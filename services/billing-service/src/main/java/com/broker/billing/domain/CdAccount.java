package com.broker.billing.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "cd_accounts")
public class CdAccount {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "broker_id", nullable = false, updatable = false) private Long brokerId;
    @Column(name = "employer_id", nullable = false, updatable = false) private Long employerId;
    @Column(name = "policy_id", nullable = false, updatable = false) private Long policyId;
    @Column(nullable = false) private BigDecimal balance = BigDecimal.ZERO;
    @Column(nullable = false) private BigDecimal reserved = BigDecimal.ZERO;
    @Column(name = "threshold_amount", nullable = false) private BigDecimal thresholdAmount = BigDecimal.ZERO;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected CdAccount() {}

    @PrePersist void onCreate() { createdAt = updatedAt = Instant.now(); }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    /** Money that is not spoken for: what a new job may reserve. Negative when the account is in deficit. */
    public BigDecimal available() { return balance.subtract(reserved); }

    public Long getId() { return id; }
    public Long getBrokerId() { return brokerId; }
    public Long getEmployerId() { return employerId; }
    public Long getPolicyId() { return policyId; }
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal v) { balance = v; }
    public BigDecimal getReserved() { return reserved; }
    public void setReserved(BigDecimal v) { reserved = v; }
    public BigDecimal getThresholdAmount() { return thresholdAmount; }
    public void setThresholdAmount(BigDecimal v) { thresholdAmount = v; }
}
