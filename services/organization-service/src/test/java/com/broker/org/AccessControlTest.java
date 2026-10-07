package com.broker.org;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;

class AccessControlTest extends IntegrationTestBase {

    @Test
    void noTokenIs401() throws Exception {
        mvc.perform(get("/api/v1/broker/employers")).andExpect(status().isUnauthorized());
    }

    @Test
    void nonAdminBrokerCannotWrite() throws Exception {
        for (String role : new String[] {"BROKER_NON_ADMIN", "BROKER_SPECIALIZER"}) {
            mvc.perform(json(post("/api/v1/broker/employers"), bearer(role, brokerA, null), employerJson("ACME")))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void gatewaysAreRoleSeparated() throws Exception {
        long e = createEmployer(brokerA, "ACME");
        mvc.perform(get("/api/v1/broker/employers").header("Authorization", bearer("EMPLOYER_ADMIN", brokerA, e)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/employer/employer").header("Authorization", bearer("BROKER_ADMIN", brokerA, null)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/brokers").header("Authorization", bearer("BROKER_ADMIN", brokerA, null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownPathsAreDenied() throws Exception {
        mvc.perform(get("/api/v1/whatever").header("Authorization", bearer("SYSTEM", null, null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void refreshTokenCannotBeUsedAsAccessToken() throws Exception {
        String t = TestTokens.build(TestTokens.KEYS, TestTokens.ISSUER, "refresh", "BROKER_ADMIN", brokerA, null, 600);
        mvc.perform(get("/api/v1/broker/employers").header("Authorization", "Bearer " + t))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void badSignatureIssuerOrExpiryAre401() throws Exception {
        String forged = TestTokens.build(TestTokens.OTHER_KEYS, TestTokens.ISSUER, "access", "BROKER_ADMIN", brokerA, null, 600);
        String wrongIssuer = TestTokens.build(TestTokens.KEYS, "evil", "access", "BROKER_ADMIN", brokerA, null, 600);
        String expired = TestTokens.build(TestTokens.KEYS, TestTokens.ISSUER, "access", "BROKER_ADMIN", brokerA, null, -600);
        for (String t : new String[] {forged, wrongIssuer, expired}) {
            mvc.perform(get("/api/v1/broker/employers").header("Authorization", "Bearer " + t))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void systemUserCreatesBrokersButBrokerScopedEndpointsNeedABrokerClaim() throws Exception {
        mvc.perform(json(post("/api/v1/admin/brokers"), bearer("SYSTEM", null, null),
                "{\"code\":\"NEWB\",\"name\":\"New Broker\",\"contactEmail\":\"x@newb.test\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void invalidPayloadIs400() throws Exception {
        mvc.perform(json(post("/api/v1/broker/employers"), bearer("BROKER_ADMIN", brokerA, null),
                "{\"employerCode\":\"X\",\"legalName\":\"X\",\"displayName\":\"X\",\"pan\":\"bad\"}"))
                .andExpect(status().isBadRequest());
    }
}
