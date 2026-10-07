package com.broker.org;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;

class TenantIsolationTest extends IntegrationTestBase {

    @Test
    void brokerCannotSeeOrTouchAnotherBrokersEmployer() throws Exception {
        long id = createEmployer(brokerA, "ACME");
        String b = bearer("BROKER_ADMIN", brokerB, null);

        mvc.perform(get("/api/v1/broker/employers/" + id).header("Authorization", b)).andExpect(status().isNotFound());
        mvc.perform(json(patch("/api/v1/broker/employers/" + id), b, "{\"displayName\":\"pwned\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/broker/employers/" + id).header("Authorization", b)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/broker/employers").header("Authorization", b))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(0)));
        mvc.perform(get("/api/v1/broker/employers/" + id + "/configs").header("Authorization", b))
                .andExpect(status().isNotFound());
    }

    @Test
    void sameEmployerCodeAllowedAcrossBrokersButNotWithinOne() throws Exception {
        createEmployer(brokerA, "ACME");
        mvc.perform(json(post("/api/v1/broker/employers"), bearer("BROKER_ADMIN", brokerB, null), employerJson("ACME")))
                .andExpect(status().isCreated());
        mvc.perform(json(post("/api/v1/broker/employers"), bearer("BROKER_ADMIN", brokerA, null), employerJson("ACME")))
                .andExpect(status().isConflict());
    }

    @Test
    void brokerIdInRequestBodyIsIgnoredInFavourOfToken() throws Exception {
        String body = """
            {"employerCode":"X1","legalName":"X","displayName":"X","brokerId":%d}""".formatted(brokerB);
        mvc.perform(json(post("/api/v1/broker/employers"), bearer("BROKER_ADMIN", brokerA, null), body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.brokerId", is((int) brokerA)));
    }

    @Test
    void employerUserSeesOnlyOwnEmployer() throws Exception {
        long mine = createEmployer(brokerA, "MINE");
        createEmployer(brokerA, "OTHER");
        mvc.perform(get("/api/v1/employer/employer").header("Authorization", bearer("EMPLOYER_ADMIN", brokerA, mine)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.employerCode", is("MINE")));
        // token claiming an employer that belongs to a different broker resolves to nothing
        mvc.perform(get("/api/v1/employer/employer").header("Authorization", bearer("EMPLOYER_ADMIN", brokerB, mine)))
                .andExpect(status().isNotFound());
    }

    @Test
    void hiddenEmployersVisibleToAdminsOnly() throws Exception {
        createEmployer(brokerA, "SHOWN");
        mvc.perform(json(post("/api/v1/broker/employers"), bearer("BROKER_ADMIN", brokerA, null),
                "{\"employerCode\":\"SECRET\",\"legalName\":\"S\",\"displayName\":\"S\",\"hidden\":true}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/broker/employers").header("Authorization", bearer("BROKER_ADMIN", brokerA, null)))
                .andExpect(jsonPath("$.content", hasSize(2)));
        mvc.perform(get("/api/v1/broker/employers").header("Authorization", bearer("BROKER_NON_ADMIN", brokerA, null)))
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    void employerConfigRoundTrip() throws Exception {
        long id = createEmployer(brokerA, "ACME");
        mvc.perform(json(put("/api/v1/broker/employers/" + id + "/configs/AUTO_FINALIZE"),
                bearer("BROKER_ADMIN", brokerA, null), "{\"value\":\"true\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/broker/employers/" + id + "/configs").header("Authorization", bearer("BROKER_NON_ADMIN", brokerA, null)))
                .andExpect(jsonPath("$[0].key", is("AUTO_FINALIZE"))).andExpect(jsonPath("$[0].value", is("true")));
    }
}
