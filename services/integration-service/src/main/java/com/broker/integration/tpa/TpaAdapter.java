package com.broker.integration.tpa;

import com.broker.integration.domain.TpaConfig;
import java.util.List;

/** One implementation per TPA wire protocol. The sync engine knows nothing TPA-specific; per-TPA quirks live behind this. */
public interface TpaAdapter {
    TpaConfig.Adapter type();

    /** Send one batch. Must be idempotent on {@code batch.idempotencyKey()} at the TPA, because retries resend the same key. */
    List<MemberResult> enroll(TpaConfig cfg, Credentials creds, SyncBatch batch) throws TpaException;

    /** Pull: the members the TPA says are covered under a policy. Adapters that cannot pull keep the default. */
    default List<Reconciler.ActiveLife> activeLives(TpaConfig cfg, Credentials creds, String policyNumber) throws TpaException {
        throw new TpaException("this adapter cannot list a policy's active lives", false);
    }

    record Credentials(String username, String password, String token) {
        public static final Credentials NONE = new Credentials(null, null, null);
    }

    record RosterMember(long id, String employeeCode, String relationCode, String firstName, String lastName, String dateOfBirth,
                        String gender, String email, String mobile, String sumInsured, String effectiveDate, String endDate,
                        boolean active) {}

    record SyncBatch(String idempotencyKey, String operation, long policyId, List<RosterMember> members) {}

    /** accepted=true carries the TPA's own member id; otherwise reason says why this member was refused. */
    record MemberResult(long id, boolean accepted, String tpaMemberId, String reason) {}

    class TpaException extends RuntimeException {
        private final boolean retryable;

        public TpaException(String message, boolean retryable, Throwable cause) { super(message, cause); this.retryable = retryable; }

        public TpaException(String message, boolean retryable) { this(message, retryable, null); }

        /** True for outages, timeouts, 408/429/5xx; false when resending the same request cannot succeed (4xx, bad response). */
        public boolean isRetryable() { return retryable; }
    }
}
