package com.broker.integration.security;

/** Tenant scope taken from the verified JWT. Never derived from request parameters. */
public record TenantContext(String userId, String role, Long brokerId, Long employerId, Long tpaId) {

    public boolean isBrokerScoped() {
        return role != null && role.startsWith("BROKER_");
    }

    public boolean isTpaScoped() {
        return "TPA_USER".equals(role);
    }

    public boolean isEmployerScoped() {
        return role != null && role.startsWith("EMPLOYER_");
    }
}
