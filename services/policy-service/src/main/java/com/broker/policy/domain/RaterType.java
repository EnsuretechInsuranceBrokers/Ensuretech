package com.broker.policy.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "rater_types")
public class RaterType {
    @Id private Short id;
    private String code;
    private String name;
    @Column(name = "supports_opd") private boolean supportsOpd;
    @Column(name = "supports_ipd") private boolean supportsIpd;

    protected RaterType() {}

    public Short getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
}
