package com.broker.policy.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "suminsured_subtypes")
public class SumInsuredSubtype {
    @Id private Short id;
    private String code;
    private String name;
    @Column(name = "allows_grade") private boolean allowsGrade;
    @Column(name = "allows_age_band") private boolean allowsAgeBand;

    protected SumInsuredSubtype() {}

    public Short getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public boolean isAllowsGrade() { return allowsGrade; }
    public boolean isAllowsAgeBand() { return allowsAgeBand; }
}
