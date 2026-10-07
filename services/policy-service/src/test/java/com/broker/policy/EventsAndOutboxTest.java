package com.broker.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.broker.policy.outbox.OutboxRelayJob;
import com.broker.policy.service.EmployerEventHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class EventsAndOutboxTest extends IntegrationTestBase {
    @Autowired EmployerEventHandler handler;
    @Autowired OutboxRelayJob relay;

    private Boolean active(long e) {
        return jdbc.sql("SELECT is_active FROM policy_svc.employer_refs WHERE employer_id=:e").param("e", e).query(Boolean.class).optional().orElse(null);
    }

    private long broker(long e) {
        return jdbc.sql("SELECT broker_id FROM policy_svc.employer_refs WHERE employer_id=:e").param("e", e).query(Long.class).single();
    }

    @Test
    void employerEventsMaintainTheProjectionIdempotently() {
        String created = "{\"event_type\":\"EmployerCreated\",\"broker_id\":7,\"employer_id\":70,\"is_active\":true}";
        handler.handle(created);
        handler.handle(created);   // at-least-once redelivery
        assertThat(active(70)).isTrue();
        handler.handle("{\"event_type\":\"EmployerDeactivated\",\"broker_id\":7,\"employer_id\":70,\"is_active\":false}");
        assertThat(active(70)).isFalse();
        handler.handle("{\"event_type\":\"EmployerUpdated\",\"broker_id\":7,\"employer_id\":70,\"is_active\":true}");
        assertThat(active(70)).isTrue();
    }

    @Test
    void anEventCannotReparentAnEmployerToAnotherBroker() {
        handler.handle("{\"event_type\":\"EmployerCreated\",\"broker_id\":7,\"employer_id\":70,\"is_active\":true}");
        handler.handle("{\"event_type\":\"EmployerCreated\",\"broker_id\":8,\"employer_id\":70,\"is_active\":false}");
        assertThat(broker(70)).isEqualTo(7);
        assertThat(active(70)).isTrue();
    }

    @Test
    void eventsWithoutAnEmployerAreIgnored() {
        handler.handle("{\"event_type\":\"BrokerCreated\",\"broker_id\":9}");
        assertThat(jdbc.sql("SELECT count(*) FROM policy_svc.employer_refs WHERE broker_id=9").query(Integer.class).single()).isZero();
    }

    private int count(String where) {
        return jdbc.sql("SELECT count(*) FROM outbox.outbox_events WHERE " + where).query(Integer.class).single();
    }

    @Test
    void policyChangesEmitEventsAtomicallyAndRelayPublishesThem() throws Exception {
        long id = createPolicy(BROKER_A, EMP_A, "P-1", "FLAT", "GPA");
        mvc.perform(json(put("/api/v1/broker/policies/" + id + "/rate-card"), admin(BROKER_A),
                "{\"effectiveFrom\":\"2030-04-01\",\"rateTable\":{\"flat\":1000}}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/broker/policies/" + id + "/approve").header("Authorization", admin(BROKER_A))).andExpect(status().isOk());
        assertThat(count("event_type IN ('PolicyCreated','RateCardChanged','PolicyApproved') AND status='PENDING'")).isEqualTo(3);

        // a rejected change leaves no event behind
        int before = count("true");
        mvc.perform(post("/api/v1/broker/policies/" + id + "/approve").header("Authorization", admin(BROKER_A))).andExpect(status().isConflict());
        mvc.perform(json(put("/api/v1/broker/policies/" + id + "/rate-card"), admin(BROKER_A),
                "{\"effectiveFrom\":\"2030-04-01\",\"rateTable\":{\"nope\":1}}")).andExpect(status().isUnprocessableEntity());
        assertThat(count("true")).isEqualTo(before);

        assertThat(relay.relayBatch()).isEqualTo(3);
        assertThat(sender.sent).allSatisfy(s -> {
            assertThat(s.topic()).isEqualTo("policy.events");
            assertThat(s.key()).isEqualTo(String.valueOf(BROKER_A));
        });
        assertThat(count("status='PUBLISHED'")).isEqualTo(3);
    }

    @Test
    void policyApprovedMatchesTheSharedContractBillingConsumes() throws Exception {
        long id = createPolicy(BROKER_A, EMP_A, "P-1", "FLAT", "GPA");
        mvc.perform(json(put("/api/v1/broker/policies/" + id + "/rate-card"), admin(BROKER_A),
                "{\"effectiveFrom\":\"2030-04-01\",\"rateTable\":{\"flat\":1000}}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/broker/policies/" + id + "/approve").header("Authorization", admin(BROKER_A))).andExpect(status().isOk());
        var mapper = new tools.jackson.databind.json.JsonMapper();
        String real = jdbc.sql("SELECT payload::text FROM outbox.outbox_events WHERE event_type='PolicyApproved'").query(String.class).single();
        String sample = java.nio.file.Files.readString(java.nio.file.Path.of("../../shared/contracts/events/policy-approved.sample.json"));
        var realKeys = new java.util.TreeSet<String>();
        mapper.readTree(real).propertyNames().forEach(realKeys::add);
        var sampleKeys = new java.util.TreeSet<String>();
        mapper.readTree(sample).propertyNames().forEach(sampleKeys::add);
        assertThat(realKeys).as("PolicyApproved fields drifted from shared/contracts/events").isEqualTo(sampleKeys);
    }
}
