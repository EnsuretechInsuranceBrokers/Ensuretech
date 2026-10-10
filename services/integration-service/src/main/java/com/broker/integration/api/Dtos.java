package com.broker.integration.api;

import com.broker.integration.domain.SyncJob;
import com.broker.integration.domain.SyncRejection;
import com.broker.integration.domain.TpaConfig;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class Dtos {
    private Dtos() {}

    record TpaConfigRequest(@NotNull Short tpaId, @NotNull TpaConfig.Adapter adapter, @NotBlank @Size(max = 300) String baseUrl,
                            @NotNull TpaConfig.AuthType authType,
                            @Pattern(regexp = "[A-Z][A-Z0-9_]{2,40}", message = "an upper-case environment variable prefix, e.g. CARE_PROD") String secretRef,
                            @Min(1) @Max(1000) Integer batchSize, @Min(100) @Max(60000) Integer timeoutMs, Boolean enabled) {}

    /** Never carries credentials: only the NAME of the env-var prefix they are read from. */
    record TpaConfigResponse(Long id, Short tpaId, TpaConfig.Adapter adapter, String baseUrl, TpaConfig.AuthType authType,
                             String secretRef, int batchSize, int timeoutMs, boolean enabled, Instant updatedAt) {
        static TpaConfigResponse of(TpaConfig c) {
            return new TpaConfigResponse(c.getId(), c.getTpaId(), c.getAdapter(), c.getBaseUrl(), c.getAuthType(), c.getSecretRef(),
                    c.getBatchSize(), c.getTimeoutMs(), c.isEnabled(), c.getUpdatedAt());
        }
    }

    record SyncJobResponse(UUID id, UUID endorsementJobId, Long policyId, Long employerId, Short tpaId, String opType, int membersExpected,
                           SyncJob.Status status, int attempts, Instant nextAttemptAt, int syncedCount, int rejectedCount, String lastError,
                           Instant createdAt, Instant completedAt, List<Rejection> rejections) {
        static SyncJobResponse of(SyncJob j, List<SyncRejection> rej) {
            return new SyncJobResponse(j.getId(), j.getEndorsementJobId(), j.getPolicyId(), j.getEmployerId(), j.getTpaId(), j.getOpType(),
                    j.getMembersExpected(), j.getStatus(), j.getAttempts(), j.getNextAttemptAt(), j.getSyncedCount(), j.getRejectedCount(),
                    j.getLastError(), j.getCreatedAt(), j.getCompletedAt(), rej == null ? null : rej.stream().map(r -> new Rejection(r.getMemberId(), r.getReason())).toList());
        }
    }

    record Rejection(Long memberId, String reason) {}
}
