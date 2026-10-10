package com.broker.policy.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "dependant_midterm_rules")
public class DependantMidtermRules {
    public static final List<String> GROUPS = List.of("SPOUSE", "PARTNER", "KIDS");
    public static final List<String> BASES = List.of("UPLOAD", "EVENT");

    public record Rule(boolean allowed, Integer windowDays, String startBasis) {}

    @Id @Column(name = "policy_id") private Long policyId;
    @Column(name = "broker_id", nullable = false, updatable = false) private Long brokerId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false) private Map<String, Rule> rules = new LinkedHashMap<>();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected DependantMidtermRules() {}

    public DependantMidtermRules(Long policyId, Long brokerId) { this.policyId = policyId; this.brokerId = brokerId; }

    @PrePersist @PreUpdate void touch() { updatedAt = Instant.now(); }

    public Long getPolicyId() { return policyId; }
    public Map<String, Rule> getRules() { return rules; }
    public void setRules(Map<String, Rule> r) { rules = new LinkedHashMap<>(r); }
}
