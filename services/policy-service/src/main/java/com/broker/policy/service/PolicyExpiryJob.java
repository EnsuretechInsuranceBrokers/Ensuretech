package com.broker.policy.service;

import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class PolicyExpiryJob {
    private final PolicyService policies;
    private final boolean enabled;

    PolicyExpiryJob(PolicyService policies, @Value("${app.expiry-job.enabled:true}") boolean enabled) {
        this.policies = policies;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${app.expiry-job.cron:0 15 0 * * *}")
    void run() {
        if (enabled) policies.expireDue(LocalDate.now());
    }
}
