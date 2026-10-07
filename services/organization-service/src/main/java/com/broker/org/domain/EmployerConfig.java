package com.broker.org.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "employer_configs")
public class EmployerConfig {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "broker_id", nullable = false, updatable = false) private Long brokerId;
    @Column(name = "employer_id", nullable = false, updatable = false) private Long employerId;
    @Column(name = "config_key", nullable = false, updatable = false) private String configKey;
    @Column(name = "config_value", nullable = false) private String configValue;
    @Column(name = "expires_at") private Instant expiresAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected EmployerConfig() {}

    public EmployerConfig(Long brokerId, Long employerId, String configKey) {
        this.brokerId = brokerId;
        this.employerId = employerId;
        this.configKey = configKey;
    }

    @PrePersist void onCreate() { createdAt = updatedAt = Instant.now(); }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public String getConfigKey() { return configKey; }
    public String getConfigValue() { return configValue; }
    public void setConfigValue(String v) { configValue = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { expiresAt = v; }
}
