package com.broker.org.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "brokers")
public class Broker {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String code;
    @Column(nullable = false) private String name;
    @Column(name = "legal_name") private String legalName;
    private String gstin;
    private String pan;
    @Column(name = "irdai_licence_no") private String irdaiLicenceNo;
    @Column(name = "contact_email", nullable = false) private String contactEmail;
    @Column(name = "contact_phone") private String contactPhone;
    @Column(name = "is_active", nullable = false) private boolean active = true;
    @Column(name = "is_test_data", nullable = false) private boolean testData = false;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = Instant.now(); }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String v) { code = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getLegalName() { return legalName; }
    public void setLegalName(String v) { legalName = v; }
    public String getGstin() { return gstin; }
    public void setGstin(String v) { gstin = v; }
    public String getPan() { return pan; }
    public void setPan(String v) { pan = v; }
    public String getIrdaiLicenceNo() { return irdaiLicenceNo; }
    public void setIrdaiLicenceNo(String v) { irdaiLicenceNo = v; }
    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String v) { contactEmail = v; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String v) { contactPhone = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { active = v; }
}
