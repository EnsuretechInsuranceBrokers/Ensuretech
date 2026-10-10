package com.broker.integration.sync;

import com.broker.integration.api.ConflictException;
import com.broker.integration.api.NotFoundException;
import com.broker.integration.domain.PolicyRoute;
import com.broker.integration.domain.PolicyRouteRepository;
import com.broker.integration.engine.EngineClient;
import com.broker.integration.engine.EngineException;
import com.broker.integration.outbox.OutboxPublisher;
import com.broker.integration.tpa.OfflineMatcher;
import com.broker.integration.tpa.OfflineSheet;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * The offline route to TPA member ids and e-cards: when the TPA's server is down (or the TPA simply works in Excel) the TPA uploads a sheet itself, or emails it
 * and the broker uploads it for the TPA. The nightly pull and this route end the same way (the same matching, the same roster fields), but this one answers
 * in the same request instead of by e-mail two days later, because every hour a member waits for an id is an hour of pro-rata ticking.
 */
@Service
public class OfflineMemberService {
    static final int PAGE = 500, MAX_ROSTER = 200_000;

    /** Who is asking. A broker sees its own policies; a TPA user sees the policies routed to its TPA (within one broker when its token names one). */
    public record Scope(String userId, String role, Long brokerId, Long tpaId) {
        boolean isTpa() { return "TPA_USER".equals(role); }
        String source() { return isTpa() ? "TPA_PORTAL" : "BROKER_FOR_TPA"; }
    }

    public record Waiting(long id, String employeeCode, String relationCode, String name, String coverStart, String endorsementRef, String tpaMemberId, String ecardUrl,
                          String endorsedAt, double hoursWaiting, String urgency) {}

    public record PolicyWaiting(long policyId, String policyNumber, short tpaId, long employerId, int pendingIds, int pendingEcards, double oldestHours, String urgency) {}

    public record Board(List<PolicyWaiting> policies, int totalPendingIds, int totalPendingEcards, double oldestHours) {}

    public record PolicyDetail(long policyId, String policyNumber, short tpaId, String policyStart, String policyEnd, boolean inception, int pendingIds, int pendingEcards,
                               double oldestHours, String urgency, List<Waiting> members, Long nextAfter) {}

    public record LineResult(int line, String employeeCode, String relation, String tpaMemberId, String tpaMemberName, String ecardUrl, String outcome, String reasonCode,
                             String reason, Long rosterId, String previousTpaMemberId) {}

    public record Report(UUID uploadId, boolean applied, long policyId, short tpaId, String source, String filename, String sha256, int rowsTotal, int assigned, int reassigned,
                         int updated, int ecardsAdded, int unchanged, int rejected, Double longestWaitHours, List<String> warnings, List<LineResult> lines, long tookMs) {}

    public record UploadSummary(UUID id, long policyId, short tpaId, String source, String uploadedBy, String uploadedByRole, String filename, String status, int rowsTotal, int assigned,
                                int reassigned, int ecardsAdded, int unchanged, int rejected, Double longestWaitHours, String createdAt) {}

    private final PolicyRouteRepository routes;
    private final EngineClient engine;
    private final JdbcClient jdbc;
    private final OutboxPublisher outbox;
    private final TransactionTemplate tx;
    private final double urgentHours, criticalHours;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    OfflineMemberService(PolicyRouteRepository routes, EngineClient engine, JdbcClient jdbc, OutboxPublisher outbox, PlatformTransactionManager tm, io.micrometer.core.instrument.MeterRegistry meters,
                         @Value("${app.offline.urgent-hours:4}") double urgentHours, @Value("${app.offline.critical-hours:24}") double criticalHours) {
        this.routes = routes; this.engine = engine; this.jdbc = jdbc; this.outbox = outbox; this.tx = new TransactionTemplate(tm);
        this.urgentHours = urgentHours; this.criticalHours = criticalHours; this.meters = meters;
    }

