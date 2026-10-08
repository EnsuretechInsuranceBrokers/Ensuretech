package com.broker.integration.sync;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The legacy system pulls each policy's active lives on cron; this does the same, off by default. Each tick reconciles the policies that have gone
 * longest without a successful run (never-run first), at most {@code batch} of them, so a large book is spread across ticks and one dead TPA
 * cannot starve the rest (a failed run still counts as a run, so it goes to the back of the queue).
 */
@Component
public class ReconcileScheduler {
    private static final Logger log = LoggerFactory.getLogger(ReconcileScheduler.class);
    private final ReconcileService service;
    private final JdbcClient jdbc;
    private final boolean enabled;
    private final int batch;
    private final long minIntervalSeconds;

    ReconcileScheduler(ReconcileService service, JdbcClient jdbc, @Value("${app.reconcile.enabled:false}") boolean enabled,
                       @Value("${app.reconcile.batch:20}") int batch, @Value("${app.reconcile.min-interval-seconds:21600}") long minIntervalSeconds) {
        this.service = service; this.jdbc = jdbc; this.enabled = enabled; this.batch = batch; this.minIntervalSeconds = minIntervalSeconds;
    }

    @Scheduled(cron = "${app.reconcile.cron:0 0 */6 * * *}")
    void tick() {
        if (enabled) runOnce();
    }

    record Due(long brokerId, long policyId) {}

    /** @return how many policies were reconciled (successfully or not). Public for operators and tests. */
    public int runOnce() {
        List<Due> due = jdbc.sql("""
                SELECT r.broker_id, r.policy_id FROM integration.policy_routes r
                JOIN integration.tpa_configs c ON c.broker_id = r.broker_id AND c.tpa_id = r.tpa_id AND c.enabled
                LEFT JOIN (SELECT policy_id, max(created_at) last_run FROM integration.reconcile_runs GROUP BY policy_id) l ON l.policy_id = r.policy_id
                WHERE r.tpa_id IS NOT NULL AND (l.last_run IS NULL OR l.last_run < now() - make_interval(secs => :age))
                ORDER BY l.last_run NULLS FIRST, r.policy_id LIMIT :n""")
                .param("age", (double) minIntervalSeconds).param("n", batch).query((rs, i) -> new Due(rs.getLong(1), rs.getLong(2))).list();
        int ran = 0;
        for (Due d : due) {
            try {
                service.reconcile(d.brokerId(), d.policyId());
            } catch (RuntimeException e) {
                log.warn("scheduled reconciliation of policy {} did not complete: {}", d.policyId(), e.getMessage());
            }
            ran++;
        }
        return ran;
    }
}
