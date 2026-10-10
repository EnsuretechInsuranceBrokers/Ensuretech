package com.broker.policy.service;

import com.broker.policy.api.NotFoundException;
import com.broker.policy.api.ValidationFailedException;
import com.broker.policy.api.ValidationFailedException.Issue;
import com.broker.policy.domain.Policy;
import com.broker.policy.domain.PolicyRepository;
import com.broker.policy.service.EnrollmentWindowEvaluator.*;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Enrollment windows of a policy: the policy-wide window, employee-level extensions, confirmations, and who changed what. */
@Service
public class EnrollmentWindowService {
    public record Audit(long id, String scope, List<String> employees, Object oldValue, Object newValue, String modifiedBy, Instant at) {}
    public record OverrideIn(String employeeRef, boolean enabled, LocalDate start, LocalDate end, LocalDate closeDate) {}

    private final PolicyRepository policies;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    EnrollmentWindowService(PolicyRepository policies, JdbcClient jdbc, ObjectMapper json) { this.policies = policies; this.jdbc = jdbc; this.json = json; }

    private Policy policy(long brokerId, long policyId) {
        return policies.findByIdAndBrokerId(policyId, brokerId).orElseThrow(() -> new NotFoundException("Policy not found"));
    }

    // ── policy-wide window ────────────────────────────────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public Settings settings(long brokerId, long policyId) {
        policy(brokerId, policyId);
        return jdbc.sql("SELECT enabled, start_date, end_date, midterm_days, allow_after_expiry, days_after_expiry FROM policy_svc.enrollment_windows WHERE policy_id = :p AND broker_id = :b")
                .param("p", policyId).param("b", brokerId)
                .query((rs, n) -> new Settings(rs.getBoolean(1), rs.getObject(2, LocalDate.class), rs.getObject(3, LocalDate.class), rs.getInt(4), rs.getBoolean(5), rs.getInt(6)))
                .optional().orElse(new Settings(false, null, null, 30, false, 0));
    }

    @Transactional
    public Settings putSettings(long brokerId, long policyId, Settings in, String actor) {
        policy(brokerId, policyId);
        List<Issue> bad = new ArrayList<>();
        if (in.enabled() && (in.start() == null || in.end() == null)) bad.add(new Issue("WINDOW_DATES", "An enabled window needs a start and an end date"));
        if (in.start() != null && in.end() != null && in.end().isBefore(in.start())) bad.add(new Issue("WINDOW_ORDER", "The window cannot end before it starts"));
        if (in.midtermDays() < 1 || in.midtermDays() > 365) bad.add(new Issue("MIDTERM_DAYS", "midtermDays must be between 1 and 365"));
        if (in.daysAfterExpiry() < 0 || in.daysAfterExpiry() > 365) bad.add(new Issue("DAYS_AFTER_EXPIRY", "daysAfterExpiry must be between 0 and 365"));
        if (!bad.isEmpty()) throw new ValidationFailedException(bad);
        Settings old = settings(brokerId, policyId);
        jdbc.sql("""
                INSERT INTO policy_svc.enrollment_windows(policy_id, broker_id, enabled, start_date, end_date, midterm_days, allow_after_expiry, days_after_expiry, updated_at)
                VALUES (:p, :b, :en, :s, :e, :m, :a, :d, now())
                ON CONFLICT (policy_id) DO UPDATE SET enabled = :en, start_date = :s, end_date = :e, midterm_days = :m, allow_after_expiry = :a, days_after_expiry = :d, updated_at = now()""")
                .param("p", policyId).param("b", brokerId).param("en", in.enabled()).param("s", in.start()).param("e", in.end()).param("m", in.midtermDays())
                .param("a", in.allowAfterExpiry()).param("d", in.daysAfterExpiry()).update();
        audit(brokerId, policyId, "POLICY_LEVEL", List.of(), old, in, actor);
        return in;
    }

    // ── employee-level windows ────────────────────────────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public List<OverrideIn> overrides(long brokerId, long policyId) {
        policy(brokerId, policyId);
        return jdbc.sql("SELECT employee_ref, enabled, start_date, end_date, close_date FROM policy_svc.enrollment_overrides WHERE policy_id = :p AND broker_id = :b ORDER BY employee_ref")
                .param("p", policyId).param("b", brokerId)
                .query((rs, n) -> new OverrideIn(rs.getString(1), rs.getBoolean(2), rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class), rs.getObject(5, LocalDate.class))).list();
    }

    /** Sets (or replaces) the window of each listed employee. All or nothing. */
    @Transactional
    public List<OverrideIn> putOverrides(long brokerId, long policyId, List<OverrideIn> in, String actor) {
        policy(brokerId, policyId);
        if (in == null || in.isEmpty() || in.size() > 5000) throw new ValidationFailedException(List.of(new Issue("EMPLOYEES", "Give between 1 and 5000 employees")));
        List<Issue> bad = new ArrayList<>();
        for (OverrideIn o : in) {
            String who = o.employeeRef();
            if (who == null || who.isBlank() || who.length() > 60) { bad.add(new Issue("EMPLOYEE_REF", "employeeRef is required (max 60 characters)")); continue; }
            if (o.start() == null || o.end() == null) bad.add(new Issue("WINDOW_DATES", who + ": start and end date are required"));
            else if (o.end().isBefore(o.start())) bad.add(new Issue("WINDOW_ORDER", who + ": the window cannot end before it starts"));
            else if (o.closeDate() != null && o.closeDate().isAfter(o.end())) bad.add(new Issue("CLOSE_DATE", who + ": the close date is after the window's end date"));   // legacy message: close date greater than end date
        }
        if (!bad.isEmpty()) throw new ValidationFailedException(bad);
        List<OverrideIn> old = overrides(brokerId, policyId).stream().filter(x -> in.stream().anyMatch(i -> i.employeeRef().equals(x.employeeRef()))).toList();
        for (OverrideIn o : in) {
            jdbc.sql("""
                    INSERT INTO policy_svc.enrollment_overrides(policy_id, employee_ref, broker_id, enabled, start_date, end_date, close_date, updated_at)
                    VALUES (:p, :r, :b, :en, :s, :e, :c, now())
                    ON CONFLICT (policy_id, employee_ref) DO UPDATE SET enabled = :en, start_date = :s, end_date = :e, close_date = :c, updated_at = now()""")
                    .param("p", policyId).param("r", o.employeeRef()).param("b", brokerId).param("en", o.enabled()).param("s", o.start()).param("e", o.end()).param("c", o.closeDate()).update();
        }
        audit(brokerId, policyId, in.size() == 1 ? "SINGLE_EMPLOYEE" : "MULTIPLE_EMPLOYEES", in.stream().map(OverrideIn::employeeRef).toList(), old, in, actor);
        return in;
    }

    @Transactional
    public void deleteOverride(long brokerId, long policyId, String employeeRef, String actor) {
        policy(brokerId, policyId);
        List<OverrideIn> old = overrides(brokerId, policyId).stream().filter(x -> x.employeeRef().equals(employeeRef)).toList();
        if (jdbc.sql("DELETE FROM policy_svc.enrollment_overrides WHERE policy_id = :p AND broker_id = :b AND employee_ref = :r")
                .param("p", policyId).param("b", brokerId).param("r", employeeRef).update() == 0) throw new NotFoundException("No custom window for that employee");
        audit(brokerId, policyId, "SINGLE_EMPLOYEE", List.of(employeeRef), old, null, actor);
    }

    // ── confirmation ──────────────────────────────────────────────────────────────────────────────────────────
    /** Freezes the window the employee confirmed under; later changes to the policy window no longer move it. */
    @Transactional
    public Result confirm(long brokerId, long policyId, String employeeRef, Employee facts, LocalDate today, String actor) {
        policy(brokerId, policyId);
        Result now = evaluate(brokerId, policyId, employeeRef, facts, today);
        if (now.status() == Status.CONFIRMED || now.status() == Status.CONFIRMED_BUT_NOT_OPTED) throw new com.broker.policy.api.ConflictException("Already confirmed");
        if (now.status() == Status.CLOSED) throw new com.broker.policy.api.ConflictException("The enrollment window is closed");
        jdbc.sql("INSERT INTO policy_svc.enrollment_confirmations(policy_id, employee_ref, broker_id, confirmed_at, start_date, end_date) VALUES (:p, :r, :b, now(), :s, :e)")
                .param("p", policyId).param("r", employeeRef).param("b", brokerId).param("s", now.start()).param("e", now.end()).update();
        audit(brokerId, policyId, "SINGLE_EMPLOYEE", List.of(employeeRef), Map.of("status", now.status()), Map.of("status", Status.CONFIRMED), actor);
        return evaluate(brokerId, policyId, employeeRef, facts, today);
    }

    @Transactional
    public void unconfirm(long brokerId, long policyId, String employeeRef, String actor) {
        policy(brokerId, policyId);
        if (jdbc.sql("DELETE FROM policy_svc.enrollment_confirmations WHERE policy_id = :p AND broker_id = :b AND employee_ref = :r")
                .param("p", policyId).param("b", brokerId).param("r", employeeRef).update() == 0) throw new NotFoundException("Not confirmed");
        audit(brokerId, policyId, "SINGLE_EMPLOYEE", List.of(employeeRef), Map.of("status", Status.CONFIRMED), Map.of("status", "UNCONFIRMED"), actor);
    }

    // ── evaluation ────────────────────────────────────────────────────────────────────────────────────────────
    /** `facts` carries what only the caller knows (dates, whether the employee has members); the stored override and confirmation are added here. */
    @Transactional(readOnly = true)
    public Result evaluate(long brokerId, long policyId, String employeeRef, Employee facts, LocalDate today) {
        Policy p = policy(brokerId, policyId);
        CustomWindow o = jdbc.sql("SELECT enabled, start_date, end_date, close_date FROM policy_svc.enrollment_overrides WHERE policy_id = :p AND broker_id = :b AND employee_ref = :r")
                .param("p", policyId).param("b", brokerId).param("r", employeeRef)
                .query((rs, n) -> new CustomWindow(rs.getBoolean(1), rs.getObject(2, LocalDate.class), rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class))).optional().orElse(null);
        Confirmation c = jdbc.sql("SELECT confirmed_at, start_date, end_date FROM policy_svc.enrollment_confirmations WHERE policy_id = :p AND broker_id = :b AND employee_ref = :r")
                .param("p", policyId).param("b", brokerId).param("r", employeeRef)
                .query((rs, n) -> new Confirmation(rs.getTimestamp(1).toInstant(), rs.getObject(2, LocalDate.class), rs.getObject(3, LocalDate.class))).optional().orElse(null);
        Employee e = new Employee(facts.dateOfJoining(), facts.dateOfUpload(), facts.hasMembers(), facts.inception(), o, c);
        return new EnrollmentWindowEvaluator(settings(brokerId, policyId), p.getStartDate(), p.getEndDate(), p.getMidtermBasis(), today).evaluate(e);
    }

    // ── audit ─────────────────────────────────────────────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public List<Audit> audit(long brokerId, long policyId, int limit) {
        policy(brokerId, policyId);
        return jdbc.sql("SELECT id, scope, employees::text, old_value::text, new_value::text, modified_by, at FROM policy_svc.enrollment_window_audit WHERE policy_id = :p AND broker_id = :b ORDER BY at DESC, id DESC LIMIT :l")
                .param("p", policyId).param("b", brokerId).param("l", Math.max(1, Math.min(limit, 500)))
                .query((rs, n) -> new Audit(rs.getLong(1), rs.getString(2), read(rs.getString(3), List.class), read(rs.getString(4), Object.class), read(rs.getString(5), Object.class), rs.getString(6), rs.getTimestamp(7).toInstant()))
                .list();
    }

    private void audit(long brokerId, long policyId, String scope, List<String> employees, Object oldV, Object newV, String actor) {
        jdbc.sql("INSERT INTO policy_svc.enrollment_window_audit(policy_id, broker_id, scope, employees, old_value, new_value, modified_by) VALUES (:p, :b, :s, CAST(:e AS jsonb), CAST(:o AS jsonb), CAST(:n AS jsonb), :a)")
                .param("p", policyId).param("b", brokerId).param("s", scope).param("e", write(employees)).param("o", oldV == null ? null : write(oldV)).param("n", newV == null ? null : write(newV))
                .param("a", actor == null ? "unknown" : actor).update();
    }

    private String write(Object o) { try { return json.writeValueAsString(o); } catch (Exception ex) { throw new IllegalStateException(ex); } }

    @SuppressWarnings("unchecked")
    private <T> T read(String s, Class<T> type) {
        if (s == null) return null;
        try { return json.readValue(s, type); } catch (Exception ex) { return null; }
    }
}
