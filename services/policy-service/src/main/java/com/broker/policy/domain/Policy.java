package com.broker.policy.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "policies")
public class Policy {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "broker_id", nullable = false, updatable = false) private Long brokerId;
    @Column(name = "employer_id", nullable = false, updatable = false) private Long employerId;
    @Column(name = "policy_number", nullable = false, updatable = false) private String policyNumber;
    @Column(name = "insurer_id", nullable = false) private Short insurerId;
    @Column(name = "tpa_id") private Short tpaId;
    @Column(name = "policy_type", nullable = false) private String policyType;
    @Column(name = "policy_sub_type") private String policySubType;
    @Column(name = "premium_type_id", nullable = false, updatable = false) private Short premiumTypeId;
    @Column(name = "suminsured_subtype_id", nullable = false) private Short suminsuredSubtypeId;
    @Column(name = "rater_type_id", nullable = false) private Short raterTypeId;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false) private PolicyStatus status = PolicyStatus.NOT_APPROVED;
    @Column(name = "start_date", nullable = false) private LocalDate startDate;
    @Column(name = "end_date", nullable = false) private LocalDate endDate;
    @Column(name = "renewal_date") private LocalDate renewalDate;
    @Column(name = "max_members") private Integer maxMembers;
    @Column(name = "is_inception", nullable = false) private boolean inception;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "pro_rata_type", nullable = false) private ProRataType proRataType = ProRataType.NONE;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "construct_config", nullable = false) private Map<String, Object> constructConfig = new HashMap<>();
    @Column(name = "midterm_basis", nullable = false) private String midtermBasis = "DATE_OF_JOINING";
    @Column(name = "premium_rounding", nullable = false) private String premiumRounding = "NONE";
    @Column(name = "rounding_unit", nullable = false) private int roundingUnit = 1;
    @Column(name = "is_test_data", nullable = false) private boolean testData;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected Policy() {}

    public Policy(Long brokerId, Long employerId, String policyNumber, Short premiumTypeId) {
        this.brokerId = brokerId;
        this.employerId = employerId;
        this.policyNumber = policyNumber;
        this.premiumTypeId = premiumTypeId;
    }

    @PrePersist void onCreate() { createdAt = updatedAt = Instant.now(); }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public Long getBrokerId() { return brokerId; }
    public Long getEmployerId() { return employerId; }
    public String getPolicyNumber() { return policyNumber; }
    public Short getInsurerId() { return insurerId; }
    public void setInsurerId(Short v) { insurerId = v; }
    public Short getTpaId() { return tpaId; }
    public void setTpaId(Short v) { tpaId = v; }
    public String getPolicyType() { return policyType; }
    public void setPolicyType(String v) { policyType = v; }
    public String getPolicySubType() { return policySubType; }
    public void setPolicySubType(String v) { policySubType = v; }
    public Short getPremiumTypeId() { return premiumTypeId; }
    public Short getSuminsuredSubtypeId() { return suminsuredSubtypeId; }
    public void setSuminsuredSubtypeId(Short v) { suminsuredSubtypeId = v; }
    public Short getRaterTypeId() { return raterTypeId; }
    public void setRaterTypeId(Short v) { raterTypeId = v; }
    public PolicyStatus getStatus() { return status; }
    public void setStatus(PolicyStatus v) { status = v; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate v) { startDate = v; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate v) { endDate = v; }
    public LocalDate getRenewalDate() { return renewalDate; }
    public void setRenewalDate(LocalDate v) { renewalDate = v; }
    public Integer getMaxMembers() { return maxMembers; }
    public void setMaxMembers(Integer v) { maxMembers = v; }
    public boolean isInception() { return inception; }
    public void setInception(boolean v) { inception = v; }
    public String getMidtermBasis() { return midtermBasis; }
    public void setMidtermBasis(String v) { midtermBasis = v; }
    public String getPremiumRounding() { return premiumRounding; }
    public int getRoundingUnit() { return roundingUnit; }
    public void setRounding(String type, int unit) { premiumRounding = type; roundingUnit = unit; }
    public ProRataType getProRataType() { return proRataType; }
    public void setProRataType(ProRataType v) { proRataType = v; }
    public Map<String, Object> getConstructConfig() { return constructConfig; }
    public void setConstructConfig(Map<String, Object> v) { constructConfig = v; }
}
