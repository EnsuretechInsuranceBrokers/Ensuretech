package com.broker.policy.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "relation_types")
public class RelationType {
    @Id private Short id;
    private String code;
    private String name;
    @Column(name = "relation_group") private String relationGroup;
    @Column(name = "is_dependent") private boolean dependent;
    @Column(name = "max_age") private Short maxAge;

    protected RelationType() {}

    public Short getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getRelationGroup() { return relationGroup; }
    public boolean isDependent() { return dependent; }
    public Short getMaxAge() { return maxAge; }
}
