package com.broker.billing.service;

import com.broker.billing.api.ConflictException;
import com.broker.billing.api.NotFoundException;
import com.broker.billing.domain.*;
import com.broker.billing.domain.LedgerEntry.EntryType;
import com.broker.billing.domain.LedgerEntry.ReferenceType;
import com.broker.billing.outbox.OutboxPublisher;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only code that moves money. Every operation takes the account's row lock first, so concurrent jobs, events and
 * approvals serialise per account, and every balance change is an append-only ledger row keyed by a unique reference
 * (replaying anything is a no-op). Invariants (tested): balance == signed sum of the ledger; reserved == sum of HELD.
 */
@Service
public class CdLedgerService {
    public static final String TOPIC = "cd-balance.updates";

    public record CheckResult(String status, BigDecimal available, BigDecimal required, BigDecimal deficit, BigDecimal reserved) {}

    private final CdAccountRepository accounts;
    private final LedgerEntryRepository ledger;
    private final ReservationRepository reservations;
    private final OutboxPublisher outbox;
    private final JdbcClient jdbc;
    private final long ttlHours;

    CdLedgerService(CdAccountRepository accounts, LedgerEntryRepository ledger, ReservationRepository reservations,
                    OutboxPublisher outbox, JdbcClient jdbc, @Value("${app.cd.reservation-ttl-hours:24}") long ttlHours) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.reservations = reservations;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.ttlHours = ttlHours;
    }

    static BigDecimal money(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP); }

    // ── accounts ──────────────────────────────────────────────────────────────────────────────────────────────
    /** Idempotent. An existing account for the policy under a DIFFERENT broker is a tenant-integrity error. */
    @Transactional
    public CdAccount openAccount(long brokerId, long employerId, long policyId) {
        jdbc.sql("INSERT INTO billing.cd_accounts(broker_id, employer_id, policy_id) VALUES (:b, :e, :p) ON CONFLICT (policy_id) DO NOTHING")
                .param("b", brokerId).param("e", employerId).param("p", policyId).update();
        return accounts.lockByPolicyAndBroker(policyId, brokerId)
                .orElseThrow(() -> new ConflictException("Policy " + policyId + " already belongs to another broker"));
    }

    // ── the deposit check, which now reserves ─────────────────────────────────────────────────────────────────
    /**
     * Does the deposit cover {@code required}? If so the amount is RESERVED for the job (one reservation per job_id,
     * re-evaluated on every call), so a concurrent job can never spend the same money. A refund (required <= 0) needs
     * no deposit and releases any earlier hold. No account means no deposit: INSUFFICIENT.
     */
    @Transactional
    public CheckResult check(long brokerId, long policyId, UUID jobId, BigDecimal required) {
        required = money(required);
        CdAccount acct = accounts.lockByPolicyAndBroker(policyId, brokerId).orElse(null);
        if (acct == null) {
            return new CheckResult("INSUFFICIENT", BigDecimal.ZERO.setScale(2), required.max(BigDecimal.ZERO), required.max(BigDecimal.ZERO), BigDecimal.ZERO.setScale(2));
        }
        Reservation res = reservations.findByJobId(jobId).orElse(null);
        if (res != null && res.getStatus() == Reservation.Status.COMMITTED) {
            return new CheckResult("SUFFICIENT", acct.available(), required, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));   // already spent
        }
        BigDecimal myHold = res != null && res.getStatus() == Reservation.Status.HELD ? res.getAmount() : BigDecimal.ZERO;
        BigDecimal availableForJob = acct.available().add(myHold);                        // as if this job held nothing

        if (required.signum() <= 0) {
            release(acct, res);
            return new CheckResult("SUFFICIENT", availableForJob, required, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
        }
        if (availableForJob.compareTo(required) >= 0) {
            Instant expires = Instant.now().plus(ttlHours, ChronoUnit.HOURS);
            if (res == null) {
                reservations.save(new Reservation(acct.getId(), jobId, required, expires));
            } else {
                res.setAmount(required);
                res.setStatus(Reservation.Status.HELD);
                res.setExpiresAt(expires);
                res.setResolvedAt(null);
            }
            acct.setReserved(acct.getReserved().subtract(myHold).add(required));
            return new CheckResult("SUFFICIENT", availableForJob, required, BigDecimal.ZERO.setScale(2), required);
        }
        release(acct, res);                                                              // cannot cover it: hold nothing
        BigDecimal available = acct.available();
        BigDecimal deficit = required.subtract(available).max(BigDecimal.ZERO);
        return new CheckResult(available.signum() > 0 ? "PARTIAL_SUFFICIENT" : "INSUFFICIENT", available, required, deficit, BigDecimal.ZERO.setScale(2));
    }

    private void release(CdAccount acct, Reservation res) {
        if (res != null && res.getStatus() == Reservation.Status.HELD) {
            acct.setReserved(acct.getReserved().subtract(res.getAmount()));
            res.setStatus(Reservation.Status.RELEASED);
            res.setResolvedAt(Instant.now());
        }
    }

    /** Frees a job's hold (job cancelled or failed validation). Idempotent. @return true if something was released */
    @Transactional
    public boolean releaseForJob(UUID jobId) {
        Reservation res = reservations.findByJobId(jobId).orElse(null);
        if (res == null) return false;
        CdAccount acct = accounts.lockById(res.getAccountId()).orElseThrow();
        res = reservations.findByJobId(jobId).orElseThrow();                              // re-read under the lock
        if (res.getStatus() != Reservation.Status.HELD) return false;
        release(acct, res);
        return true;
    }

    /** Holds nobody collected within the TTL are freed so abandoned jobs cannot lock a deposit forever. */
    @Transactional
    public int releaseExpired(Instant now) {
        int n = 0;
        for (Reservation r : reservations.findByStatusAndExpiresAtBefore(Reservation.Status.HELD, now)) {
            if (releaseForJob(r.getJobId())) n++;
        }
        return n;
    }

    // ── committing an endorsement ─────────────────────────────────────────────────────────────────────────────
    /**
     * The endorsement was applied: turn the job's reservation into a real debit for the ACTUAL total (a refund is a
     * credit). Idempotent twice over: by event id and by the ledger's unique (account, ENDORSEMENT, job) reference.
     * If the hold had expired the debit still happens (the endorsement is already on the roster) and may leave the
     * account in deficit, which raises a low-balance event.
     */
    @Transactional
    public void commitEndorsement(UUID eventId, long brokerId, long employerId, long policyId, UUID jobId, BigDecimal total) {
        if (jdbc.sql("INSERT INTO billing.processed_events(event_id) VALUES (:id) ON CONFLICT DO NOTHING")
                .param("id", eventId).update() == 0) {
            return;                                                                        // duplicate delivery
        }
        CdAccount acct = openAccount(brokerId, employerId, policyId);                      // locked
        Reservation res = reservations.findByJobId(jobId).orElse(null);
        if (res != null && res.getStatus() == Reservation.Status.HELD) {
            acct.setReserved(acct.getReserved().subtract(res.getAmount()));
        }
        if (res != null) {
            res.setStatus(Reservation.Status.COMMITTED);
            res.setResolvedAt(Instant.now());
        }
        total = money(total);
        if (total.signum() == 0 || ledger.findByAccountIdAndReferenceTypeAndReferenceId(acct.getId(), ReferenceType.ENDORSEMENT, jobId.toString()).isPresent()) {
            return;
        }
        post(acct, total.signum() > 0 ? EntryType.DEBIT : EntryType.CREDIT, total.abs(), ReferenceType.ENDORSEMENT,
                jobId.toString(), "Endorsement " + jobId, "SYSTEM");
    }

    // ── credits ───────────────────────────────────────────────────────────────────────────────────────────────
    /** Adds money. Idempotent by (reference type, reference): replaying the same payment returns the original entry. */
    @Transactional
    public LedgerEntry credit(long brokerId, long policyId, BigDecimal amount, ReferenceType type, String reference,
                              String narration, String actor) {
        CdAccount acct = accounts.lockByPolicyAndBroker(policyId, brokerId)
                .orElseThrow(() -> new NotFoundException("No CD account for policy"));
        return creditLocked(acct, amount, type, reference, narration, actor);
    }

    LedgerEntry creditLocked(CdAccount acct, BigDecimal amount, ReferenceType type, String reference, String narration, String actor) {
        BigDecimal amt = money(amount);
        if (amt.signum() <= 0) throw new IllegalArgumentException("amount must be positive");
        var existing = ledger.findByAccountIdAndReferenceTypeAndReferenceId(acct.getId(), type, reference);
        if (existing.isPresent()) {
            LedgerEntry e = existing.get();
            if (e.getEntryType() != EntryType.CREDIT || e.getAmount().compareTo(amt) != 0) {
                throw new ConflictException("Reference '" + reference + "' was already used for a different entry");
            }
            return e;
        }
        return post(acct, EntryType.CREDIT, amt, type, reference, narration, actor);
    }

    private LedgerEntry post(CdAccount acct, EntryType type, BigDecimal amount, ReferenceType refType, String refId,
                             String narration, String actor) {
        BigDecimal after = type == EntryType.CREDIT ? acct.getBalance().add(amount) : acct.getBalance().subtract(amount);
        acct.setBalance(after);
        LedgerEntry e = ledger.saveAndFlush(new LedgerEntry(acct.getId(), type, amount, after, refType, refId, narration, actor));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("broker_id", acct.getBrokerId());
        body.put("employer_id", acct.getEmployerId());
        body.put("policy_id", acct.getPolicyId());
        body.put("entry_type", type.name());
        body.put("amount", amount.toPlainString());
        body.put("balance", after.toPlainString());
        body.put("reserved", acct.getReserved().toPlainString());
        body.put("available", acct.available().toPlainString());
        body.put("threshold", acct.getThresholdAmount().toPlainString());
        body.put("reference_type", refType.name());
        body.put("reference_id", refId);
        outbox.publish("CdAccount", acct.getPolicyId(), "CdBalanceUpdated", TOPIC, String.valueOf(acct.getBrokerId()), body);
        if (acct.available().compareTo(acct.getThresholdAmount()) < 0 && type == EntryType.DEBIT) {
            outbox.publish("CdAccount", acct.getPolicyId(), "CdBalanceLow", TOPIC, String.valueOf(acct.getBrokerId()), body);
        }
        return e;
    }

    @Transactional
    public CdAccount setThreshold(long brokerId, long policyId, BigDecimal threshold) {
        CdAccount a = accounts.lockByPolicyAndBroker(policyId, brokerId).orElseThrow(() -> new NotFoundException("No CD account for policy"));
        a.setThresholdAmount(money(threshold));
        return a;
    }
}
