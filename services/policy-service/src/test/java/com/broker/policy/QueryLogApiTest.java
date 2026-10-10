package com.broker.policy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "ops.querylog.enabled=true")
class QueryLogApiTest extends IntegrationTestBase {
    @Test
    void theHandlerAndItsSqlAreRecordedAndOnlyASystemTokenCanReadThem() throws Exception {
        mvc.perform(get("/api/v1/broker/policies").header("Authorization", admin(BROKER_A))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/ops/queries").header("Authorization", admin(BROKER_A))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ops/queries").header("Authorization", bearer("SYSTEM", null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("policy-service"))
                .andExpect(jsonPath("$.requests[0].handler").value(org.hamcrest.Matchers.containsString("#")))
                .andExpect(jsonPath("$.requests[0].uri").value("/api/v1/broker/policies"))
                .andExpect(jsonPath("$.requests[0].queryCount").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.requests[0].queries[0].sql").value(org.hamcrest.Matchers.containsStringIgnoringCase("select")));
    }
}
