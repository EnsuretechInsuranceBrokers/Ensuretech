package com.broker.policy.domain;

import jakarta.persistence.*;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Read-only reference data seeded from the legacy PremiumType constants. */
@Entity
@Table(name = "premium_types")
public class PremiumType {
    @Id private Short id;
    private String code;
    private String name;
    @Column(name = "rater_class") private String raterClass;
    @Column(name = "is_age_banded") private boolean ageBanded;
    @Column(name = "is_grade_based") private boolean gradeBased;
    @Column(name = "supports_topup") private boolean supportsTopup;
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "construct_raters") private List<String> constructRaters;
    @Column(name = "is_active") private boolean active;

    protected PremiumType() {}

    public Short getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getRaterClass() { return raterClass; }
    public boolean isAgeBanded() { return ageBanded; }
    public boolean isGradeBased() { return gradeBased; }
    public boolean isSupportsTopup() { return supportsTopup; }
    public List<String> getConstructRaters() { return constructRaters; }
    public boolean isActive() { return active; }
}
