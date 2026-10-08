package com.broker.integration;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import org.assertj.core.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;

class ReconcileFlowTest extends IntegrationTestBase {
    private static final String URL = "/api/v1/broker/integration/policies/";

    private void policy(long id, String number, String... members) {
        jdbc.sql("INSERT INTO integration.policy_routes(policy_id, broker_id, employer_id, tpa_id) VALUES (:p, 1, 10, 3)").param("p", id).update();
        world.policyRoster.put(id, List.of(members));
        if (number != null) world.policyNumbers.put(id, number);
    }

    @Test
    void activeLivesAreMatchedOntoTheRosterAndMissesAreReported() throws Exception {
        config(BROKER_A, 3, "NONE", 100);
        policy(77, "GMC/2030/1", FakeWorld.member(1, "E100", "SELF", "Asha", "Rao"), FakeWorld.member(2, "E100", "WIFE", "Meera", "Rao"),
                FakeWorld.member(3, "E100", "SON", "Arjun", "Rao"), FakeWorld.member(4, "E200", "SELF", "Nobody", "Home"),
                FakeWorld.member(5, "E100", "DAUGHTER", "Diya", "Rao"));
        world.activeLivesJson = """
            {"members":[{"employee_code":"e100","relation":"Self","member_id":"T1","member_name":"Asha Rao",
              "dependents":[{"relation":"Wife","member_id":"T2","member_name":"Meera Rao"},{"relation":"Son","member_id":"T3","member_name":"Arjun Rao"},
                            {"relation":"Daughter","member_id":"T9","member_name":"Ghost"}]},
             {"employee_code":"E999","relation":"Self","member_id":"T8","member_name":"Stranger"}]}""";
        String res = mvc.perform(post(URL + "77/reconcile").header("Authorization", admin(BROKER_A))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("OK"))).andExpect(jsonPath("$.rosterSize", is(5))).andExpect(jsonPath("$.tpaRecords", is(2)))
                .andExpect(jsonPath("$.matched", is(3))).andExpect(jsonPath("$.misses", is(2))).andExpect(jsonPath("$.rosterUnmatched", is(2)))
                .andReturn().getResponse().getContentAsString();
        Assertions.assertThat(world.tpaIdsRecorded).containsEntry(1L, "T1").containsEntry(2L, "T2").containsEntry(3L, "T3").hasSize(3);
        Assertions.assertThat(world.tpaCalls.getFirst().path()).isEqualTo("/active-lives?policy_number=GMC%2F2030%2F1");
        String id = com.jayway.jsonpath.JsonPath.read(res, "$.id");
        mvc.perform(get("/api/v1/broker/integration/reconcile-runs/" + id).header("Authorization", admin(BROKER_A)))
                .andExpect(jsonPath("$.misses[*].reason", hasItems(containsString("no such member"), containsString("matches the name"))));
        mvc.perform(get("/api/v1/broker/integration/reconcile-runs?policyId=77").header("Authorization", admin(BROKER_A))).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void relationLabelsAndLeadingZerosAreConfiguredPerTpa() throws Exception {
        config(BROKER_A, 3, "NONE", 100);
        long cfg = jdbc.sql("SELECT id FROM integration.tpa_configs").query(Long.class).single();
        policy(78, "P-78", FakeWorld.member(1, "007", "SELF", "Zed", "Z"), FakeWorld.member(2, "007", "FATHER", "Old", "Z"));
        world.activeLivesJson = "{\"members\":[{\"employee_code\":\"7\",\"relation\":\"EMP\",\"member_id\":\"T1\",\"member_name\":\"Zed Z\"},"
                + "{\"employee_code\":\"7\",\"relation\":\"Dad\",\"member_id\":\"T2\",\"member_name\":\"Old Z\"}]}";
        mvc.perform(post(URL + "78/reconcile").header("Authorization", admin(BROKER_A))).andExpect(jsonPath("$.matched", is(0))).andExpect(jsonPath("$.misses", is(2)));
        mvc.perform(json(put("/api/v1/broker/integration/tpa-configs/" + cfg + "/reconciliation"), admin(BROKER_A),
                "{\"stripLeadingZeros\":true,\"relationLabels\":{\"Dad\":\"FATHER\"}}")).andExpect(status().isOk());
        mvc.perform(post(URL + "78/reconcile").header("Authorization", admin(BROKER_A))).andExpect(jsonPath("$.matched", is(2)));
        mvc.perform(json(put("/api/v1/broker/integration/tpa-configs/" + cfg + "/reconciliation"), admin(BROKER_A),
                "{\"stripLeadingZeros\":false,\"relationLabels\":{\"Dad\":\"not a code\"}}")).andExpect(status().isBadRequest());
    }

