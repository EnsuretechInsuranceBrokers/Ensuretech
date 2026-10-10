package com.broker.policy.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "rate_cards")
public class RateCard {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "policy_id", nullable = false, updatable = false) private Long policyId;
    @Column(name = "broker_id", nullable = false, updatable = false) private Long brokerId;
    @Column(name = "effective_from", nullable = false) private LocalDate effectiveFrom;
    @Column(name = "effective_to") private LocalDate effectiveTo;
    @Column(name = "tax_rate", nullable = false) private BigDecimal taxRate;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rate_table", nullable = false) private Map<String, Object> rateTable = new HashMap<>();
    @Column(name = "is_active", nullable = false) private boolean active = true;
    @Column(name = "is_test_data", nullable = false) private boolean testData;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected RateCard() {}

    public RateCard(Long policyId, Long brokerId, LocalDate effectiveFrom, BigDecimal taxRate,
                    Map<String, Object> rateTable) {
        this.policyId = policyId;
        this.brokerId = brokerId;
        this.effectiveFrom = effectiveFrom;
        this.taxRate = taxRate;
        this.rateTable = rateTable;
    }

    @PrePersist void onCreate() { createdAt = Instant.now(); }

    public Long getId() { return id; }
    public Long getPolicyId() { return policyId; }
    public Long getBrokerId() { return brokerId; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public LocalDate getEffectiveTo() { return effectiveTo; }
    public void setEffectiveTo(LocalDate v) { effectiveTo = v; }
    public BigDecimal getTaxRate() { return taxRate; }
    public Map<String, Object> getRateTable() { return rateTable; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { active = v; }
}
