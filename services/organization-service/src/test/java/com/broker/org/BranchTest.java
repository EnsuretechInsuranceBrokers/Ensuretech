package com.broker.org;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;

class BranchTest extends IntegrationTestBase {

    @Test
    void createWithoutOptionalFlagAndListIsTenantScoped() throws Exception {
        // headOffice omitted on purpose: a missing optional field must not be a 400
        mvc.perform(json(post("/api/v1/broker/branches"), bearer("BROKER_ADMIN", brokerA, null),
                "{\"branchCode\":\"MUM\",\"city\":\"Mumbai\",\"state\":\"MH\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.headOffice", is(false)));
        mvc.perform(json(post("/api/v1/broker/branches"), bearer("BROKER_ADMIN", brokerA, null),
                "{\"branchCode\":\"HQ\",\"headOffice\":true}")).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/broker/branches").header("Authorization", bearer("BROKER_NON_ADMIN", brokerA, null)))
                .andExpect(jsonPath("$", hasSize(2))).andExpect(jsonPath("$[0].branchCode", is("HQ")));
        mvc.perform(get("/api/v1/broker/branches").header("Authorization", bearer("BROKER_ADMIN", brokerB, null)))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void duplicateCodeIs409OnlyWithinABroker() throws Exception {
        String body = "{\"branchCode\":\"MUM\"}";
        mvc.perform(json(post("/api/v1/broker/branches"), bearer("BROKER_ADMIN", brokerA, null), body)).andExpect(status().isCreated());
        mvc.perform(json(post("/api/v1/broker/branches"), bearer("BROKER_ADMIN", brokerA, null), body)).andExpect(status().isConflict());
        mvc.perform(json(post("/api/v1/broker/branches"), bearer("BROKER_ADMIN", brokerB, null), body)).andExpect(status().isCreated());
    }

    @Test
    void onlyBrokerAdminsCanWrite() throws Exception {
        mvc.perform(json(post("/api/v1/broker/branches"), bearer("BROKER_NON_ADMIN", brokerA, null), "{\"branchCode\":\"X\"}"))
                .andExpect(status().isForbidden());
    }
}
