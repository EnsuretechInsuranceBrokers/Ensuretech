package com.broker.policy.outbox;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Writes the event in the CALLER's transaction (MANDATORY), so state change and event commit atomically. */
@Component
public class OutboxPublisher {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public OutboxPublisher(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(String aggregateType, Object aggregateId, String eventType, String topic,
                        String partitionKey, Map<String, Object> payload) {
        UUID id = UUID.randomUUID();
        Map<String, Object> body = new LinkedHashMap<>(payload);
        body.put("event_id", id.toString());   // consumers dedupe on this (delivery is at-least-once)
        body.put("event_type", eventType);
        jdbc.sql("""
                INSERT INTO outbox.outbox_events
                  (id, aggregate_type, aggregate_id, event_type, payload, topic, partition_key, correlation_id)
                VALUES (:id, :at, :aid, :et, CAST(:payload AS jsonb), :topic, :pk, :cid)
                """)
            .param("id", id)
            .param("at", aggregateType)
            .param("aid", String.valueOf(aggregateId))
            .param("et", eventType)
            .param("payload", mapper.writeValueAsString(body))
            .param("topic", topic)
            .param("pk", partitionKey)
            .param("cid", id.toString())
            .update();
        return id;
    }
}
