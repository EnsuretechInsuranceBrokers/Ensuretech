package com.broker.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.broker.org.outbox.OutboxRelayJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OutboxTest extends IntegrationTestBase {
    @Autowired OutboxRelayJob relay;

    private int count(String where) {
        return jdbc.sql("SELECT count(*) FROM outbox.outbox_events WHERE " + where).query(Integer.class).single();
    }

    @Test
    void stateChangeAndEventCommitTogether() throws Exception {
        long id = createEmployer(brokerA, "ACME");
        assertThat(count("event_type='EmployerCreated' AND aggregate_id='" + id + "' AND status='PENDING'")).isEqualTo(1);
    }

    @Test
    void failedWriteLeavesNoOrphanEvent() throws Exception {
        createEmployer(brokerA, "ACME");
        int before = count("true");
        mvc.perform(json(post("/api/v1/broker/employers"), bearer("BROKER_ADMIN", brokerA, null), employerJson("ACME")))
                .andExpect(status().isConflict());
        assertThat(count("true")).isEqualTo(before);
    }

    @Test
    void relayPublishesOnceWithKeyAndEventId() throws Exception {
        createEmployer(brokerA, "ACME");
        assertThat(relay.relayBatch()).isEqualTo(1);
        assertThat(relay.relayBatch()).isZero();
        assertThat(sender.sent).hasSize(1);
        RecordingSender.Sent s = sender.sent.get(0);
        assertThat(s.topic()).isEqualTo("employer.events");
        assertThat(s.key()).isEqualTo(String.valueOf(brokerA));
        var json = new tools.jackson.databind.json.JsonMapper().readTree(s.payload());
        assertThat(json.get("event_id").asString()).isNotBlank();
        assertThat(json.get("event_type").asString()).isEqualTo("EmployerCreated");
        assertThat(json.get("employer_code").asString()).isEqualTo("ACME");
        assertThat(json.get("broker_id").asLong()).isEqualTo(brokerA);
        assertThat(count("status='PUBLISHED' AND published_at IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void failuresAreRetriedThenDeadLettered() throws Exception {
        createEmployer(brokerA, "ACME");
        sender.failing = true;
        for (int i = 0; i < 5; i++) {
            relay.relayBatch();
            jdbc.sql("UPDATE outbox.outbox_events SET next_retry_at = now() - interval '1 second' WHERE status='FAILED'").update();
        }
        assertThat(count("status='DEAD' AND retry_count=5 AND last_error LIKE '%broker down%'")).isEqualTo(1);
        sender.failing = false;
        assertThat(relay.relayBatch()).isZero();   // DEAD rows are not retried automatically
    }

    @Test
    void recoveredBrokerDeliversFailedEvent() throws Exception {
        createEmployer(brokerA, "ACME");
        sender.failing = true;
        relay.relayBatch();
        assertThat(count("status='FAILED'")).isEqualTo(1);
        sender.failing = false;
        jdbc.sql("UPDATE outbox.outbox_events SET next_retry_at = now() - interval '1 second'").update();
        assertThat(relay.relayBatch()).isEqualTo(1);
        assertThat(count("status='PUBLISHED'")).isEqualTo(1);
    }

    @Test
    void deactivateEmitsEventAndSoftDeletes() throws Exception {
        long id = createEmployer(brokerA, "ACME");
        mvc.perform(delete("/api/v1/broker/employers/" + id).header("Authorization", bearer("BROKER_ADMIN", brokerA, null)))
                .andExpect(status().isNoContent());
        assertThat(jdbc.sql("SELECT is_active FROM organization.employers WHERE id=:i").param("i", id)
                .query(Boolean.class).single()).isFalse();
        assertThat(count("event_type='EmployerDeactivated'")).isEqualTo(1);
    }
}
