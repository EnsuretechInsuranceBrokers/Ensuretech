package com.broker.org.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "employers")
public class Employer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "broker_id", nullable = false, updatable = false) private Long brokerId;
    @Column(name = "employer_code", nullable = false, updatable = false) private String employerCode;
    @Column(name = "legal_name", nullable = false) private String legalName;
    @Column(name = "display_name", nullable = false) private String displayName;
    private String gstin;
    private String pan;
    private String sector;
    @Column(name = "employee_count") private Integer employeeCount;
    @Column(name = "contact_email") private String contactEmail;
    @Column(name = "contact_phone") private String contactPhone;
    @Column(name = "hrms_type") private String hrmsType;
    @Column(name = "parent_employer_id") private Long parentEmployerId;
    @Column(name = "is_active", nullable = false) private boolean active = true;
    @Column(name = "is_hidden", nullable = false) private boolean hidden = false;
    @Column(name = "is_test_data", nullable = false) private boolean testData = false;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected Employer() {}

    public Employer(Long brokerId, String employerCode) {
        this.brokerId = brokerId;
        this.employerCode = employerCode;
    }

    @PrePersist void onCreate() { createdAt = updatedAt = Instant.now(); }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public Long getBrokerId() { return brokerId; }
    public String getEmployerCode() { return employerCode; }
    public String getLegalName() { return legalName; }
    public void setLegalName(String v) { legalName = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { displayName = v; }
    public String getGstin() { return gstin; }
    public void setGstin(String v) { gstin = v; }
    public String getPan() { return pan; }
    public void setPan(String v) { pan = v; }
    public String getSector() { return sector; }
    public void setSector(String v) { sector = v; }
    public Integer getEmployeeCount() { return employeeCount; }
    public void setEmployeeCount(Integer v) { employeeCount = v; }
    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String v) { contactEmail = v; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String v) { contactPhone = v; }
    public String getHrmsType() { return hrmsType; }
    public void setHrmsType(String v) { hrmsType = v; }
    public Long getParentEmployerId() { return parentEmployerId; }
    public void setParentEmployerId(Long v) { parentEmployerId = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { active = v; }
    public boolean isHidden() { return hidden; }
    public void setHidden(boolean v) { hidden = v; }
}
