package com.broker.integration.sync;

import com.broker.integration.api.ConflictException;
import com.broker.integration.api.NotFoundException;
import com.broker.integration.domain.PolicyRoute;
import com.broker.integration.domain.PolicyRouteRepository;
import com.broker.integration.domain.TpaConfig;
import com.broker.integration.domain.TpaConfigRepository;
import com.broker.integration.engine.EngineClient;
import com.broker.integration.engine.EngineException;
import com.broker.integration.tpa.Reconciler;
import com.broker.integration.tpa.TpaAdapter;
import com.broker.integration.tpa.TpaGateway;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Pull a policy's active lives from its TPA and reconcile them onto our roster (the legacy GetEnrolmentData / assignTpaMemberId). */
@Service
public class ReconcileService {
    static final int MAX_ROSTER = 200_000, MAX_STORED_MISSES = 5_000, PAGE = 500;

    public record Run(UUID id, long policyId, short tpaId, String status, int rosterSize, int tpaRecords, int matched, int misses, int rosterUnmatched, String error) {}

    public record Miss(String employeeCode, String relation, String memberId, String reason) {}

    private final PolicyRouteRepository routes;
    private final TpaConfigRepository configs;
    private final EngineClient engine;
    private final TpaGateway gateway;
    private final JdbcClient jdbc;

    ReconcileService(PolicyRouteRepository routes, TpaConfigRepository configs, EngineClient engine, TpaGateway gateway, JdbcClient jdbc) {
        this.routes = routes; this.configs = configs; this.engine = engine; this.gateway = gateway; this.jdbc = jdbc;
    }

