package com.broker.integration.sync;

import com.broker.integration.domain.*;
import com.broker.integration.engine.EngineClient;
import com.broker.integration.engine.EngineException;
import com.broker.integration.outbox.OutboxPublisher;
import com.broker.integration.tpa.TpaAdapter;
import com.broker.integration.tpa.TpaAdapter.MemberResult;
import com.broker.integration.tpa.TpaAdapter.SyncBatch;
import com.broker.integration.tpa.TpaGateway;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns EndorsementApplied events into TPA enrolments.
 *
 * A sync job is the work item AND the log. It is claimed with a row lock and a lease (a crashed worker's job is reclaimed),
 * and it works through the endorsement's roster rows in keyset batches, persisting a cursor after each batch, so a retry
 * resumes where it stopped instead of resending everything. Resends of the batch in flight reuse the same idempotency key.
 * Failures are classified: retryable ones back off exponentially and eventually dead-letter (DEAD, with a TpaSyncFailed event
 * and an operator retry); permanent ones dead-letter at once; an open circuit just waits without burning attempts.
 */
@Service
public class SyncService {
    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    public static final String TOPIC = "tpa.events";

    private final SyncJobRepository jobs;
    private final PolicyRouteRepository routes;
    private final TpaConfigRepository configs;
    private final SyncRejectionRepository rejections;
    private final TpaGateway gateway;
    private final EngineClient engine;
    private final OutboxPublisher outbox;
    private final TransactionTemplate tx;
    private final int maxAttempts;
    private final long leaseSeconds, backoffBase, backoffMax, breakerOpenSeconds;

    SyncService(SyncJobRepository jobs, PolicyRouteRepository routes, TpaConfigRepository configs, SyncRejectionRepository rejections,
                TpaGateway gateway, EngineClient engine, OutboxPublisher outbox, PlatformTransactionManager tm,
                @Value("${app.sync.max-attempts:8}") int maxAttempts, @Value("${app.sync.lease-seconds:300}") long leaseSeconds,
                @Value("${app.sync.backoff-base-seconds:30}") long backoffBase, @Value("${app.sync.backoff-max-seconds:3600}") long backoffMax,
                @Value("${app.sync.breaker-open-seconds:30}") long breakerOpenSeconds) {
        this.jobs = jobs; this.routes = routes; this.configs = configs; this.rejections = rejections; this.gateway = gateway;
        this.engine = engine; this.outbox = outbox; this.tx = new TransactionTemplate(tm);
        this.maxAttempts = maxAttempts; this.leaseSeconds = leaseSeconds; this.backoffBase = backoffBase; this.backoffMax = backoffMax;
        this.breakerOpenSeconds = breakerOpenSeconds;
    }

    // ── ingest (event handlers) ──────────────────────────────────────────────────────────────────────────────
    @Transactional
    public void onPolicyApproved(long brokerId, long employerId, long policyId, Short tpaId) {
        routes.findById(policyId).ifPresentOrElse(r -> r.update(brokerId, employerId, tpaId),
                () -> routes.save(new PolicyRoute(policyId, brokerId, employerId, tpaId)));
    }

    /** Idempotent on eventId. A concurrent duplicate loses on the unique key; the handler treats that as already done. */
    @Transactional
    public void onEndorsementApplied(UUID eventId, UUID endorsementJobId, long brokerId, long employerId, long policyId, String opType, int members) {
        if (jobs.findByEventId(eventId).isPresent()) return;
        Optional<PolicyRoute> route = routes.findById(policyId);
        SyncJob job = new SyncJob(eventId, endorsementJobId, brokerId, employerId, policyId, route.map(PolicyRoute::getTpaId).orElse(null), opType, members);
        if (members <= 0) skip(job, "endorsement changed no members");
        else if (route.isPresent() && route.get().getTpaId() == null) skip(job, "policy has no TPA");
        jobs.save(job);
    }

    private void skip(SyncJob job, String why) {
        job.setStatus(SyncJob.Status.SKIPPED);
        job.setLastError(why);
        job.setCompletedAt(Instant.now());
    }

    // ── work loop ────────────────────────────────────────────────────────────────────────────────────────────
    /** Claims the next due job (lease + row lock) and returns its id, or empty. */
    public Optional<UUID> claimNext() {
        return tx.execute(s -> {
            Instant now = Instant.now();
            List<SyncJob> due = jobs.claimDue(now);
            if (due.isEmpty()) return Optional.<UUID>empty();
            SyncJob j = due.get(0);
            j.setStatus(SyncJob.Status.IN_PROGRESS);
            j.setLeaseUntil(now.plusSeconds(leaseSeconds));
            return Optional.of(j.getId());
        });
    }

    public void process(UUID id) {
        try {
            run(id);
        } catch (CallNotPermittedException e) {
            reschedule(id, "circuit open for this TPA; waiting", breakerOpenSeconds, false);   // an outage in progress is not this job's fault
        } catch (TpaAdapter.TpaException e) {
            fail(id, e.getMessage(), e.isRetryable());
        } catch (EngineException e) {
            fail(id, e.getMessage(), e.isRetryable());
        } catch (JobCancelled e) {
            log.info("sync job {} was cancelled by an operator; stopping", id);
        } catch (RuntimeException e) {
            log.error("sync job {} failed unexpectedly", id, e);
            fail(id, "unexpected error: " + e.getClass().getSimpleName(), true);        // counts attempts, so a bug cannot spin forever
        }
    }

    /** Thrown inside the work loop when an operator has cancelled the job. */
    private static final class JobCancelled extends RuntimeException {
        JobCancelled() { super("cancelled", null, false, false); }
    }

    boolean isCancelled(UUID id) {
        return Boolean.TRUE.equals(tx.execute(s -> jobs.findById(id).map(j -> j.getStatus() == SyncJob.Status.CANCELLED).orElse(false)));
    }

    private void run(UUID id) {
        SyncJob job = jobs.findById(id).orElseThrow();
        Short known = job.getTpaId();
        if (known == null) {
            PolicyRoute route = routes.findById(job.getPolicyId())
                    .orElseThrow(() -> new EngineException("policy " + job.getPolicyId() + " has no route yet (PolicyApproved not seen)", true));
            known = route.getTpaId();
            if (known == null) { finish(id, j -> skip(j, "policy has no TPA"), null); return; }
            Short resolved = known;
            tx.executeWithoutResult(s -> jobs.findById(id).orElseThrow().setTpaId(resolved));
        }
        final Short tpaId = known;
        TpaConfig cfg = configs.findByBrokerIdAndTpaId(job.getBrokerId(), tpaId).filter(TpaConfig::isEnabled)
                .orElseThrow(() -> new TpaAdapter.TpaException("no enabled TPA configuration for tpa " + tpaId, false));

        report(job.getEndorsementJobId(), "TPA_SYNC_QUEUED");
        long cursor = job.getCursorId();
        while (true) {
            if (isCancelled(id)) throw new JobCancelled();                                // an operator stopped it between batches
            EngineClient.Page page = engine.members(job.getEndorsementJobId(), cursor, cfg.getBatchSize());
            if (page.members().isEmpty()) break;
            long first = page.members().getFirst().id(), last = page.members().getLast().id();
            SyncBatch batch = new SyncBatch(job.getId() + ":" + first + "-" + last, page.opType(), job.getPolicyId(), page.members());
            List<MemberResult> results = gateway.enroll(cfg, batch);

            Map<Long, String> tpaIds = new LinkedHashMap<>();
            results.stream().filter(MemberResult::accepted).forEach(r -> tpaIds.put(r.id(), r.tpaMemberId()));
            engine.recordTpaIds(job.getEndorsementJobId(), tpaIds);                       // before the cursor moves: a failure resends the same keyed batch

            long newCursor = last;
            int rejected = (int) results.stream().filter(r -> !r.accepted()).count();
            tx.executeWithoutResult(s -> {
                for (MemberResult r : results) {
                    if (!r.accepted() && !rejections.existsBySyncJobIdAndMemberId(id, r.id())) rejections.save(new SyncRejection(id, r.id(), r.reason()));
                }
                SyncJob j = jobs.findById(id).orElseThrow();
                if (j.getStatus() == SyncJob.Status.CANCELLED) return;                    // cancelled mid-batch: keep what was sent, change nothing else
                j.progress(newCursor, tpaIds.size(), rejected);
                j.setLeaseUntil(Instant.now().plusSeconds(leaseSeconds));                 // still alive
            });
            cursor = newCursor;
            if (page.nextAfter() == null) break;
        }
        finish(id, j -> { j.setStatus(SyncJob.Status.SUCCEEDED); j.setLastError(null); j.setCompletedAt(Instant.now()); }, "TpaSyncCompleted");
        report(job.getEndorsementJobId(), "TPA_SYNC_COMPLETED");
    }

    /** Best-effort: the engine's lifecycle is informational for the sync, so a refusal (409) or an outage must not fail or retry the TPA work. */
    private void report(UUID endorsementJobId, String state) {
        try { engine.reportLifecycle(endorsementJobId, state, null); }
        catch (EngineException e) { log.warn("could not report {} for endorsement {}: {}", state, endorsementJobId, e.getMessage()); }
    }

    private void finish(UUID id, java.util.function.Consumer<SyncJob> change, String event) {
        tx.executeWithoutResult(s -> {
            SyncJob j = jobs.findById(id).orElseThrow();
            if (j.getStatus() == SyncJob.Status.CANCELLED) return;                        // an operator's decision outranks the worker's result
            change.accept(j);
            j.setLeaseUntil(null);
            if (event != null) emit(event, j, null);
        });
    }

    private void fail(UUID id, String error, boolean retryable) {
        java.util.concurrent.atomic.AtomicReference<UUID> dead = new java.util.concurrent.atomic.AtomicReference<>();
        tx.executeWithoutResult(s -> {
            SyncJob j = jobs.findById(id).orElseThrow();
            if (j.getStatus() == SyncJob.Status.CANCELLED) return;                        // killed: do not retry, do not dead-letter
            j.setAttempts(j.getAttempts() + 1);
            j.setLastError(error);
            j.setLeaseUntil(null);
            if (!retryable || j.getAttempts() >= maxAttempts) {
                j.setStatus(SyncJob.Status.DEAD);
                j.setCompletedAt(Instant.now());
                emit("TpaSyncFailed", j, error);
                dead.set(j.getEndorsementJobId());
                log.warn("sync job {} dead-lettered after {} attempt(s): {}", id, j.getAttempts(), error);
            } else {
                j.setStatus(SyncJob.Status.PENDING);
                j.setNextAttemptAt(Instant.now().plus(backoff(j.getAttempts())));
            }
        });
        if (dead.get() != null) report(dead.get(), "TPA_SYNC_FAILED");
    }

    private void reschedule(UUID id, String why, long seconds, boolean countAttempt) {
        tx.executeWithoutResult(s -> {
            SyncJob j = jobs.findById(id).orElseThrow();
            if (j.getStatus() == SyncJob.Status.CANCELLED) return;
            if (countAttempt) j.setAttempts(j.getAttempts() + 1);
            j.setStatus(SyncJob.Status.PENDING);
            j.setLastError(why);
            j.setLeaseUntil(null);
            j.setNextAttemptAt(Instant.now().plusSeconds(seconds));
        });
    }

    Duration backoff(int attempts) {
        long secs = Math.min(backoffMax, backoffBase * (1L << Math.min(attempts - 1, 20)));
        return Duration.ofMillis((long) (secs * 1000 * (0.8 + 0.4 * ThreadLocalRandom.current().nextDouble())));   // ±20% jitter
    }

    private void emit(String type, SyncJob j, String error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sync_job_id", j.getId().toString());
        body.put("endorsement_job_id", j.getEndorsementJobId().toString());
        body.put("broker_id", j.getBrokerId());
        body.put("employer_id", j.getEmployerId());
        body.put("policy_id", j.getPolicyId());
        body.put("tpa_id", j.getTpaId());
        body.put("op_type", j.getOpType());
        body.put("members_expected", j.getMembersExpected());
        body.put("synced", j.getSyncedCount());
        body.put("rejected", j.getRejectedCount());
        if (error != null) { body.put("error", error); body.put("attempts", j.getAttempts()); }
        outbox.publish("TpaSync", j.getId(), type, TOPIC, String.valueOf(j.getBrokerId()), body);
    }

    /** Operator action: a dead-lettered job goes back to the queue with a clean attempt count; its cursor is kept. */
    @Transactional
    public boolean retryDead(long brokerId, UUID id) {
        SyncJob j = jobs.findByIdAndBrokerId(id, brokerId).orElseThrow(() -> new com.broker.integration.api.NotFoundException("No such sync job"));
        if (j.getStatus() != SyncJob.Status.DEAD) return false;
        j.setStatus(SyncJob.Status.PENDING);
        j.setAttempts(0);
        j.setNextAttemptAt(Instant.now());
        j.setCompletedAt(null);
        return true;
    }
}
