package com.broker.billing.api;

import com.broker.billing.domain.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;

public final class Dtos {
    private Dtos() {}

    public record AccountResponse(Long policyId, Long employerId, BigDecimal balance, BigDecimal reserved,
                                  BigDecimal available, BigDecimal thresholdAmount, boolean belowThreshold) {
        public static AccountResponse of(CdAccount a) {
            return new AccountResponse(a.getPolicyId(), a.getEmployerId(), a.getBalance(), a.getReserved(), a.available(),
                    a.getThresholdAmount(), a.available().compareTo(a.getThresholdAmount()) < 0);
        }
    }

    public record EntryResponse(Long id, String entryType, BigDecimal amount, BigDecimal balanceAfter, String referenceType,
                                String referenceId, String narration, String createdBy, Instant createdAt) {
        public static EntryResponse of(LedgerEntry e) {
            return new EntryResponse(e.getId(), e.getEntryType().name(), e.getAmount(), e.getBalanceAfter(),
                    e.getReferenceType().name(), e.getReferenceId(), e.getNarration(), e.getCreatedBy(), e.getCreatedAt());
        }
    }

    /** Amounts must be positive with at most two decimals: we reject rather than silently round money. */
    public record CreditRequest(@NotNull @DecimalMin(value = "0.01") @Digits(integer = 14, fraction = 2) BigDecimal amount,
                                @NotBlank @Size(max = 80) String reference,
                                @NotNull @Pattern(regexp = "DEPOSIT|ADJUSTMENT") String type,
                                @Size(max = 500) String narration) {}

    public record ThresholdRequest(@NotNull @DecimalMin("0") @Digits(integer = 14, fraction = 2) BigDecimal thresholdAmount) {}

    public record ReplenishmentCreate(@NotNull @DecimalMin("0.01") @Digits(integer = 14, fraction = 2) BigDecimal amount,
                                      @NotBlank @Size(max = 80) String reference) {}

    public record DecisionRequest(@Size(max = 500) String note) {}

    public record ReplenishmentResponse(Long id, BigDecimal amount, String reference, String status, String requestedBy,
                                        String decidedBy, String decisionNote, Instant createdAt, Instant decidedAt) {
        public static ReplenishmentResponse of(ReplenishmentRequest r) {
            return new ReplenishmentResponse(r.getId(), r.getAmount(), r.getReference(), r.getStatus().name(), r.getRequestedBy(),
                    r.getDecidedBy(), r.getDecisionNote(), r.getCreatedAt(), r.getDecidedAt());
        }
    }
}
