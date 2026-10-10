package com.broker.billing.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Immutable
@Table(name = "cd_ledger")
public class LedgerEntry {
    public enum EntryType { CREDIT, DEBIT }
    public enum ReferenceType { DEPOSIT, REPLENISHMENT, ENDORSEMENT, ADJUSTMENT }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "account_id", nullable = false, updatable = false) private Long accountId;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "entry_type", nullable = false, updatable = false) private EntryType entryType;
    @Column(nullable = false, updatable = false) private BigDecimal amount;
    @Column(name = "balance_after", nullable = false, updatable = false) private BigDecimal balanceAfter;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "reference_type", nullable = false, updatable = false) private ReferenceType referenceType;
    @Column(name = "reference_id", nullable = false, updatable = false) private String referenceId;
    @Column(updatable = false) private String narration;
    @Column(name = "created_by", nullable = false, updatable = false) private String createdBy;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();

    protected LedgerEntry() {}

    public LedgerEntry(Long accountId, EntryType type, BigDecimal amount, BigDecimal balanceAfter, ReferenceType refType,
                       String refId, String narration, String createdBy) {
        this.accountId = accountId;
        this.entryType = type;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.referenceType = refType;
        this.referenceId = refId;
        this.narration = narration;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public EntryType getEntryType() { return entryType; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public ReferenceType getReferenceType() { return referenceType; }
    public String getReferenceId() { return referenceId; }
    public String getNarration() { return narration; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
