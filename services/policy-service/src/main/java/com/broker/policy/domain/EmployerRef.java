package com.broker.policy.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Projection of organization-service employers, maintained from employer.events. */
@Entity
@Table(name = "employer_refs")
public class EmployerRef {
    @Id @Column(name = "employer_id") private Long employerId;
    @Column(name = "broker_id", nullable = false) private Long brokerId;
    @Column(name = "is_active", nullable = false) private boolean active = true;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    protected EmployerRef() {}

    public Long getEmployerId() { return employerId; }
    public Long getBrokerId() { return brokerId; }
    public boolean isActive() { return active; }
}
