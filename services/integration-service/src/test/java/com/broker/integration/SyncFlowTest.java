package com.broker.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SyncFlowTest extends IntegrationTestBase {

    private UUID setup(int members, int batch, String auth) {
        config(BROKER_A, 3, auth, batch);
        policyApproved(1, 3);
        UUID job = UUID.randomUUID();
        world.roster(job, members, 100);
        return applied(UUID.randomUUID(), job, 1, members);
    }

    @Test
    void happyPathSyncsInBatchesAuthenticatesAndRecordsTpaIds() {
        UUID sync = setup(25, 10, "BASIC");
        assertThat(worker.drain()).isEqualTo(1);

        assertThat(stateOf(sync)).isEqualTo("SUCCEEDED");
        assertThat(intCol(sync, "synced_count")).isEqualTo(25);
        assertThat(intCol(sync, "cursor_id")).isEqualTo(124);
        assertThat(world.tpaCalls).hasSize(3);                                              // 10 + 10 + 5
        var call = world.tpaCalls.getFirst();
        assertThat(call.path()).isEqualTo("/enrollments");
        assertThat(call.headers().get("authorization")).isEqualTo("Basic dXNlcjpwYXNz");  // user:pass, from env, never from the DB
        assertThat(call.headers().get("idempotency-key")).startsWith(sync + ":100-109");
        assertThat(call.body()).contains("\"operation\":\"ADD\"").doesNotContain("pass");
        assertThat(world.tpaIdsRecorded).hasSize(25).containsEntry(100L, "T-100").containsEntry(124L, "T-124");
        assertThat(world.tokenCalls.get()).isEqualTo(1);                                    // token cached across ~7 engine calls
        assertThat(outbox("TpaSyncCompleted")).isEqualTo(1);
    }

    @Test
    void redeliveredEventsDoNotCreateSecondJobs() {
        UUID ev = UUID.randomUUID(), job = UUID.randomUUID();
        config(BROKER_A, 3, "NONE", 10);
        policyApproved(1, 3);
        world.roster(job, 3, 100);
        UUID first = applied(ev, job, 1, 3);
        UUID second = applied(ev, job, 1, 3);
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.sql("SELECT count(*) FROM integration.sync_jobs").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void policiesWithoutATpaAndEmptyEndorsementsAreSkippedWithoutCalls() {
        policyApproved(1, null);
        UUID noTpa = applied(UUID.randomUUID(), UUID.randomUUID(), 1, 5);
        policyApproved(2, 3);
        UUID empty = applied(UUID.randomUUID(), UUID.randomUUID(), 2, 0);
        assertThat(stateOf(noTpa)).isEqualTo("SKIPPED");
        assertThat(stateOf(empty)).isEqualTo("SKIPPED");
        assertThat(worker.drain()).isZero();
        assertThat(world.tpaCalls).isEmpty();
    }

    @Test
    void membersTheTpaRejectsAreRecordedAndTheRestStillSync() throws Exception {
        UUID sync = setup(5, 10, "NONE");
        world.rejectRefs.add(102L);
        worker.drain();
        assertThat(stateOf(sync)).isEqualTo("SUCCEEDED");
        assertThat(intCol(sync, "synced_count")).isEqualTo(4);
        assertThat(intCol(sync, "rejected_count")).isEqualTo(1);
        assertThat(world.tpaIdsRecorded).doesNotContainKey(102L).hasSize(4);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/broker/integration/sync-jobs/" + sync).header("Authorization", admin(BROKER_A)))
           .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.rejections[0].memberId").value(102))
           .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.rejections[0].reason").value("invalid DOB"));
    }

    @Test
    void anOutageBacksOffThenDeadLettersThenAnOperatorRetryCompletes() throws Exception {
        UUID sync = setup(3, 10, "NONE");
        world.tpaStatus = 503;
        worker.drain();                                                                      // attempt 1: 2 in-call tries, then job-level backoff
        assertThat(world.tpaCalls).hasSize(2);
        assertThat(stateOf(sync)).isEqualTo("PENDING");
        assertThat(intCol(sync, "attempts")).isEqualTo(1);
        assertThat(worker.drain()).as("not due yet: backoff is honoured").isZero();

        makeDue(sync); worker.drain();
        makeDue(sync); worker.drain();                                                       // third attempt hits max-attempts=3
        assertThat(stateOf(sync)).isEqualTo("DEAD");
        assertThat(outbox("TpaSyncFailed")).isEqualTo(1);
        var failed = jdbc.sql("SELECT payload::text FROM outbox.outbox_events WHERE event_type='TpaSyncFailed'").query(String.class).single();
        assertThat(failed).contains("\"attempts\": 3").contains("503");

        world.tpaStatus = 200;
        world.tpaCalls.clear();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/broker/integration/sync-jobs/" + sync + "/retry").header("Authorization", admin(BROKER_A)))
           .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        worker.drain();
        assertThat(stateOf(sync)).isEqualTo("SUCCEEDED");
        assertThat(intCol(sync, "synced_count")).isEqualTo(3);
    }

    @Test
    void aRetryResumesAfterTheLastCompletedBatchInsteadOfResendingEverything() {
        UUID sync = setup(30, 10, "NONE");
        world.failFromCall = 2;                                                              // batch 1 succeeds, batch 2 dies
        worker.drain();
        assertThat(intCol(sync, "cursor_id")).isEqualTo(109);
        assertThat(intCol(sync, "synced_count")).isEqualTo(10);
        assertThat(stateOf(sync)).isEqualTo("PENDING");

        world.failFromCall = Integer.MAX_VALUE;
        world.tpaCalls.clear();
        makeDue(sync); worker.drain();
        assertThat(stateOf(sync)).isEqualTo("SUCCEEDED");
        assertThat(intCol(sync, "synced_count")).isEqualTo(30);
        assertThat(world.tpaCalls).hasSize(2);                                               // only batches 2 and 3
        assertThat(world.tpaCalls.getFirst().body()).doesNotContain("\"ref\":100,").contains("\"ref\":110,");
    }

    @Test
    void permanentFailuresDeadLetterImmediatelyWithoutRetrying() {
        UUID sync = setup(3, 10, "NONE");
        world.tpaStatus = 400;
        worker.drain();
        assertThat(stateOf(sync)).isEqualTo("DEAD");
        assertThat(world.tpaCalls).hasSize(1);
        assertThat(intCol(sync, "attempts")).isEqualTo(1);
    }

    @Test
    void missingConfigurationOrCredentialsDeadLetterWithoutCallingTheTpa() {
        policyApproved(1, 3);
        UUID job = UUID.randomUUID(); world.roster(job, 2, 100);
        UUID noConfig = applied(UUID.randomUUID(), job, 1, 2);
        worker.drain();
        assertThat(stateOf(noConfig)).isEqualTo("DEAD");
        assertThat(jdbc.sql("SELECT last_error FROM integration.sync_jobs WHERE id=:i").param("i", noConfig).query(String.class).single()).contains("no enabled TPA configuration");

        jdbc.sql("INSERT INTO integration.tpa_configs(broker_id,tpa_id,adapter,base_url,auth_type,secret_ref) VALUES (1,3,'GENERIC_REST',:u,'BEARER','NOT_SET_ANYWHERE')")
            .param("u", world.tpaUrl).update();
        UUID noCred = applied(UUID.randomUUID(), job, 1, 2);
        worker.drain();
        assertThat(stateOf(noCred)).isEqualTo("DEAD");
        assertThat(jdbc.sql("SELECT last_error FROM integration.sync_jobs WHERE id=:i").param("i", noCred).query(String.class).single()).contains("NOT_SET_ANYWHERE_TOKEN").doesNotContain("pass");
        assertThat(world.tpaCalls).isEmpty();
    }

    @Test
    void anOpenCircuitStopsCallsAndDoesNotBurnAttempts() throws Exception {
        config(BROKER_A, 3, "NONE", 10);
        policyApproved(1, 3);
        world.tpaStatus = 503;
        for (int i = 0; i < 5; i++) applied(UUID.randomUUID(), jobWith(2), 1, 2);
        worker.drain();                                                                      // 5 jobs x 2 in-call tries = 10 failed calls: breaker opens
        int callsSoFar = world.tpaCalls.size();
        assertThat(callsSoFar).isEqualTo(10);

        UUID c = applied(UUID.randomUUID(), jobWith(2), 1, 2);
        worker.drain();
        assertThat(world.tpaCalls).as("breaker open: the TPA is left alone").hasSize(callsSoFar);
        assertThat(stateOf(c)).isEqualTo("PENDING");
        assertThat(intCol(c, "attempts")).as("waiting out an outage is not the job's failure").isZero();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/broker/integration/status").header("Authorization", admin(BROKER_A)))
           .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.circuitBreakers['3']").value("OPEN"));
    }

    private UUID jobWith(int n) { UUID j = UUID.randomUUID(); world.roster(j, n, 100); return j; }

    @Test
    void engineOutageIsRetriedAndABadServiceCredentialIsPermanent() {
        UUID sync = setup(3, 10, "NONE");
        world.engineStatus = 503;
        worker.drain();
        assertThat(stateOf(sync)).isEqualTo("PENDING");
        world.engineStatus = 200;
        world.identityStatus = 401;
        world.tokenCalls.set(0);
        tokens.invalidate(FakeWorld.TOKEN);
        makeDue(sync); worker.drain();
        assertThat(stateOf(sync)).isEqualTo("DEAD");
        assertThat(world.tpaCalls).isEmpty();
    }

    @Autowired com.broker.integration.engine.ServiceTokenProvider tokens;

    @Test
    void aCrashedWorkersJobIsReclaimedOnlyAfterItsLeaseExpires() {
        UUID sync = setup(3, 10, "NONE");
        var claimed = syncService.claimNext();
        assertThat(claimed).contains(sync);
        assertThat(stateOf(sync)).isEqualTo("IN_PROGRESS");
        assertThat(syncService.claimNext()).as("lease still valid: nobody else may take it").isEmpty();
        jdbc.sql("UPDATE integration.sync_jobs SET lease_until = now() - interval '1 second' WHERE id=:i").param("i", sync).update();
        assertThat(syncService.claimNext()).contains(sync);
    }

    @Autowired com.broker.integration.sync.SyncService syncService;

    @Test
    void concurrentWorkersNeverClaimTheSameJob() throws Exception {
        config(BROKER_A, 3, "NONE", 10);
        policyApproved(1, 3);
        for (int i = 0; i < 12; i++) applied(UUID.randomUUID(), jobWith(1), 1, 1);
        var claimed = ConcurrentHashMap.<UUID>newKeySet();
        var dupes = new java.util.concurrent.atomic.AtomicInteger();
        var pool = Executors.newFixedThreadPool(6);
        var start = new CountDownLatch(1);
        for (int t = 0; t < 6; t++) pool.submit(() -> {
            start.await();
            for (var id = syncService.claimNext(); id.isPresent(); id = syncService.claimNext()) if (!claimed.add(id.get())) dupes.incrementAndGet();
            return null;
        });
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(dupes.get()).isZero();
        assertThat(claimed).hasSize(12);
    }
}
