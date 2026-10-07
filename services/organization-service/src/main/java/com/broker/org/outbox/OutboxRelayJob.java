package com.broker.org.outbox;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes pending outbox rows. FOR UPDATE SKIP LOCKED lets several instances run safely.
 * Delivery is at-least-once; consumers must be idempotent (dedupe on the payload's event_id).
 */
@Component
public class OutboxRelayJob {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelayJob.class);

    private final JdbcClient jdbc;
    private final EventSender sender;
    private final boolean enabled;
    private final int batchSize;

    public OutboxRelayJob(JdbcClient jdbc, EventSender sender,
                          @Value("${app.outbox.relay.enabled:true}") boolean enabled,
                          @Value("${app.outbox.relay.batch-size:100}") int batchSize) {
        this.jdbc = jdbc;
        this.sender = sender;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay.interval-ms:5000}")
    void tick() {
        if (enabled) relayBatch();
    }

    /** @return number of events published in this batch */
    @Transactional
    public int relayBatch() {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT id, topic, partition_key, payload::text AS payload, retry_count, max_retries
                FROM outbox.outbox_events
                WHERE is_test_data = FALSE
                  AND (status = 'PENDING' OR (status = 'FAILED' AND next_retry_at <= now()))
                ORDER BY created_at
                LIMIT :n FOR UPDATE SKIP LOCKED
                """).param("n", batchSize).query().listOfRows();
        int published = 0;
        for (Map<String, Object> row : rows) {
            Object id = row.get("id");
            try {
                sender.send((String) row.get("topic"), (String) row.get("partition_key"), (String) row.get("payload"));
                jdbc.sql("UPDATE outbox.outbox_events SET status='PUBLISHED', published_at=now(), last_error=NULL WHERE id=:id")
                    .param("id", id).update();
                published++;
            } catch (Exception e) {
                int attempts = ((Number) row.get("retry_count")).intValue() + 1;
                boolean dead = attempts >= ((Number) row.get("max_retries")).intValue();
                log.warn("Outbox event {} failed (attempt {}): {}", id, attempts, e.toString());
                jdbc.sql("""
                        UPDATE outbox.outbox_events
                        SET status = :st, retry_count = :rc, last_error = :err,
                            next_retry_at = now() + (:backoff * interval '1 second')
                        WHERE id = :id
                        """)
                    .param("st", dead ? "DEAD" : "FAILED").param("rc", attempts)
                    .param("err", String.valueOf(e)).param("backoff", Math.min(300, 1L << attempts))
                    .param("id", id).update();
            }
        }
        return published;
    }
}
