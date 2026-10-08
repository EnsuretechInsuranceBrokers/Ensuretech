package com.broker.policy;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;

class EnrollmentWindowApiTest extends IntegrationTestBase {
    private static String url(long id, String tail) { return "/api/v1/broker/policies/" + id + "/enrollment-window" + tail; }

    @Test
    void settingsOverridesEvaluationAndAudit() throws Exception {
        long id = createPolicy(BROKER_A, EMP_A, "W-1", "FLAT", "GMC");                       // policy period is set by the helper
        mvc.perform(get(url(id, "")).header("Authorization", admin(BROKER_A))).andExpect(jsonPath("$.enabled", is(false))).andExpect(jsonPath("$.midtermDays", is(30)));

        mvc.perform(json(put(url(id, "")), admin(BROKER_A), "{\"enabled\":true,\"startDate\":\"2030-03-01\",\"endDate\":\"2030-04-30\",\"midtermDays\":15}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.midtermDays", is(15)));

        mvc.perform(json(put(url(id, "/employees")), admin(BROKER_A),
                "{\"employees\":[{\"employeeRef\":\"E1\",\"startDate\":\"2030-05-01\",\"endDate\":\"2030-05-20\",\"closeDate\":\"2030-05-15\"},{\"employeeRef\":\"E2\",\"startDate\":\"2030-05-01\",\"endDate\":\"2030-05-20\"}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get(url(id, "/employees")).header("Authorization", admin(BROKER_A))).andExpect(jsonPath("$[0].employeeRef", is("E1"))).andExpect(jsonPath("$[0].enabled", is(true)));

        // E1 has a custom window to 05-20 but it closes on 05-15; E2 has none beyond the same dates
        mvc.perform(json(post(url(id, "/evaluate")), admin(BROKER_A), "{\"employeeRef\":\"E1\",\"dateOfJoining\":\"2030-04-02\",\"asOf\":\"2030-05-10\"}"))
                .andExpect(jsonPath("$.status", is("OPEN"))).andExpect(jsonPath("$.customWindowSet", is(true)));
        mvc.perform(json(post(url(id, "/evaluate")), admin(BROKER_A), "{\"employeeRef\":\"E1\",\"dateOfJoining\":\"2030-04-02\",\"asOf\":\"2030-05-16\"}"))
                .andExpect(jsonPath("$.status", is("CLOSED")));

        // confirming freezes it; confirming twice is a conflict
        mvc.perform(json(post(url(id, "/confirm")), admin(BROKER_A), "{\"employeeRef\":\"E2\",\"dateOfJoining\":\"2030-04-02\",\"asOf\":\"2030-05-02\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("CONFIRMED"))).andExpect(jsonPath("$.confirmedAt", notNullValue()));
        mvc.perform(json(post(url(id, "/confirm")), admin(BROKER_A), "{\"employeeRef\":\"E2\",\"dateOfJoining\":\"2030-04-02\",\"asOf\":\"2030-05-02\"}")).andExpect(status().isConflict());

        // audit: policy level, then one multi-employee change, then the confirmation; newest first
        mvc.perform(get(url(id, "/audit")).header("Authorization", admin(BROKER_A)))
                .andExpect(jsonPath("$", hasSize(3))).andExpect(jsonPath("$[2].scope", is("POLICY_LEVEL")))
                .andExpect(jsonPath("$[1].scope", is("MULTIPLE_EMPLOYEES"))).andExpect(jsonPath("$[1].employees", contains("E1", "E2")))
                .andExpect(jsonPath("$[0].scope", is("SINGLE_EMPLOYEE")));

        mvc.perform(delete(url(id, "/employees/E1")).header("Authorization", admin(BROKER_A))).andExpect(status().isOk());
        mvc.perform(delete(url(id, "/employees/E1")).header("Authorization", admin(BROKER_A))).andExpect(status().isNotFound());
    }

    @Test
    void badInputIsRefusedWithReasons() throws Exception {
        long id = createPolicy(BROKER_A, EMP_A, "W-2", "FLAT", "GMC");
        mvc.perform(json(put(url(id, "")), admin(BROKER_A), "{\"enabled\":true}")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.issues[0].code", is("WINDOW_DATES")));
        mvc.perform(json(put(url(id, "")), admin(BROKER_A), "{\"enabled\":true,\"startDate\":\"2030-05-01\",\"endDate\":\"2030-04-01\"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(json(put(url(id, "/employees")), admin(BROKER_A), "{\"employees\":[{\"employeeRef\":\"E1\",\"startDate\":\"2030-05-01\",\"endDate\":\"2030-05-10\",\"closeDate\":\"2030-05-11\"}]}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.issues[0].code", is("CLOSE_DATE")));
        mvc.perform(json(put(url(id, "/employees")), admin(BROKER_A), "{\"employees\":[]}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(json(post(url(id, "/evaluate")), admin(BROKER_A), "{}")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void anotherBrokerCannotSeeOrChangeAWindow() throws Exception {
        long id = createPolicy(BROKER_A, EMP_A, "W-3", "FLAT", "GMC");
        String b = admin(BROKER_B);
        mvc.perform(get(url(id, "")).header("Authorization", b)).andExpect(status().isNotFound());
        mvc.perform(json(put(url(id, "")), b, "{\"enabled\":false}")).andExpect(status().isNotFound());
        mvc.perform(get(url(id, "/audit")).header("Authorization", b)).andExpect(status().isNotFound());
        mvc.perform(json(post(url(id, "/evaluate")), b, "{\"employeeRef\":\"E1\"}")).andExpect(status().isNotFound());
    }

    @Test
    void nonAdminsMayReadButNotChange() throws Exception {
        long id = createPolicy(BROKER_A, EMP_A, "W-4", "FLAT", "GMC");
        String staff = bearer("BROKER_NON_ADMIN", BROKER_A, null);
        mvc.perform(get(url(id, "")).header("Authorization", staff)).andExpect(status().isOk());
        mvc.perform(json(put(url(id, "")), staff, "{\"enabled\":false}")).andExpect(status().isForbidden());
    }
}
