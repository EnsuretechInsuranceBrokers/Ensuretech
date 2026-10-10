package com.broker.integration.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "policy_routes")
public class PolicyRoute {
    @Id @Column(name = "policy_id") private Long policyId;
    @Column(name = "broker_id", nullable = false) private Long brokerId;
    @Column(name = "employer_id", nullable = false) private Long employerId;
    @Column(name = "tpa_id") private Short tpaId;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    protected PolicyRoute() {}

    public PolicyRoute(Long policyId, Long brokerId, Long employerId, Short tpaId) {
        this.policyId = policyId; this.brokerId = brokerId; this.employerId = employerId; this.tpaId = tpaId;
    }

    public Long getPolicyId() { return policyId; }
    public Long getBrokerId() { return brokerId; }
    public Long getEmployerId() { return employerId; }
    public Short getTpaId() { return tpaId; }
    public void update(Long brokerId, Long employerId, Short tpaId) {
        this.brokerId = brokerId; this.employerId = employerId; this.tpaId = tpaId; this.updatedAt = Instant.now();
    }
}
