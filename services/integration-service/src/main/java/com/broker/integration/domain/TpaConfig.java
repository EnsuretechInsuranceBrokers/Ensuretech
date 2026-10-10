package com.broker.integration.domain;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "tpa_configs")
public class TpaConfig {
    public enum Adapter { GENERIC_REST }
    public enum AuthType { NONE, BASIC, BEARER }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "broker_id", nullable = false, updatable = false) private Long brokerId;
    @Column(name = "tpa_id", nullable = false, updatable = false) private Short tpaId;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM) @Column(nullable = false) private Adapter adapter;
    @Column(name = "base_url", nullable = false) private String baseUrl;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM) @Column(name = "auth_type", nullable = false) private AuthType authType = AuthType.NONE;
    @Column(name = "secret_ref") private String secretRef;
    @Column(name = "batch_size", nullable = false) private int batchSize = 100;
    @Column(name = "timeout_ms", nullable = false) private int timeoutMs = 5000;
    @Column(nullable = false) private boolean enabled = true;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    protected TpaConfig() {}

    public TpaConfig(Long brokerId, Short tpaId) { this.brokerId = brokerId; this.tpaId = tpaId; }

    public Long getId() { return id; }
    public Long getBrokerId() { return brokerId; }
    public Short getTpaId() { return tpaId; }
    public Adapter getAdapter() { return adapter; }
    public String getBaseUrl() { return baseUrl; }
    public AuthType getAuthType() { return authType; }
    public String getSecretRef() { return secretRef; }
    public int getBatchSize() { return batchSize; }
    public int getTimeoutMs() { return timeoutMs; }
    public boolean isEnabled() { return enabled; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void apply(Adapter adapter, String baseUrl, AuthType authType, String secretRef, int batchSize, int timeoutMs, boolean enabled) {
        this.adapter = adapter; this.baseUrl = baseUrl; this.authType = authType; this.secretRef = secretRef;
        this.batchSize = batchSize; this.timeoutMs = timeoutMs; this.enabled = enabled; this.updatedAt = Instant.now();
    }
}
