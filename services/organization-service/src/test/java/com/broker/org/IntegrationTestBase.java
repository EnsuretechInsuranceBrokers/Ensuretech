package com.broker.org;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

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

@SpringBootTest(properties = {"app.outbox.relay.enabled=false"})
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

    protected long brokerA;
    protected long brokerB;

    @BeforeEach
    void resetDb() {
        jdbc.sql("TRUNCATE organization.employer_configs, organization.employers, organization.broker_branches, "
                + "organization.brokers RESTART IDENTITY CASCADE").update();
        jdbc.sql("DELETE FROM outbox.outbox_events").update();
        sender.sent.clear();
        sender.failing = false;
        brokerA = insertBroker("A");
        brokerB = insertBroker("B");
    }

    private long insertBroker(String code) {
        return jdbc.sql("INSERT INTO organization.brokers(code,name,contact_email) VALUES (:c,:c,:e) RETURNING id")
                .param("c", "BRK_" + code).param("e", code.toLowerCase() + "@broker.test")
                .query(Long.class).single();
    }

    protected static String bearer(String role, Long brokerId, Long employerId) {
        return "Bearer " + TestTokens.token(role, brokerId, employerId);
    }

    protected MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String auth, String body) {
        return b.header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    protected static String employerJson(String code) {
        return """
            {"employerCode":"%s","legalName":"%s Pvt Ltd","displayName":"%s","pan":"ABCDE1234F",
             "contactEmail":"hr@%s.test","employeeCount":120}""".formatted(code, code, code, code.toLowerCase());
    }

    /** Creates an employer through the API and returns its id. */
    protected long createEmployer(long brokerId, String code) throws Exception {
        String body = mvc.perform(json(post("/api/v1/broker/employers"), bearer("BROKER_ADMIN", brokerId, null),
                        employerJson(code)))
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }
}