    @Test
    void guardsTenancyMissingPrerequisitesAndTpaFailures() throws Exception {
        config(BROKER_A, 3, "NONE", 100);
        policy(79, null, FakeWorld.member(1, "E1", "SELF", "A", "B"));
        mvc.perform(post(URL + "79/reconcile").header("Authorization", admin(BROKER_A))).andExpect(status().isConflict());          // no policy number yet
        world.policyNumbers.put(79L, "P-79");
        mvc.perform(post(URL + "79/reconcile").header("Authorization", admin(BROKER_B))).andExpect(status().isNotFound());           // another broker's policy
        mvc.perform(post(URL + "79/reconcile").header("Authorization", bearer("BROKER_NON_ADMIN", BROKER_A, null))).andExpect(status().isForbidden());
        mvc.perform(post(URL + "12345/reconcile").header("Authorization", admin(BROKER_A))).andExpect(status().isNotFound());
        world.tpaStatus = 503;
        mvc.perform(post(URL + "79/reconcile").header("Authorization", admin(BROKER_A))).andExpect(status().isBadGateway());
        mvc.perform(get("/api/v1/broker/integration/reconcile-runs?policyId=79").header("Authorization", admin(BROKER_A)))
                .andExpect(jsonPath("$[0].status", is("FAILED"))).andExpect(jsonPath("$[0].error", containsString("503")));
        world.tpaStatus = 200;
        jdbc.sql("UPDATE integration.policy_routes SET tpa_id = NULL WHERE policy_id = 79").update();
        mvc.perform(post(URL + "79/reconcile").header("Authorization", admin(BROKER_A))).andExpect(status().isConflict());          // no TPA
    }

    @Autowired private com.broker.integration.sync.ReconcileScheduler scheduler;

    @Test
    void theSchedulerReconcilesTheStalestPoliciesFirstAndNeverTwiceInsideTheInterval() throws Exception {
        config(BROKER_A, 3, "NONE", 100);
        policy(81, "P-81", FakeWorld.member(1, "E1", "SELF", "A", "B"));
        policy(82, "P-82", FakeWorld.member(2, "E2", "SELF", "C", "D"));
        jdbc.sql("INSERT INTO integration.policy_routes(policy_id, broker_id, employer_id, tpa_id) VALUES (83, 1, 10, NULL)").update();           // no TPA: never scheduled
        world.activeLivesJson = "{\"members\":[{\"employee_code\":\"E1\",\"relation\":\"Self\",\"member_id\":\"T1\",\"member_name\":\"A B\"}]}";
        Assertions.assertThat(scheduler.runOnce()).isEqualTo(2);
        Assertions.assertThat(world.tpaIdsRecorded).containsEntry(1L, "T1");
        Assertions.assertThat(scheduler.runOnce()).isZero();                                                                                    // both ran moments ago
        jdbc.sql("UPDATE integration.reconcile_runs SET created_at = now() - interval '1 day' WHERE policy_id = 82").update();
        Assertions.assertThat(scheduler.runOnce()).isEqualTo(1);
        world.tpaStatus = 503;
        jdbc.sql("UPDATE integration.reconcile_runs SET created_at = now() - interval '2 days'").update();
        Assertions.assertThat(scheduler.runOnce()).isEqualTo(2);                                                                                // failures are survived and recorded
        Assertions.assertThat(jdbc.sql("SELECT count(*) FROM integration.reconcile_runs WHERE status = 'FAILED'").query(Long.class).single()).isEqualTo(2L);
    }
}
