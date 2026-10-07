package com.broker.policy.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "contribution_plans")
public class ContributionPlan {
    /** The member groups a contribution can be set for (legacy: self, spouse, per kid, single/double parent, single/double parent-in-law, others). */
    public static final java.util.List<String> GROUPS = java.util.List.of("SELF", "SPOUSE", "CHILD", "PARENT_SINGLE", "PARENT_DOUBLE",
            "PARENT_IN_LAW_SINGLE", "PARENT_IN_LAW_DOUBLE", "OTHERS");

    public enum Type { NONE, PERCENT, FIXED }

    @Id @Column(name = "policy_id") private Long policyId;
    @Column(name = "broker_id", nullable = false, updatable = false) private Long brokerId;
    @Enumerated(EnumType.STRING) @Column(name = "contribution_type", nullable = false) private Type type = Type.NONE;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "groups", nullable = false) private Map<String, BigDecimal> groups = new LinkedHashMap<>();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected ContributionPlan() {}

    public ContributionPlan(Long policyId, Long brokerId) { this.policyId = policyId; this.brokerId = brokerId; }

    @PrePersist @PreUpdate void touch() { updatedAt = Instant.now(); }

    public Long getPolicyId() { return policyId; }
    public Long getBrokerId() { return brokerId; }
    public Type getType() { return type; }
    public void setType(Type t) { type = t; }
    public Map<String, BigDecimal> getGroups() { return groups; }
    public void setGroups(Map<String, BigDecimal> g) { groups = new LinkedHashMap<>(g); }
}
