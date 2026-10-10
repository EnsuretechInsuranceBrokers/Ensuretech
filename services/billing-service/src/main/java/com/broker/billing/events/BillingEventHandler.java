package com.broker.billing.events;

import com.broker.billing.service.CdLedgerService;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reacts to other services' events. Idempotent (delivery is at-least-once). A malformed event is logged and skipped:
 * one poison message must not stop a partition's billing. Events whose type we do not care about are ignored.
 */
@Component
public class BillingEventHandler {
    private static final Logger log = LoggerFactory.getLogger(BillingEventHandler.class);
    /** States after which an endorsement will never reach the roster, so its reserved deposit must be freed. */
    private static final Set<String> NEVER_APPLIED = Set.of("CANCELLED_BY_HR", "VALIDATION_FAILED");

    private final CdLedgerService ledger;
    private final ObjectMapper mapper;

    BillingEventHandler(CdLedgerService ledger, ObjectMapper mapper) {
        this.ledger = ledger;
        this.mapper = mapper;
    }

    @KafkaListener(topics = {"endorsement.events", "policy.events"}, autoStartup = "${app.kafka.listener.enabled:true}")
    public void onMessage(String json) {
        handle(json);
    }

    public void handle(String json) {
        com.broker.billing.logging.CorrelationFilter.runWith(com.broker.billing.logging.CorrelationFilter.fromEvent(json), () -> handle0(json));      // the logs of this event carry the id of the request that caused it
    }

    private void handle0(String json) {
        try {
            JsonNode e = mapper.readTree(json);
            switch (e.path("event_type").asString("")) {
                case "PolicyApproved" ->
                    ledger.openAccount(e.get("broker_id").asLong(), e.get("employer_id").asLong(), e.get("policy_id").asLong());
                case "EndorsementApplied" ->
                    ledger.commitEndorsement(UUID.fromString(e.get("event_id").asString()), e.get("broker_id").asLong(),
                            e.get("employer_id").asLong(), e.get("policy_id").asLong(), UUID.fromString(e.get("job_id").asString()),
                            new BigDecimal(e.get("total").asString()));
                case "EndorsementStateChanged" -> {
                    if (NEVER_APPLIED.contains(e.path("to_state").asString(""))) {
                        ledger.releaseForJob(UUID.fromString(e.get("job_id").asString()));
                    }
                }
                default -> { }
            }
        } catch (RuntimeException ex) {
            if (isTransient(ex)) throw ex;                                  // let the consumer retry real infrastructure faults
            log.error("Skipping malformed or unprocessable event: {} ({})", ex.toString(), json);
        }
    }

    private static boolean isTransient(RuntimeException ex) {
        return ex instanceof org.springframework.dao.TransientDataAccessException
                || ex instanceof org.springframework.dao.DataAccessResourceFailureException
                || ex instanceof org.springframework.transaction.CannotCreateTransactionException;
    }
}