    // ── who may see which policy ───────────────────────────────────────────────────────────────────────────────────────────────
    PolicyRoute route(Scope s, long policyId) {
        PolicyRoute r = routes.findById(policyId).orElseThrow(() -> new NotFoundException("No such policy"));
        boolean ok = s.isTpa() ? r.getTpaId() != null && r.getTpaId().equals(s.tpaId() == null ? null : s.tpaId().shortValue()) && (s.brokerId() == null || s.brokerId().equals(r.getBrokerId()))
                : s.brokerId() != null && s.brokerId().equals(r.getBrokerId());
        if (!ok) throw new NotFoundException("No such policy");                                  // never reveal that it exists for someone else
        if (r.getTpaId() == null) throw new ConflictException("This policy has no TPA, so there are no TPA member ids to receive.");
        return r;
    }

    String urgency(double hours) { return hours >= criticalHours ? "CRITICAL" : hours >= urgentHours ? "HIGH" : "NORMAL"; }

    static double hoursSince(String iso, Instant now) {
        if (iso == null) return 0;
        try { return Math.max(0, Duration.between(Instant.parse(iso.replace("+00:00", "Z")), now).toSeconds() / 3600.0); } catch (RuntimeException e) { return 0; }
    }

    private static double round1(double v) { return Math.round(v * 10) / 10.0; }

    // ── the board: who is still waiting, across every policy this caller can see ─────────────────────────────────────────────────
    public Board board(Scope s) {
        List<PolicyRoute> mine = s.isTpa()
                ? (s.brokerId() == null ? routes.findByTpaId(s.tpaId().shortValue()) : routes.findByTpaId(s.tpaId().shortValue()).stream().filter(r -> r.getBrokerId().equals(s.brokerId())).toList())
                : routes.findByBrokerId(s.brokerId()).stream().filter(r -> r.getTpaId() != null).toList();
        Map<Long, PolicyRoute> byId = new LinkedHashMap<>();
        mine.forEach(r -> byId.put(r.getPolicyId(), r));
        Instant now = Instant.now();
        List<PolicyWaiting> out = new ArrayList<>();
        int ids = 0, cards = 0;
        double oldest = 0;
        for (EngineClient.Pending p : engine.pendingSummary(new ArrayList<>(byId.keySet()))) {
            PolicyRoute r = byId.get(p.policyId());
            double h = Math.max(hoursSince(p.oldestIdWaitingSince(), now), hoursSince(p.oldestEcardWaitingSince(), now));
            out.add(new PolicyWaiting(p.policyId(), policyNumber(p.policyId()), r.getTpaId(), r.getEmployerId(), p.pendingIds(), p.pendingEcards(), round1(h), urgency(h)));
            ids += p.pendingIds(); cards += p.pendingEcards(); oldest = Math.max(oldest, h);
        }
        out.sort((a, b) -> Double.compare(b.oldestHours(), a.oldestHours()));                    // the one waiting longest first
        return new Board(out, ids, cards, round1(oldest));
    }

    private String policyNumber(long policyId) {
        try { return engine.policyMembersDetailed(policyId, Long.MAX_VALUE - 1, 1, null).policyNumber(); } catch (EngineException e) { return null; }
    }

    public PolicyDetail detail(Scope s, long policyId, long after, int limit) {
        PolicyRoute r = route(s, policyId);
        EngineClient.DetailedPage p = engine.policyMembersDetailed(policyId, after, Math.min(Math.max(limit, 1), 1000), "any");
        Instant now = Instant.now();
        List<Waiting> out = new ArrayList<>();
        int ids = 0, cards = 0;
        double oldest = 0;
        for (OfflineMatcher.Member m : p.members()) {
            double h = hoursSince(m.endorsedAt(), now);
            boolean noId = m.tpaMemberId() == null;
            if (noId) { ids++; oldest = Math.max(oldest, h); } else cards++;
            out.add(new Waiting(m.id(), m.employeeCode(), m.relationCode(), (m.firstName() + " " + (m.lastName() == null ? "" : m.lastName())).trim(), m.effectiveDate(), m.endorsementRef(),
                    m.tpaMemberId(), m.ecardUrl(), m.endorsedAt(), round1(h), noId ? urgency(h) : "NORMAL"));
        }
        return new PolicyDetail(policyId, p.policyNumber(), r.getTpaId(), p.policyStart(), p.policyEnd(), p.inception(), ids, cards, round1(oldest), urgency(oldest), out, p.nextAfter());
    }

