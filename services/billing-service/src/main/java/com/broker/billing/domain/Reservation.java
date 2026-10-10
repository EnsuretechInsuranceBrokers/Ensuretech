package com.broker.billing.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "cd_reservations")
public class Reservation {
    public enum Status { HELD, COMMITTED, RELEASED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "account_id", nullable = false, updatable = false) private Long accountId;
    @Column(name = "job_id", nullable = false, updatable = false) private UUID jobId;
    @Column(nullable = false) private BigDecimal amount;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false) private Status status = Status.HELD;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(name = "resolved_at") private Instant resolvedAt;

    protected Reservation() {}

    public Reservation(Long accountId, UUID jobId, BigDecimal amount, Instant expiresAt) {
        this.accountId = accountId;
        this.jobId = jobId;
        this.amount = amount;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public UUID getJobId() { return jobId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { amount = v; }
    public Status getStatus() { return status; }
    public void setStatus(Status v) { status = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { expiresAt = v; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant v) { resolvedAt = v; }
}
