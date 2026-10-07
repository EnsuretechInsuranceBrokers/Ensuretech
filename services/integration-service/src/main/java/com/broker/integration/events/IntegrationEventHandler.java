package com.broker.integration.events;

import com.broker.integration.sync.SyncService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Idempotent consumer of policy.events and endorsement.events. A poison message is logged and skipped; infrastructure faults are retried. */
@Component
public class IntegrationEventHandler {
    private static final Logger log = LoggerFactory.getLogger(IntegrationEventHandler.class);
    private final SyncService sync;
    private final ObjectMapper mapper;

    IntegrationEventHandler(SyncService sync, ObjectMapper mapper) { this.sync = sync; this.mapper = mapper; }

    @KafkaListener(topics = {"endorsement.events", "policy.events"}, autoStartup = "${app.kafka.listener.enabled:true}")
    public void onMessage(String json) { handle(json); }

    public void handle(String json) {
        try {
            JsonNode e = mapper.readTree(json);
            switch (e.path("event_type").asString("")) {
                case "PolicyApproved" -> {
                    JsonNode tpa = e.path("tpa_id");
                    sync.onPolicyApproved(e.get("broker_id").asLong(), e.get("employer_id").asLong(), e.get("policy_id").asLong(),
                            tpa.isNull() || tpa.isMissingNode() ? null : (short) tpa.asInt());
                }
                case "EndorsementApplied" -> sync.onEndorsementApplied(UUID.fromString(e.get("event_id").asString()),
                        UUID.fromString(e.get("job_id").asString()), e.get("broker_id").asLong(), e.get("employer_id").asLong(),
                        e.get("policy_id").asLong(), e.get("op_type").asString(), e.get("members_changed").asInt());
                default -> { }
            }
        } catch (DataIntegrityViolationException dup) {
            log.debug("duplicate delivery ignored");                      // the unique event_id won a race: already handled
        } catch (RuntimeException ex) {
            if (ex instanceof org.springframework.dao.TransientDataAccessException
                    || ex instanceof org.springframework.dao.DataAccessResourceFailureException
                    || ex instanceof org.springframework.transaction.CannotCreateTransactionException) throw ex;
            log.error("Skipping malformed or unprocessable event: {} ({})", ex.toString(), json);
        }
    }
}