    private List<OfflineMatcher.Member> roster(long policyId, String pending) {
        List<OfflineMatcher.Member> all = new ArrayList<>();
        long after = 0;
        while (true) {
            EngineClient.DetailedPage page = engine.policyMembersDetailed(policyId, after, PAGE, pending);
            all.addAll(page.members());
            if (all.size() > MAX_ROSTER) throw new ConflictException("The policy has more than " + MAX_ROSTER + " active members.");
            if (page.nextAfter() == null) return all;
            after = page.nextAfter();
        }
    }

    // ── the sheet to send the TPA: who we are waiting on, already filled in, so all it needs is the ids ────────────────────────────
    public byte[] template(Scope s, long policyId) {
        PolicyRoute r = route(s, policyId);
        List<OfflineMatcher.Member> waiting = roster(policyId, "any");
        waiting.sort((a, b) -> a.employeeCode().compareToIgnoreCase(b.employeeCode()) != 0 ? a.employeeCode().compareToIgnoreCase(b.employeeCode()) : a.relationCode().compareTo(b.relationCode()));
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Members");
            CellStyle head = wb.createCellStyle(), ours = wb.createCellStyle();
            Font bold = wb.createFont(); bold.setBold(true); head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex()); head.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            ours.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex()); ours.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            String[] cols = {"Employee code", "Relationship with employee", "Name on our records", "Cover start date", "Endorsement ref", "TPA member id", "TPA member name", "E-card URL"};
            Row h = sh.createRow(0);
            for (int c = 0; c < cols.length; c++) { h.createCell(c).setCellValue(cols[c]); h.getCell(c).setCellStyle(head); }
            int rn = 1;
            for (OfflineMatcher.Member m : waiting) {
                Row row = sh.createRow(rn++);
                row.createCell(0).setCellValue(m.employeeCode()); row.createCell(1).setCellValue(m.relationCode().replace('_', ' ').toLowerCase(Locale.ROOT));
                row.createCell(2).setCellValue((m.firstName() + " " + (m.lastName() == null ? "" : m.lastName())).trim()); row.createCell(3).setCellValue(m.effectiveDate());
                row.createCell(4).setCellValue(m.endorsementRef() == null ? "" : m.endorsementRef());
                if (m.tpaMemberId() != null) row.createCell(5).setCellValue(m.tpaMemberId());
                if (m.tpaMemberName() != null) row.createCell(6).setCellValue(m.tpaMemberName());
                if (m.ecardUrl() != null) row.createCell(7).setCellValue(m.ecardUrl());
                for (int c = 0; c < 5; c++) row.getCell(c).setCellStyle(ours);
            }
            int[] widths = {16, 26, 28, 16, 16, 22, 28, 50};
            for (int c = 0; c < widths.length; c++) sh.setColumnWidth(c, widths[c] * 256);
            sh.createFreezePane(0, 1);
            sh.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(1, rn - 1), 0, cols.length - 1));
            Sheet info = wb.createSheet("Instructions");
            String[] lines = {"Ensuretech: TPA member ids and e-cards", "Policy " + policyId + " · " + waiting.size() + " member(s) waiting for an id or an e-card",
                    "Grey columns are ours: do not change them. Fill the yellow columns: TPA member id (required), TPA member name (if it differs from ours), E-card URL (a full https link).",
                    "Rows you leave empty are ignored. Each line is checked on its own: a mistake on one line never holds up the others, and the answer lists every rejected line by its number.",
                    "Keep the Cover start date and Endorsement ref columns as they are: a line whose cover start date or endorsement differs from ours is rejected until it is settled."};
            for (int i = 0; i < lines.length; i++) info.createRow(i).createCell(0).setCellValue(lines[i]);
            info.setColumnWidth(0, 130 * 256);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── the upload ─────────────────────────────────────────────────────────────────────────────────────────────────────────────
    public Report process(Scope s, long policyId, byte[] data, String filename, boolean apply, boolean ignoreCoverDate) {
        long t0 = System.nanoTime();
        PolicyRoute r = route(s, policyId);
        short tpa = r.getTpaId();
        OfflineSheet.Parsed sheet;
        try { sheet = OfflineSheet.parse(data, filename); }
        catch (OfflineSheet.SheetException e) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage()); }
        boolean strip = Boolean.TRUE.equals(jdbc.sql("SELECT strip_leading_zeros FROM integration.tpa_reconcile_settings WHERE broker_id=:b AND tpa_id=:t")
                .param("b", r.getBrokerId()).param("t", tpa).query(Boolean.class).optional().orElse(false));
        Map<String, String> labels = new LinkedHashMap<>();
        jdbc.sql("SELECT tpa_label, relation_code FROM integration.tpa_relation_mappings WHERE broker_id=:b AND tpa_id=:t").param("b", r.getBrokerId()).param("t", tpa)
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getString(2))).list().forEach(e -> labels.put(e.getKey(), e.getValue()));
        List<OfflineMatcher.Member> roster;
        try { roster = roster(policyId, null); }
        catch (EngineException e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not read the policy's members just now: " + e.getMessage()); }
        List<OfflineMatcher.Input> inputs = new ArrayList<>();
        for (OfflineSheet.Line l : sheet.lines()) {
            inputs.add(new OfflineMatcher.Input(l.line(), l.get(OfflineSheet.Col.EMPLOYEE_CODE), l.get(OfflineSheet.Col.RELATION), l.get(OfflineSheet.Col.TPA_MEMBER_ID),
                    l.get(OfflineSheet.Col.TPA_MEMBER_NAME), l.get(OfflineSheet.Col.ECARD_URL), l.get(OfflineSheet.Col.COVER_START), l.get(OfflineSheet.Col.ENDORSEMENT_REF)));
        }
        List<OfflineMatcher.Verdict> verdicts = OfflineMatcher.match(inputs, roster, new OfflineMatcher.Options(strip, labels, ignoreCoverDate));
        Instant now = Instant.now();
        int assigned = 0, reassigned = 0, updated = 0, ecards = 0, unchanged = 0, rejected = 0;
        double longest = 0;
        List<EngineClient.AssignItem> toApply = new ArrayList<>();
        for (OfflineMatcher.Verdict v : verdicts) {
            switch (v.outcome()) {
                case ASSIGNED -> { assigned++; longest = Math.max(longest, hoursSince(v.member().endorsedAt(), now)); }
                case REASSIGNED -> reassigned++;
                case UPDATED -> updated++;
                case UNCHANGED -> unchanged++;
                case REJECTED -> rejected++;
            }
            if (v.accepted() && v.outcome() != OfflineMatcher.Outcome.UNCHANGED) {
                if (v.in().ecardUrl() != null && !v.in().ecardUrl().isBlank() && !v.in().ecardUrl().trim().equals(v.member().ecardUrl())) ecards++;
                toApply.add(new EngineClient.AssignItem(v.member().id(), blankToNull(v.in().tpaMemberId()), blankToNull(v.in().tpaMemberName()), blankToNull(v.in().ecardUrl())));
            }
        }
        String sha = sha256(data);
        UUID id = null;
        if (apply) {
            try {
                for (int i = 0; i < toApply.size(); i += PAGE) engine.assign(policyId, s.source(), toApply.subList(i, Math.min(toApply.size(), i + PAGE)));
            } catch (EngineException e) {
                store(s, r, filename, sha, "FAILED", verdicts, 0, 0, 0, 0, 0, null, e.getMessage(), false);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The member ids could not be saved just now; some lines may already have been saved, so upload the same sheet again to finish. Detail: " + e.getMessage());
            }
            id = store(s, r, filename, sha, "APPLIED", verdicts, assigned, reassigned, ecards, unchanged, rejected, longest, null, true);
        }
        List<LineResult> lines = new ArrayList<>();
        for (OfflineMatcher.Verdict v : verdicts) lines.add(line(v));
        if (apply) for (OfflineMatcher.Verdict v : verdicts) meters.counter("ensuretech.offline.lines", "outcome", v.outcome().name(), "source", s.source()).increment();
        long took = System.nanoTime() - t0;
        meters.timer("ensuretech.offline.upload", "mode", apply ? "apply" : "preview", "source", s.source()).record(took, java.util.concurrent.TimeUnit.NANOSECONDS);
        return new Report(id, apply, policyId, tpa, s.source(), filename, sha, verdicts.size(), assigned, reassigned, updated, ecards, unchanged, rejected,
                assigned > 0 ? round1(longest) : null, sheet.warnings(), lines, (System.nanoTime() - t0) / 1_000_000);
    }

    private static String blankToNull(String v) { return v == null || v.isBlank() ? null : v.trim(); }

    private static LineResult line(OfflineMatcher.Verdict v) {
        return new LineResult(v.in().line(), v.in().employeeCode(), v.in().relation(), v.in().tpaMemberId(), v.in().tpaMemberName(), v.in().ecardUrl(), v.outcome().name(), v.reasonCode(),
                v.reason(), v.member() == null ? null : v.member().id(), v.previousTpaMemberId());
    }

    private UUID store(Scope s, PolicyRoute r, String filename, String sha, String status, List<OfflineMatcher.Verdict> verdicts, int assigned, int reassigned, int ecards,
                       int unchanged, int rejected, Double longest, String error, boolean publish) {
        return tx.execute(st -> {
            UUID id = jdbc.sql("""
                    INSERT INTO integration.offline_uploads (broker_id, policy_id, tpa_id, source, uploaded_by, uploaded_by_role, filename, sha256, status, rows_total, assigned, reassigned,
                                                            ecards_added, unchanged, rejected, waited_hours_max, error)
                    VALUES (:b,:p,:t,:src,:u,:role,:f,:sha,:st,:n,:a,:re,:e,:un,:rj,:w,:err) RETURNING id""")
                    .param("b", r.getBrokerId()).param("p", r.getPolicyId()).param("t", r.getTpaId()).param("src", s.source()).param("u", String.valueOf(s.userId())).param("role", s.role())
                    .param("f", filename == null ? "sheet" : filename.substring(0, Math.min(255, filename.length()))).param("sha", sha).param("st", status).param("n", verdicts.size())
                    .param("a", assigned).param("re", reassigned).param("e", ecards).param("un", unchanged).param("rj", rejected).param("w", longest == null || assigned == 0 ? null : round1(longest))
                    .param("err", error == null ? null : error.substring(0, Math.min(500, error.length()))).query(UUID.class).single();
            for (OfflineMatcher.Verdict v : verdicts) {
                jdbc.sql("""
                        INSERT INTO integration.offline_upload_rows (upload_id, line, employee_code, relation, tpa_member_id, tpa_member_name, ecard_url, cover_start_date, endorsement_ref, outcome,
                                                                     reason_code, reason, roster_id, previous_tpa_member_id)
                        VALUES (:u,:l,:c,:rel,:tid,:tn,:ec,:cs,:er,:o,:rc,:rs,:rid,:prev)""")
                        .param("u", id).param("l", v.in().line()).param("c", cut(v.in().employeeCode(), 80)).param("rel", cut(v.in().relation(), 80)).param("tid", cut(v.in().tpaMemberId(), 80))
                        .param("tn", cut(v.in().tpaMemberName(), 200)).param("ec", cut(v.in().ecardUrl(), 500)).param("cs", cut(v.in().coverStart(), 40)).param("er", cut(v.in().endorsementRef(), 80))
                        .param("o", v.outcome().name()).param("rc", v.reasonCode()).param("rs", cut(v.reason(), 300)).param("rid", v.member() == null ? null : v.member().id())
                        .param("prev", cut(v.previousTpaMemberId(), 80)).update();
            }
            if (publish && (assigned > 0 || reassigned > 0 || ecards > 0)) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("upload_id", id.toString()); body.put("broker_id", r.getBrokerId()); body.put("employer_id", r.getEmployerId()); body.put("policy_id", r.getPolicyId());
                body.put("tpa_id", r.getTpaId()); body.put("source", s.source()); body.put("uploaded_by", s.userId()); body.put("assigned", assigned); body.put("reassigned", reassigned);
                body.put("ecards_added", ecards); body.put("rejected", rejected); body.put("longest_wait_hours", longest == null ? null : round1(longest));
                outbox.publish("OfflineUpload", id, "TpaMemberIdsReceived", SyncService.TOPIC, String.valueOf(r.getBrokerId()), body);
            }
            return id;
        });
    }

    private static String cut(String v, int n) { return v == null ? null : v.length() <= n ? v : v.substring(0, n); }

    private static String sha256(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    // ── history ────────────────────────────────────────────────────────────────────────────────────────────────────────────────
    public List<UploadSummary> uploads(Scope s, Long policyId) {
        if (policyId != null) route(s, policyId);
        String where = s.isTpa() ? "tpa_id=:t" + (s.brokerId() == null ? "" : " AND broker_id=:b") : "broker_id=:b";
        var q = jdbc.sql("SELECT id, policy_id, tpa_id, source, uploaded_by, uploaded_by_role, filename, status, rows_total, assigned, reassigned, ecards_added, unchanged, rejected, waited_hours_max, created_at "
                + "FROM integration.offline_uploads WHERE " + where + (policyId == null ? "" : " AND policy_id=:p") + " ORDER BY created_at DESC LIMIT 100");
        if (s.isTpa()) q = q.param("t", s.tpaId().shortValue());
        if (!s.isTpa() || s.brokerId() != null) q = q.param("b", s.brokerId());
        if (policyId != null) q = q.param("p", policyId);
        return q.query((rs, i) -> new UploadSummary(rs.getObject(1, UUID.class), rs.getLong(2), rs.getShort(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getInt(9), rs.getInt(10), rs.getInt(11), rs.getInt(12), rs.getInt(13), rs.getInt(14), rs.getObject(15) == null ? null : rs.getDouble(15), rs.getTimestamp(16).toInstant().toString())).list();
    }

    public Map<String, Object> upload(Scope s, UUID id, boolean rejectedOnly) {
        UploadSummary u = uploads(s, null).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow(() -> new NotFoundException("No such upload"));
        route(s, u.policyId());
        List<LineResult> lines = jdbc.sql("SELECT line, employee_code, relation, tpa_member_id, tpa_member_name, ecard_url, outcome, reason_code, reason, roster_id, previous_tpa_member_id "
                + "FROM integration.offline_upload_rows WHERE upload_id=:u" + (rejectedOnly ? " AND outcome='REJECTED'" : "") + " ORDER BY line").param("u", id)
                .query((rs, i) -> new LineResult(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getObject(10) == null ? null : rs.getLong(10), rs.getString(11))).list();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("upload", u); out.put("lines", lines);
        return out;
    }
}
