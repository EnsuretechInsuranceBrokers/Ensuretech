package com.broker.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class JobControlTest extends IntegrationTestBase {
    private static final String PW = "correct-horse-battery-staple", URL = "/api/v1/ops/jobs/";

    @BeforeEach
    void password() {
        jdbc.sql("INSERT INTO integration.job_control_credentials (id, password_hash, set_by) VALUES (1, :h, 'test')").param("h", new BCryptPasswordEncoder(4).encode(PW)).update();
    }

    private UUID pendingJob() {
        config(BROKER_A, 3, "NONE", 10);
        policyApproved(1, 3);
        UUID job = UUID.randomUUID();
        world.roster(job, 25, 100);
        return applied(UUID.randomUUID(), job, 1, 25);
    }

    private MockHttpServletRequestBuilder killReq(UUID id, String password) {
        var b = post(URL + id + "/kill").header("Authorization", system()).contentType("application/json").content("{\"reason\":\"rogue: stuck on a TPA that never answers\"}");
        return password == null ? b : b.header("X-Job-Killer-Password", password);
    }

    private String audit(String col, String where) { return jdbc.sql("SELECT " + col + " FROM integration.job_control_audit WHERE " + where + " ORDER BY id DESC LIMIT 1").query(String.class).optional().orElse(null); }

    @Test
    void onlyServiceAccountsReachTheControlsAndReadingNeedsNoPassword() throws Exception {
        UUID id = pendingJob();
        mvc.perform(get(URL.substring(0, URL.length() - 1)).header("Authorization", admin(BROKER_A))).andExpect(status().isForbidden());                       // a broker admin cannot even list
        mvc.perform(get(URL.substring(0, URL.length() - 1))).andExpect(status().isUnauthorized());
        mvc.perform(get(URL.substring(0, URL.length() - 1)).header("Authorization", system())).andExpect(status().isOk()).andExpect(jsonPath("$[0].id", is(id.toString()))).andExpect(jsonPath("$[0].status", is("PENDING")));
        mvc.perform(get(URL + id).header("Authorization", system())).andExpect(jsonPath("$.runningNow", is(false)));
        mvc.perform(get(URL.substring(0, URL.length() - 1) + "?status=nonsense").header("Authorization", system())).andExpect(status().isBadRequest());
    }

    @Test
    void killingNeedsThePasswordAndAWrongOneIsAuditedNotAccepted() throws Exception {
        UUID id = pendingJob();
        mvc.perform(killReq(id, null)).andExpect(status().isForbidden());
        mvc.perform(killReq(id, "not-the-password")).andExpect(status().isForbidden());
        assertThat(audit("outcome", "action='KILL'")).isEqualTo("WRONG_PASSWORD");
        mvc.perform(get(URL + id).header("Authorization", system())).andExpect(jsonPath("$.status", is("PENDING")));             // untouched
        mvc.perform(post(URL + id + "/kill").header("Authorization", system()).header("X-Job-Killer-Password", PW).contentType("application/json").content("{}"))
                .andExpect(status().isUnprocessableEntity());                                                                      // a reason is required
        mvc.perform(killReq(id, PW)).andExpect(status().isOk()).andExpect(jsonPath("$.status", is("CANCELLED"))).andExpect(jsonPath("$.lastError", containsString("rogue")));
        assertThat(audit("outcome", "action='KILL'")).isEqualTo("OK");
        assertThat(audit("actor", "action='KILL'")).isNotBlank();
        assertThat(worker.drain()).as("a cancelled job is never picked up").isZero();
        mvc.perform(killReq(id, PW)).andExpect(status().isConflict());                                                             // already stopped
        mvc.perform(killReq(UUID.randomUUID(), PW)).andExpect(status().isNotFound());
    }

    @Test
    void fiveWrongPasswordsLockTheControlsEvenAgainstTheRightOne() throws Exception {
        UUID id = pendingJob();
        for (int i = 0; i < 5; i++) mvc.perform(killReq(id, "guess-" + i)).andExpect(status().isForbidden());
        mvc.perform(killReq(id, PW)).andExpect(status().isLocked());
        mvc.perform(post(URL + id + "/requeue").header("Authorization", system()).header("X-Job-Killer-Password", PW)).andExpect(status().isLocked());
        assertThat(audit("outcome", "action='KILL'")).isEqualTo("LOCKED");
        jdbc.sql("UPDATE integration.job_control_credentials SET locked_until = now() - interval '1 second'").update();           // the lock expires
        mvc.perform(killReq(id, PW)).andExpect(status().isOk());
    }

    @Test
    void aRunningJobIsStoppedAndNotRetriedOrDeadLettered() throws Exception {
        UUID sync = pendingJob();
        world.tpaDelayMs = 20_000;                                                                                                 // the TPA never answers in time
        var t = new Thread(() -> worker.drain());
        t.start();
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline && !worker.isRunning(sync)) Thread.sleep(25);
        assertThat(worker.isRunning(sync)).isTrue();
        mvc.perform(get(URL + sync).header("Authorization", system())).andExpect(jsonPath("$.runningNow", is(true))).andExpect(jsonPath("$.status", is("IN_PROGRESS")));
        mvc.perform(killReq(sync, PW)).andExpect(status().isOk()).andExpect(jsonPath("$.interrupted", is(true))).andExpect(jsonPath("$.status", is("CANCELLED")));
        t.join(5000);
        assertThat(t.isAlive()).as("the worker thread was freed, not left waiting 20 s").isFalse();
        assertThat(stateOf(sync)).isEqualTo("CANCELLED");
        assertThat(intCol(sync, "attempts")).isZero();                                                                             // not counted as a failure, not retried
        assertThat(outbox("TpaSyncFailed")).isZero();
        world.tpaDelayMs = 0;
        assertThat(worker.drain()).isZero();
    }

    @Test
    void aDeadOrCancelledJobCanBeRequeuedAndThenRunsToTheEnd() throws Exception {
        UUID sync = pendingJob();
        mvc.perform(post(URL + sync + "/requeue").header("Authorization", system()).header("X-Job-Killer-Password", PW)).andExpect(status().isConflict());   // pending: nothing to requeue
        mvc.perform(killReq(sync, PW)).andExpect(status().isOk());
        mvc.perform(post(URL + sync + "/requeue").header("Authorization", system()).header("X-Job-Killer-Password", "wrong")).andExpect(status().isForbidden());
        mvc.perform(post(URL + sync + "/requeue").header("Authorization", system()).header("X-Job-Killer-Password", PW)).andExpect(status().isOk()).andExpect(jsonPath("$.status", is("PENDING")));
        assertThat(worker.drain()).isEqualTo(1);
        assertThat(stateOf(sync)).isEqualTo("SUCCEEDED");
        assertThat(intCol(sync, "synced_count")).isEqualTo(25);
    }

    @Test
    void thePasswordCanBeChangedOnlyByTheCurrentOneAndIsStoredHashed() throws Exception {
        UUID id = pendingJob();
        String change = "{\"newPassword\":\"a-brand-new-long-passphrase\"}";
        mvc.perform(put(URL + "password").header("Authorization", system()).header("X-Job-Killer-Password", "wrong").contentType("application/json").content(change)).andExpect(status().isForbidden());
        mvc.perform(put(URL + "password").header("Authorization", system()).header("X-Job-Killer-Password", PW).contentType("application/json").content("{\"newPassword\":\"short\"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(put(URL + "password").header("Authorization", system()).header("X-Job-Killer-Password", PW).contentType("application/json").content(change)).andExpect(status().isOk());
        mvc.perform(killReq(id, PW)).andExpect(status().isForbidden());                                                            // the old one no longer works
        mvc.perform(killReq(id, "a-brand-new-long-passphrase")).andExpect(status().isOk());
        String stored = jdbc.sql("SELECT password_hash FROM integration.job_control_credentials").query(String.class).single();
        assertThat(stored).startsWith("$2").doesNotContain("passphrase").doesNotContain(PW);
        assertThat(jdbc.sql("SELECT count(*) FROM integration.job_control_audit WHERE detail LIKE '%passphrase%' OR detail LIKE '%" + PW + "%'").query(Long.class).single()).isZero();   // never in the audit trail
    }
}
