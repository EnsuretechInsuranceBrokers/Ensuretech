package com.broker.integration.ops;

import com.broker.integration.domain.SyncJob;
import com.broker.integration.domain.SyncJobRepository;
import com.broker.integration.sync.SyncWorker;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * What an operator can do to a background job, and the password that guards it.
 *
 * Killing or re-queuing a job needs two things: a SYSTEM token (the gateway lock: only service accounts and operators with super credentials get this far) and the
 * job-killer password, a second secret that exists only as a bcrypt hash in this database. A wrong password is audited; five wrong passwords in a row lock the
 * controls for 15 minutes, for everyone, so the password cannot be guessed. Every attempt, successful or not, lands in integration.job_control_audit.
 */
@Service
public class JobControlService implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(JobControlService.class);
    static final int MAX_FAILURES = 5;
    static final int LOCK_MINUTES = 15;
    static final int MIN_PASSWORD = 12;

    private final JdbcClient jdbc;
    private final SyncJobRepository jobs;
    private final SyncWorker worker;
    private final TransactionTemplate tx;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final String bootstrapPassword;

    JobControlService(JdbcClient jdbc, SyncJobRepository jobs, SyncWorker worker, PlatformTransactionManager tm,
                      @Value("${app.job-control.bootstrap-password:}") String bootstrapPassword) {
        this.jdbc = jdbc; this.jobs = jobs; this.worker = worker; this.tx = new TransactionTemplate(tm); this.bootstrapPassword = bootstrapPassword;
    }

    /** First start only: when no password exists yet, hash the one in JOB_KILLER_BOOTSTRAP_PASSWORD (development) and store that. Afterwards the variable is ignored. */
    @Override
    public void run(ApplicationArguments args) {
        if (bootstrapPassword.isBlank()) return;
        if (bootstrapPassword.length() < MIN_PASSWORD) throw new IllegalStateException("JOB_KILLER_BOOTSTRAP_PASSWORD must be at least " + MIN_PASSWORD + " characters");
        int n = jdbc.sql("INSERT INTO integration.job_control_credentials (id, password_hash, set_by) VALUES (1, :h, 'bootstrap') ON CONFLICT (id) DO NOTHING")
                .param("h", encoder.encode(bootstrapPassword)).update();
        if (n == 1) { audit("BOOTSTRAP", null, "system", null, "OK", "initial job-killer password set from the environment"); log.warn("job-killer password initialised from JOB_KILLER_BOOTSTRAP_PASSWORD: change it with PUT /api/v1/ops/jobs/password"); }
    }

    // ── reading ──────────────────────────────────────────────────────────────────────────────────────────────
    public List<Map<String, Object>> list(String status, int limit) {
        var q = jdbc.sql("SELECT id FROM integration.sync_jobs " + (status == null ? "" : "WHERE status = CAST(:s AS integration.sync_status_enum) ")
                + "ORDER BY CASE status::text WHEN 'IN_PROGRESS' THEN 0 WHEN 'PENDING' THEN 1 WHEN 'DEAD' THEN 2 ELSE 3 END, updated_at DESC LIMIT :n");
        if (status != null) q = q.param("s", status);
        List<UUID> ids = q.param("n", Math.min(Math.max(limit, 1), 500)).query(UUID.class).list();
        List<Map<String, Object>> out = new ArrayList<>();
        for (UUID id : ids) out.add(view(jobs.findById(id).orElseThrow()));
        return out;
    }

    public Map<String, Object> get(UUID id) {
        return view(jobs.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such job")));
    }

    Map<String, Object> view(SyncJob j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", j.getId()); m.put("status", j.getStatus().name()); m.put("brokerId", j.getBrokerId()); m.put("employerId", j.getEmployerId()); m.put("policyId", j.getPolicyId());
        m.put("tpaId", j.getTpaId()); m.put("opType", j.getOpType()); m.put("membersExpected", j.getMembersExpected()); m.put("syncedCount", j.getSyncedCount());
        m.put("rejectedCount", j.getRejectedCount()); m.put("attempts", j.getAttempts()); m.put("lastError", j.getLastError());
        m.put("runningNow", worker.isRunning(j.getId()));                                  // a pool thread is working it right now, in this process
        m.put("nextAttemptAt", j.getNextAttemptAt()); m.put("createdAt", j.getCreatedAt()); m.put("updatedAt", j.getUpdatedAt()); m.put("completedAt", j.getCompletedAt());
        m.put("ageSeconds", ChronoUnit.SECONDS.between(j.getCreatedAt(), j.getCompletedAt() == null ? Instant.now() : j.getCompletedAt()));
        return m;
    }

    public List<Map<String, Object>> audit(int limit) {
        return jdbc.sql("SELECT id, at, action, job_id, actor, remote_addr, outcome, detail FROM integration.job_control_audit ORDER BY id DESC LIMIT :n").param("n", Math.min(Math.max(limit, 1), 500))
                .query((rs, i) -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("id", rs.getLong(1)); m.put("at", rs.getTimestamp(2).toInstant()); m.put("action", rs.getString(3));
                    m.put("jobId", rs.getObject(4)); m.put("actor", rs.getString(5)); m.put("remoteAddr", rs.getString(6)); m.put("outcome", rs.getString(7)); m.put("detail", rs.getString(8)); return m; }).list();
    }

    // ── acting: always behind the password ──────────────────────────────────────────────────────────────────────
    /** Stops a job: a waiting one will never start; a running one has its worker thread interrupted. What it already sent to the TPA stays sent (batches are keyed, so a later re-queue does not duplicate). */
    public Map<String, Object> kill(UUID id, String password, String reason, String actor, String remote) {
        requirePassword("KILL", id, password, actor, remote);
        if (reason == null || reason.isBlank()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Say why: a reason is required");
        Boolean changed = tx.execute(s -> {
            SyncJob j = jobs.findById(id).orElse(null);
            if (j == null) return null;
            if (j.getStatus() != SyncJob.Status.PENDING && j.getStatus() != SyncJob.Status.IN_PROGRESS) return false;
            j.setStatus(SyncJob.Status.CANCELLED); j.setLeaseUntil(null); j.setCompletedAt(Instant.now());
            j.setLastError("Killed by an operator: " + reason.substring(0, Math.min(reason.length(), 300)));
            return true;
        });
        if (changed == null) { audit("KILL", id, actor, remote, "NOT_FOUND", reason); throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such job"); }
        if (!changed) { audit("KILL", id, actor, remote, "NOT_ALLOWED", "job is not pending or running"); throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a pending or running job can be killed"); }
        boolean interrupted = worker.interrupt(id);                                         // after the commit, so the worker finds CANCELLED when it wakes
        audit("KILL", id, actor, remote, "OK", reason + (interrupted ? " (worker thread interrupted)" : " (no thread was running it)"));
        Map<String, Object> out = get(id);
        out.put("interrupted", interrupted);
        return out;
    }

    /** Puts a dead or cancelled job back in the queue with a fresh set of attempts. */
    public Map<String, Object> requeue(UUID id, String password, String actor, String remote) {
        requirePassword("REQUEUE", id, password, actor, remote);
        Boolean changed = tx.execute(s -> {
            SyncJob j = jobs.findById(id).orElse(null);
            if (j == null) return null;
            if (j.getStatus() != SyncJob.Status.DEAD && j.getStatus() != SyncJob.Status.CANCELLED) return false;
            j.setStatus(SyncJob.Status.PENDING); j.setAttempts(0); j.setNextAttemptAt(Instant.now()); j.setCompletedAt(null); j.setLeaseUntil(null);
            return true;
        });
        if (changed == null) { audit("REQUEUE", id, actor, remote, "NOT_FOUND", null); throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such job"); }
        if (!changed) { audit("REQUEUE", id, actor, remote, "NOT_ALLOWED", "job is not dead or cancelled"); throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a dead or cancelled job can be re-queued"); }
        audit("REQUEUE", id, actor, remote, "OK", null);
        return get(id);
    }

    public void changePassword(String current, String next, String actor, String remote) {
        if (next == null || next.length() < MIN_PASSWORD) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "The new password needs at least " + MIN_PASSWORD + " characters");
        requirePassword("PASSWORD_CHANGE", null, current, actor, remote);
        jdbc.sql("UPDATE integration.job_control_credentials SET password_hash = :h, set_at = now(), set_by = :a WHERE id = 1").param("h", encoder.encode(next)).param("a", actor).update();
        audit("PASSWORD_CHANGE", null, actor, remote, "OK", null);
    }

    // ── the password check, with lockout ────────────────────────────────────────────────────────────────────────
    private void requirePassword(String action, UUID job, String given, String actor, String remote) {
        String outcome = tx.execute(s -> {
            var row = jdbc.sql("SELECT password_hash, failed_attempts, locked_until FROM integration.job_control_credentials WHERE id = 1 FOR UPDATE")
                    .query((rs, i) -> new Object[] {rs.getString(1), rs.getInt(2), rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant()}).optional().orElse(null);
            if (row == null) return "NOT_SET";
            Instant lockedUntil = (Instant) row[2];
            if (lockedUntil != null && lockedUntil.isAfter(Instant.now())) return "LOCKED";
            if (given != null && !given.isEmpty() && encoder.matches(given, (String) row[0])) {
                jdbc.sql("UPDATE integration.job_control_credentials SET failed_attempts = 0, locked_until = NULL WHERE id = 1").update();
                return "OK";
            }
            int fails = (Integer) row[1] + 1;
            jdbc.sql("UPDATE integration.job_control_credentials SET failed_attempts = :f, locked_until = :l WHERE id = 1").param("f", fails >= MAX_FAILURES ? 0 : fails)
                    .param("l", fails >= MAX_FAILURES ? java.sql.Timestamp.from(Instant.now().plus(LOCK_MINUTES, ChronoUnit.MINUTES)) : null).update();
            return "WRONG_PASSWORD";
        });
        if (!"OK".equals(outcome)) {
            audit(action, job, actor, remote, outcome, null);
            switch (outcome) {
                case "LOCKED" -> throw new ResponseStatusException(HttpStatus.LOCKED, "Job controls are locked after repeated wrong passwords. Try again later.");
                case "NOT_SET" -> throw new ResponseStatusException(HttpStatus.CONFLICT, "No job-killer password has been set on this deployment");
                default -> throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Wrong job-killer password");
            }
        }
    }

    private void audit(String action, UUID job, String actor, String remote, String outcome, String detail) {
        jdbc.sql("INSERT INTO integration.job_control_audit (action, job_id, actor, remote_addr, outcome, detail) VALUES (:a, :j, :u, :r, :o, :d)")
                .param("a", action).param("j", job).param("u", actor).param("r", remote).param("o", outcome).param("d", detail == null ? null : detail.substring(0, Math.min(detail.length(), 500))).update();
    }
}