    public Run reconcile(long brokerId, long policyId) {
        PolicyRoute route = routes.findById(policyId).filter(r -> r.getBrokerId() == brokerId).orElseThrow(() -> new NotFoundException("No such policy"));
        if (route.getTpaId() == null) throw new ConflictException("The policy has no TPA");
        TpaConfig cfg = configs.findByBrokerIdAndTpaId(brokerId, route.getTpaId()).filter(TpaConfig::isEnabled)
                .orElseThrow(() -> new ConflictException("No enabled TPA configuration for tpa " + route.getTpaId()));
        short tpa = route.getTpaId();
        List<TpaAdapter.RosterMember> roster = new ArrayList<>();
        try {
            String policyNumber = null;
            long after = 0;
            while (true) {
                EngineClient.PolicyPage page = engine.policyMembers(policyId, after, PAGE);
                if (policyNumber == null) policyNumber = page.policyNumber();
                roster.addAll(page.members());
                if (roster.size() > MAX_ROSTER) throw new ConflictException("The policy has more than " + MAX_ROSTER + " active members; reconcile in smaller policies");
                if (page.nextAfter() == null) break;
                after = page.nextAfter();
            }
            if (roster.isEmpty()) return store(brokerId, policyId, tpa, "OK", 0, 0, List.of(), List.of(), 0, null);
            if (policyNumber == null || policyNumber.isBlank()) throw new ConflictException("The policy number is not known yet (no endorsement has run for this policy)");
            List<Reconciler.ActiveLife> lives = gateway.activeLives(cfg, policyNumber);
            boolean strip = Boolean.TRUE.equals(jdbc.sql("SELECT strip_leading_zeros FROM integration.tpa_reconcile_settings WHERE broker_id=:b AND tpa_id=:t")
                    .param("b", brokerId).param("t", tpa).query(Boolean.class).optional().orElse(false));
            Map<String, String> labels = new LinkedHashMap<>();
            jdbc.sql("SELECT tpa_label, relation_code FROM integration.tpa_relation_mappings WHERE broker_id=:b AND tpa_id=:t").param("b", brokerId).param("t", tpa)
                    .query((rs, i) -> Map.entry(rs.getString(1), rs.getString(2))).list().forEach(e -> labels.put(e.getKey(), e.getValue()));
            Reconciler.Result res = Reconciler.reconcile(lives, roster, labels, strip);
            Map<Long, String> ids = new LinkedHashMap<>();
            res.assignments().forEach(a -> ids.put(a.rosterId(), a.tpaMemberId()));
            List<Map.Entry<Long, String>> all = new ArrayList<>(ids.entrySet());
            for (int i = 0; i < all.size(); i += PAGE) {
                Map<Long, String> batch = new LinkedHashMap<>();
                all.subList(i, Math.min(all.size(), i + PAGE)).forEach(e -> batch.put(e.getKey(), e.getValue()));
                engine.recordPolicyTpaIds(policyId, batch);
            }
            return store(brokerId, policyId, tpa, "OK", roster.size(), lives.size(), res.assignments(), res.misses(), res.rosterUnmatched(), null);
        } catch (TpaAdapter.TpaException | EngineException e) {
            Run failed = store(brokerId, policyId, tpa, "FAILED", roster.size(), 0, List.of(), List.of(), 0, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Reconciliation failed (run " + failed.id() + "): " + e.getMessage());
        }
    }

    @Transactional
    Run store(long brokerId, long policyId, short tpa, String status, int rosterSize, int records, List<Reconciler.Assignment> matched,
              List<Reconciler.Miss> misses, int rosterUnmatched, String error) {
        UUID id = jdbc.sql("INSERT INTO integration.reconcile_runs(broker_id, policy_id, tpa_id, status, roster_size, tpa_records, matched, misses, roster_unmatched, error) "
                        + "VALUES (:b,:p,:t,:s,:rs,:tr,:m,:x,:ru,:e) RETURNING id")
                .param("b", brokerId).param("p", policyId).param("t", tpa).param("s", status).param("rs", rosterSize).param("tr", records)
                .param("m", matched.size()).param("x", misses.size()).param("ru", rosterUnmatched).param("e", error == null ? null : error.substring(0, Math.min(500, error.length())))
                .query(UUID.class).single();
        int seq = 0;
        for (Reconciler.Miss m : misses.subList(0, Math.min(MAX_STORED_MISSES, misses.size()))) {
            jdbc.sql("INSERT INTO integration.reconcile_misses(run_id, seq, employee_code, relation, tpa_member_id, reason) VALUES (:r,:s,:c,:rel,:m,:why)")
                    .param("r", id).param("s", seq++).param("c", m.employeeCode()).param("rel", m.relation()).param("m", m.memberId()).param("why", m.reason()).update();
        }
        return new Run(id, policyId, tpa, status, rosterSize, records, matched.size(), misses.size(), rosterUnmatched, error);
    }

    @Transactional(readOnly = true)
    public List<Run> runs(long brokerId, Long policyId) {
        String sql = "SELECT id, policy_id, tpa_id, status, roster_size, tpa_records, matched, misses, roster_unmatched, error FROM integration.reconcile_runs WHERE broker_id=:b"
                + (policyId == null ? "" : " AND policy_id=:p") + " ORDER BY created_at DESC LIMIT 100";
        var q = jdbc.sql(sql).param("b", brokerId);
        if (policyId != null) q = q.param("p", policyId);
        return q.query((rs, i) -> new Run(rs.getObject(1, UUID.class), rs.getLong(2), rs.getShort(3), rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getString(10))).list();
    }

    @Transactional(readOnly = true)
    public Run run(long brokerId, UUID id) {
        return runs(brokerId, null).stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow(() -> new NotFoundException("No such reconciliation run"));
    }

    @Transactional(readOnly = true)
    public List<Miss> misses(long brokerId, UUID id) {
        run(brokerId, id);
        return jdbc.sql("SELECT employee_code, relation, tpa_member_id, reason FROM integration.reconcile_misses WHERE run_id=:r ORDER BY seq").param("r", id)
                .query((rs, i) -> new Miss(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))).list();
    }

    @Transactional
    public void putSettings(long brokerId, short tpa, boolean stripLeadingZeros, Map<String, String> labels) {
        jdbc.sql("INSERT INTO integration.tpa_reconcile_settings(broker_id, tpa_id, strip_leading_zeros) VALUES (:b,:t,:s) ON CONFLICT (broker_id, tpa_id) DO UPDATE SET strip_leading_zeros = :s")
                .param("b", brokerId).param("t", tpa).param("s", stripLeadingZeros).update();
        jdbc.sql("DELETE FROM integration.tpa_relation_mappings WHERE broker_id=:b AND tpa_id=:t").param("b", brokerId).param("t", tpa).update();
        for (var e : labels.entrySet()) {
            jdbc.sql("INSERT INTO integration.tpa_relation_mappings(broker_id, tpa_id, tpa_label, relation_code) VALUES (:b,:t,:l,:r)")
                    .param("b", brokerId).param("t", tpa).param("l", e.getKey().trim().toLowerCase(Locale.ROOT)).param("r", e.getValue()).update();
        }
    }
}
