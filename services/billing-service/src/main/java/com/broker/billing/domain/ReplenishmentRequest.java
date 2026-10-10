package com.broker.billing.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "cd_replenishment_requests")
public class ReplenishmentRequest {
    public enum Status { REQUESTED, APPROVED, REJECTED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "account_id", nullable = false, updatable = false) private Long accountId;
    @Column(nullable = false, updatable = false) private BigDecimal amount;
    @Column(nullable = false, updatable = false) private String reference;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false) private Status status = Status.REQUESTED;
    @Column(name = "requested_by", nullable = false, updatable = false) private String requestedBy;
    @Column(name = "decided_by") private String decidedBy;
    @Column(name = "decision_note") private String decisionNote;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(name = "decided_at") private Instant decidedAt;

    protected ReplenishmentRequest() {}

    public ReplenishmentRequest(Long accountId, BigDecimal amount, String reference, String requestedBy) {
        this.accountId = accountId;
        this.amount = amount;
        this.reference = reference;
        this.requestedBy = requestedBy;
    }

    public void decide(Status s, String by, String note) {
        status = s;
        decidedBy = by;
        decisionNote = note;
        decidedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public BigDecimal getAmount() { return amount; }
    public String getReference() { return reference; }
    public Status getStatus() { return status; }
    public String getRequestedBy() { return requestedBy; }
    public String getDecidedBy() { return decidedBy; }
    public String getDecisionNote() { return decisionNote; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDecidedAt() { return decidedAt; }
}
