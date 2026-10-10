package com.broker.policy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Maintains policy_svc.employer_refs from organization-service's employer.events.
 * Idempotent (upsert) because delivery is at-least-once; BrokerCreated and other events without an employer_id are ignored.
 */
@Component
public class EmployerEventHandler {
    private static final Logger log = LoggerFactory.getLogger(EmployerEventHandler.class);

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    EmployerEventHandler(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @KafkaListener(topics = "employer.events", autoStartup = "${app.kafka.listener.enabled:true}")
    public void onMessage(String json) {
        handle(json);
    }

    @Transactional
    public void handle(String json) {
        com.broker.policy.logging.CorrelationFilter.runWith(com.broker.policy.logging.CorrelationFilter.fromEvent(json), () -> handle0(json));      // the logs of this event carry the id of the request that caused it
    }

    private void handle0(String json) {
        JsonNode e = mapper.readTree(json);
        String type = e.path("event_type").asString("");
        if (!e.hasNonNull("employer_id") || !e.hasNonNull("broker_id")) return;
        boolean active = !"EmployerDeactivated".equals(type) && e.path("is_active").asBoolean(true);
        long employerId = e.get("employer_id").asLong();
        long brokerId = e.get("broker_id").asLong();
        // An employer never changes broker; refuse to let an event re-parent it to another tenant.
        int n = jdbc.sql("""
                INSERT INTO policy_svc.employer_refs (employer_id, broker_id, is_active, updated_at)
                VALUES (:e, :b, :a, now())
                ON CONFLICT (employer_id) DO UPDATE SET is_active = EXCLUDED.is_active, updated_at = now()
                WHERE policy_svc.employer_refs.broker_id = EXCLUDED.broker_id
                """).param("e", employerId).param("b", brokerId).param("a", active).update();
        if (n == 0) log.warn("Ignored employer event that would move employer {} to broker {}", employerId, brokerId);
    }
}
