package com.broker.policy.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "family_constructs")
public class FamilyConstruct {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "policy_id", nullable = false, updatable = false) private Long policyId;
    @Column(name = "broker_id", nullable = false, updatable = false) private Long brokerId;
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_relations", nullable = false) private List<Integer> allowedRelations = new ArrayList<>();
    @Column(name = "max_members", nullable = false) private Short maxMembers = 6;
    @Column(name = "max_children") private Short maxChildren;
    @Column(name = "max_parents") private Short maxParents;
    @Column(name = "include_parents", nullable = false) private boolean includeParents;
    @Column(name = "include_parents_in_law", nullable = false) private boolean includeParentsInLaw;
    @Column(name = "age_min") private Short ageMin;
    @Column(name = "age_max") private Short ageMax;
    @Column(name = "is_test_data", nullable = false) private boolean testData;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected FamilyConstruct() {}

    public FamilyConstruct(Long policyId, Long brokerId) {
        this.policyId = policyId;
        this.brokerId = brokerId;
    }

    @PrePersist void onCreate() { createdAt = updatedAt = Instant.now(); }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public Long getPolicyId() { return policyId; }
    public List<Integer> getAllowedRelations() { return allowedRelations; }
    public void setAllowedRelations(List<Integer> v) { allowedRelations = new ArrayList<>(v); }
    public Short getMaxMembers() { return maxMembers; }
    public void setMaxMembers(Short v) { maxMembers = v; }
    public Short getMaxChildren() { return maxChildren; }
    public void setMaxChildren(Short v) { maxChildren = v; }
    public Short getMaxParents() { return maxParents; }
    public void setMaxParents(Short v) { maxParents = v; }
    public boolean isIncludeParents() { return includeParents; }
    public void setIncludeParents(boolean v) { includeParents = v; }
    public boolean isIncludeParentsInLaw() { return includeParentsInLaw; }
    public void setIncludeParentsInLaw(boolean v) { includeParentsInLaw = v; }
    public Short getAgeMin() { return ageMin; }
    public void setAgeMin(Short v) { ageMin = v; }
    public Short getAgeMax() { return ageMax; }
    public void setAgeMax(Short v) { ageMax = v; }
}
