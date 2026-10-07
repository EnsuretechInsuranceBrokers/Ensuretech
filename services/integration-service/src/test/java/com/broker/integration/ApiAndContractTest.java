package com.broker.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.broker.integration.tpa.UrlGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class ApiAndContractTest extends IntegrationTestBase {
    static final String BASE = "/api/v1/broker/integration";
    static final String CFG = """
        {"tpaId":3,"adapter":"GENERIC_REST","baseUrl":"%s","authType":"BASIC","secretRef":"TESTTPA","batchSize":50,"timeoutMs":3000}""";

    @Autowired UrlGuard guard;

    @Test
    void configsAreBrokerScopedRoleGatedAndNeverExposeSecrets() throws Exception {
        mvc.perform(json(post(BASE + "/tpa-configs"), admin(BROKER_A), CFG.formatted(world.tpaUrl))).andExpect(status().isCreated())
           .andExpect(jsonPath("$.secretRef").value("TESTTPA")).andExpect(jsonPath("$.password").doesNotExist());
        mvc.perform(json(post(BASE + "/tpa-configs"), admin(BROKER_A), CFG.formatted(world.tpaUrl))).andExpect(status().isConflict());
        mvc.perform(json(post(BASE + "/tpa-configs"), bearer("BROKER_NON_ADMIN", BROKER_A, null), CFG.formatted(world.tpaUrl))).andExpect(status().isForbidden());
        mvc.perform(json(post(BASE + "/tpa-configs"), bearer("EMPLOYER_ADMIN", BROKER_A, EMP_A), CFG.formatted(world.tpaUrl))).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/tpa-configs")).andExpect(status().isUnauthorized());

        mvc.perform(get(BASE + "/tpa-configs").header("Authorization", bearer("BROKER_NON_ADMIN", BROKER_A, null))).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get(BASE + "/tpa-configs").header("Authorization", admin(BROKER_B))).andExpect(jsonPath("$.length()").value(0));
        long id = jdbc.sql("SELECT id FROM integration.tpa_configs").query(Long.class).single();
        mvc.perform(json(put(BASE + "/tpa-configs/" + id), admin(BROKER_B), CFG.formatted(world.tpaUrl))).andExpect(status().isNotFound());
        mvc.perform(json(put(BASE + "/tpa-configs/" + id), admin(BROKER_A), CFG.formatted(world.tpaUrl).replace("\"batchSize\":50", "\"batchSize\":5000")))
           .andExpect(status().isBadRequest());
    }

    @Test
    void configValidationRejectsUnsafeAndIncompleteInput() throws Exception {
        mvc.perform(json(post(BASE + "/tpa-configs"), admin(BROKER_A), CFG.formatted(world.tpaUrl).replace("\"secretRef\":\"TESTTPA\"", "\"secretRef\":\"lower case!\"")))
           .andExpect(status().isBadRequest());
        mvc.perform(json(post(BASE + "/tpa-configs"), admin(BROKER_A), CFG.formatted(world.tpaUrl).replace("\"secretRef\":\"TESTTPA\",", "")))
           .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("secretRef is required when authType is not NONE"));
    }

    @Test
    void ssrfGuardBlocksInternalTargetsWhenNotInDevMode() {
        UrlGuard strict = newGuard(false, false);
        for (String bad : new String[]{"http://example.com", "https://127.0.0.1/x", "https://localhost/x", "https://169.254.169.254/latest/meta-data",
                "https://10.1.2.3/", "https://192.168.0.5/", "https://[::1]/", "https://[fd00::1]/", "https://100.64.0.1/", "https://user:pw@example.com/",
                "ftp://example.com", "not a url", "https:///nohost"}) {
            assertThatThrownBy(() -> strict.check(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(strict.check("https://8.8.8.8/enroll").getHost()).isEqualTo("8.8.8.8");
        assertThat(guard.check(world.tpaUrl)).isNotNull();                                      // dev mode (this test context) allows it
    }

    private static UrlGuard newGuard(boolean priv, boolean http) {
        try {
            var c = UrlGuard.class.getDeclaredConstructor(boolean.class, boolean.class);
            c.setAccessible(true);
            return c.newInstance(priv, http);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void syncJobsAreTenantScopedAndRetryIsAdminOnlyAndDeadOnly() throws Exception {
        config(BROKER_A, 3, "NONE", 10);
        policyApproved(1, 3);
        UUID job = UUID.randomUUID();
        world.roster(job, 2, 100);
        UUID sync = applied(UUID.randomUUID(), job, 1, 2);

        mvc.perform(get(BASE + "/sync-jobs").header("Authorization", admin(BROKER_A))).andExpect(jsonPath("$.content.length()").value(1))
           .andExpect(jsonPath("$.content[0].status").value("PENDING"));
        mvc.perform(get(BASE + "/sync-jobs?status=DEAD").header("Authorization", admin(BROKER_A))).andExpect(jsonPath("$.content.length()").value(0));
        mvc.perform(get(BASE + "/sync-jobs").header("Authorization", admin(BROKER_B))).andExpect(jsonPath("$.content.length()").value(0));
        mvc.perform(get(BASE + "/sync-jobs/" + sync).header("Authorization", admin(BROKER_B))).andExpect(status().isNotFound());
        mvc.perform(post(BASE + "/sync-jobs/" + sync + "/retry").header("Authorization", admin(BROKER_B))).andExpect(status().isNotFound());
        mvc.perform(post(BASE + "/sync-jobs/" + sync + "/retry").header("Authorization", bearer("BROKER_NON_ADMIN", BROKER_A, null))).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/sync-jobs/" + sync + "/retry").header("Authorization", admin(BROKER_A))).andExpect(status().isConflict());   // not dead
        mvc.perform(get(BASE + "/sync-jobs").header("Authorization", bearer("EMPLOYER_ADMIN", BROKER_A, EMP_A))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/internal/anything").header("Authorization", admin(BROKER_A))).andExpect(status().isForbidden());           // fail closed
    }

    // ── event contracts: the same sample files the producers test against ───────────────────────────────────────
    private static String sample(String name) throws Exception { return Files.readString(Path.of("../../shared/contracts/events/" + name + ".sample.json")); }

    @Test
    void consumesTheSharedPolicyApprovedAndEndorsementAppliedSamples() throws Exception {
        events.handle(sample("policy-approved"));
        assertThat(jdbc.sql("SELECT tpa_id FROM integration.policy_routes WHERE policy_id = 1").query(Integer.class).single()).isEqualTo(3);
        events.handle(sample("endorsement-applied"));
        var row = jdbc.sql("SELECT tpa_id, members_expected, status::text s FROM integration.sync_jobs").query().singleRow();
        assertThat(row).containsEntry("tpa_id", 3).containsEntry("members_expected", 3).containsEntry("s", "PENDING");
    }

    @Test
    void producedEventsHaveExactlyTheSharedSampleFields() throws Exception {
        config(BROKER_A, 3, "NONE", 10);
        policyApproved(1, 3);
        UUID job = UUID.randomUUID(); world.roster(job, 3, 100);
        applied(UUID.randomUUID(), job, 1, 3);
        worker.drain();
        assertThat(keys(jdbc.sql("SELECT payload::text FROM outbox.outbox_events WHERE event_type='TpaSyncCompleted'").query(String.class).single()))
            .isEqualTo(keys(sample("tpa-sync-completed")));

        world.tpaStatus = 400;
        UUID job2 = UUID.randomUUID(); world.roster(job2, 3, 200);
        applied(UUID.randomUUID(), job2, 1, 3);
        worker.drain();
        assertThat(keys(jdbc.sql("SELECT payload::text FROM outbox.outbox_events WHERE event_type='TpaSyncFailed'").query(String.class).single()))
            .isEqualTo(keys(sample("tpa-sync-failed")));
    }

    @Test
    void malformedEventsAreSkippedNotFatal() {
        events.handle("not json");
        events.handle("{\"event_type\":\"EndorsementApplied\",\"event_id\":\"nope\"}");
        events.handle("{\"event_type\":\"Unrelated\"}");
        assertThat(jdbc.sql("SELECT count(*) FROM integration.sync_jobs").query(Long.class).single()).isZero();
    }

    private TreeSet<String> keys(String json) {
        TreeSet<String> k = new TreeSet<>();
        JsonNode n = mapper.readTree(json);
        n.propertyNames().forEach(k::add);
        return k;
    }
}
