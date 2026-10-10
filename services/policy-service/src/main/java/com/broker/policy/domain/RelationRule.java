package com.broker.policy.domain;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "relation_rules")
public class RelationRule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "policy_id", nullable = false, updatable = false) private Long policyId;
    @Column(name = "broker_id", nullable = false, updatable = false) private Long brokerId;
    @Column(name = "relation_code", nullable = false) private String relationCode;
    @Column(name = "min_age") private Short minAge;
    @Column(name = "max_age") private Short maxAge;
    @Column(name = "age_difference") private Short ageDifference;
    @Column(name = "difference_from_relation") private String differenceFromRelation;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM) @Column(name = "pro_rata_type") private ProRataType proRataType;
    @Column(name = "max_twins", nullable = false) private Short maxTwins = 0;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected RelationRule() {}

    public RelationRule(Long policyId, Long brokerId, String relationCode) {
        this.policyId = policyId; this.brokerId = brokerId; this.relationCode = relationCode;
    }

    @PrePersist void onCreate() { createdAt = updatedAt = Instant.now(); }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public Long getPolicyId() { return policyId; }
    public String getRelationCode() { return relationCode; }
    public Short getMinAge() { return minAge; }
    public void setMinAge(Short v) { minAge = v; }
    public Short getMaxAge() { return maxAge; }
    public void setMaxAge(Short v) { maxAge = v; }
    public Short getAgeDifference() { return ageDifference; }
    public void setAgeDifference(Short v) { ageDifference = v; }
    public String getDifferenceFromRelation() { return differenceFromRelation; }
    public void setDifferenceFromRelation(String v) { differenceFromRelation = v; }
    public ProRataType getProRataType() { return proRataType; }
    public void setProRataType(ProRataType v) { proRataType = v; }
    public Short getMaxTwins() { return maxTwins; }
    public void setMaxTwins(Short v) { maxTwins = v == null ? 0 : v; }
}
