package com.broker.billing.service;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class ReservationExpiryJob {
    private final CdLedgerService ledger;
    private final boolean enabled;

    ReservationExpiryJob(CdLedgerService ledger, @Value("${app.cd.expiry-sweep-enabled:true}") boolean enabled) {
        this.ledger = ledger;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.cd.expiry-sweep-ms:60000}")
    void sweep() {
        if (enabled) ledger.releaseExpired(Instant.now());
    }
}
