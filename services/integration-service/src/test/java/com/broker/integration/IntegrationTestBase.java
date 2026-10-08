package com.broker.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.broker.integration.events.IntegrationEventHandler;
import com.broker.integration.sync.SyncWorker;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {"app.outbox.relay.enabled=false", "app.kafka.listener.enabled=false", "app.sync.worker-enabled=false",
        "app.tpa.allow-http=true", "app.tpa.allow-private-hosts=true", "app.identity.client-id=svc_test", "app.identity.client-secret=s3cret",
        "app.sync.call-retries=2", "app.sync.call-retry-base-ms=1", "app.sync.max-attempts=3", "app.sync.backoff-base-seconds=60",
        "app.sync.breaker-window=10", "app.sync.breaker-min-calls=10", "app.sync.breaker-failure-rate=50", "app.sync.breaker-open-seconds=45",
        "TESTTPA_USERNAME=user", "TESTTPA_PASSWORD=pass"})
@AutoConfigureMockMvc
@Import(TestSenderConfig.class)
abstract class IntegrationTestBase {
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.jwt.public-key-location", () -> "file:" + TestTokens.PUBLIC_PEM);
        r.add("app.jwt.issuer", () -> TestTokens.ISSUER);
        r.add("app.engine.base-url", () -> FakeWorld.I.engineUrl);
        r.add("app.identity.token-url", () -> FakeWorld.I.identityUrl);
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected RecordingSender sender;
    @Autowired protected IntegrationEventHandler events;
    @Autowired protected SyncWorker worker;
    @Autowired protected tools.jackson.databind.ObjectMapper mapper;

    protected static final long BROKER_A = 1, BROKER_B = 2, EMP_A = 10;
    protected final FakeWorld world = FakeWorld.I;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE integration.reconcile_misses, integration.reconcile_runs, integration.tpa_relation_mappings, integration.tpa_reconcile_settings, integration.sync_rejections, integration.sync_jobs, integration.policy_routes, integration.tpa_configs RESTART IDENTITY CASCADE").update();
        jdbc.sql("DELETE FROM outbox.outbox_events").update();
        sender.sent.clear();
        sender.failing = false;
        world.reset();
        resetBreakersAndTokens();
    }

    @Autowired private org.springframework.context.ApplicationContext ctx;

    /** Breakers and the cached token live in singletons; recreate them so tests do not leak state into each other. */
    private void resetBreakersAndTokens() {
        try {
            var gw = ctx.getBean(com.broker.integration.tpa.TpaGateway.class);
            var f = gw.getClass().getDeclaredField("breakers");
            f.setAccessible(true);
            ((io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry) f.get(gw)).getAllCircuitBreakers().forEach(io.github.resilience4j.circuitbreaker.CircuitBreaker::reset);
            ctx.getBean(com.broker.integration.engine.ServiceTokenProvider.class).invalidate(FakeWorld.TOKEN);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static String bearer(String role, Long broker, Long employer) { return "Bearer " + TestTokens.token(role, broker, employer); }

    protected static String admin(long broker) { return bearer("BROKER_ADMIN", broker, null); }

    protected MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String auth, String body) {
        return b.header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    protected void config(long broker, int tpa, String auth, int batch) {
        jdbc.sql("INSERT INTO integration.tpa_configs(broker_id, tpa_id, adapter, base_url, auth_type, secret_ref, batch_size, timeout_ms) "
                + "VALUES (:b, :t, 'GENERIC_REST', :u, CAST(:a AS integration.tpa_auth_enum), :s, :bs, 2000)")
            .param("b", broker).param("t", (short) tpa).param("u", world.tpaUrl).param("a", auth)
            .param("s", auth.equals("NONE") ? null : "TESTTPA").param("bs", batch).update();
    }

    protected void policyApproved(long policy, Integer tpa) {
        events.handle("{\"event_id\":\"" + UUID.randomUUID() + "\",\"event_type\":\"PolicyApproved\",\"broker_id\":1,\"employer_id\":10,\"policy_id\":"
                + policy + ",\"policy_number\":\"P\",\"tpa_id\":" + tpa + ",\"status\":\"APPROVED_AND_LIVE\"}");
    }

    /** Delivers EndorsementApplied and returns the sync job id it created (null when none). */
    protected UUID applied(UUID eventId, UUID job, long policy, int members) {
        events.handle("{\"event_id\":\"" + eventId + "\",\"event_type\":\"EndorsementApplied\",\"job_id\":\"" + job + "\",\"broker_id\":1,\"employer_id\":10,"
                + "\"policy_id\":" + policy + ",\"op_type\":\"ADD\",\"source_type\":\"CSV_UPLOAD\",\"members_changed\":" + members + ",\"premium_delta\":\"1\",\"tax_amount\":\"0\",\"total\":\"1\"}");
        return jdbc.sql("SELECT id FROM integration.sync_jobs WHERE event_id = :e").param("e", eventId).query(UUID.class).optional().orElse(null);
    }

    protected String stateOf(UUID syncJob) { return jdbc.sql("SELECT status::text FROM integration.sync_jobs WHERE id = :i").param("i", syncJob).query(String.class).single(); }

    protected int intCol(UUID syncJob, String col) { return jdbc.sql("SELECT " + col + " FROM integration.sync_jobs WHERE id = :i").param("i", syncJob).query(Integer.class).single(); }

    protected void makeDue(UUID syncJob) { jdbc.sql("UPDATE integration.sync_jobs SET next_attempt_at = now() - interval '1 second' WHERE id = :i").param("i", syncJob).update(); }

    protected long outbox(String type) { return jdbc.sql("SELECT count(*) FROM outbox.outbox_events WHERE event_type = :t").param("t", type).query(Long.class).single(); }
}
