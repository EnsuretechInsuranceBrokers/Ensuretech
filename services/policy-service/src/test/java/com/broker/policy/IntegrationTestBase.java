package com.broker.policy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {
        "app.outbox.relay.enabled=false", "app.kafka.listener.enabled=false", "app.expiry-job.enabled=false",
        "app.grpc.port=0"})
@AutoConfigureMockMvc
@Import(TestSenderConfig.class)
abstract class IntegrationTestBase {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.jwt.public-key-location", () -> "file:" + TestTokens.PUBLIC_PEM);
        r.add("app.jwt.issuer", () -> TestTokens.ISSUER);
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected RecordingSender sender;
    @Autowired protected StringRedisTemplate redis;

    protected static final long BROKER_A = 1, BROKER_B = 2, EMP_A = 10, EMP_B = 20;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE policy_svc.rate_cards, policy_svc.family_constructs, policy_svc.policies, "
                + "policy_svc.employer_refs RESTART IDENTITY CASCADE").update();
        jdbc.sql("DELETE FROM outbox.outbox_events").update();
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) c -> {
            c.serverCommands().flushDb();
            return null;
        });
        sender.sent.clear();
        sender.failing = false;
        ref(EMP_A, BROKER_A);
        ref(EMP_B, BROKER_B);
    }

    protected void ref(long employer, long broker) {
        jdbc.sql("INSERT INTO policy_svc.employer_refs(employer_id, broker_id) VALUES (:e,:b)")
                .param("e", employer).param("b", broker).update();
    }

    protected static String bearer(String role, Long brokerId, Long employerId) {
        return "Bearer " + TestTokens.token(role, brokerId, employerId);
    }

    protected static String admin(long broker) { return bearer("BROKER_ADMIN", broker, null); }

    protected MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String auth, String body) {
        return b.header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    protected short premiumTypeId(String code) {
        return jdbc.sql("SELECT id FROM policy_svc.premium_types WHERE code=:c").param("c", code).query(Short.class).single();
    }

    protected static String policyJson(long employerId, String number, short premiumTypeId, String policyType) {
        return """
            {"employerId":%d,"policyNumber":"%s","insurerId":3,"tpaId":1,"policyType":"%s",
             "premiumTypeId":%d,"suminsuredSubtypeId":1,"raterTypeId":1,
             "startDate":"2030-04-01","endDate":"2031-03-31"}""".formatted(employerId, number, policyType, premiumTypeId);
    }

    /** Creates a policy through the API (as BROKER_ADMIN of the given broker) and returns its id. */
    protected long createPolicy(long broker, long employer, String number, String premiumCode, String policyType) throws Exception {
        String body = mvc.perform(json(post("/api/v1/broker/policies"), admin(broker),
                        policyJson(employer, number, premiumTypeId(premiumCode), policyType)))
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }
}
