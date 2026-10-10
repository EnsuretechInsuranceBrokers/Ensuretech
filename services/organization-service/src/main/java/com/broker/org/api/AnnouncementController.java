package com.broker.org.api;

import com.broker.org.security.TenantContext;
import com.broker.org.security.TenantContextHolder;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Broker announcements shown to employers' HR users. The broker manages them; an employer reads only what is live and meant for it. */
@RestController
public class AnnouncementController {
    public record AnnouncementRequest(
            @NotBlank @Pattern(regexp = "BANNER|CAROUSEL|NOTICE") String kind,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 5000) String content,
            @Size(max = 500) @Pattern(regexp = "^$|https?://.+", message = "must be an http(s) link") String link,
            @Size(max = 500) @Pattern(regexp = "^$|https?://.+", message = "must be an http(s) link") String imageUrl,
            @Pattern(regexp = "TOP|BOTTOM|SIDEBAR") String position,
            Integer sequence, Boolean allEmployers, List<Long> employerIds, LocalDate startsOn,
            @Min(1) @Max(3650) Integer ttlDays, Boolean active) {}

    public record AnnouncementResponse(long id, String kind, String title, String content, String link, String imageUrl, String position, int sequence,
                                       boolean allEmployers, List<Long> employerIds, LocalDate startsOn, Integer ttlDays, LocalDate endsOn, boolean active, boolean live) {}

    /** Live = switched on, started, and not past its time to live. */
    static boolean live(boolean active, LocalDate startsOn, Integer ttlDays, LocalDate today) {
        if (!active || today.isBefore(startsOn)) return false;
        return ttlDays == null || !today.isAfter(startsOn.plusDays(ttlDays - 1L));
    }

    private final JdbcClient jdbc;

    AnnouncementController(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static long broker() { return TenantContextHolder.requireBrokerId(); }

    private AnnouncementResponse row(java.sql.ResultSet rs, List<Long> employers) throws java.sql.SQLException {
        LocalDate start = rs.getObject("starts_on", LocalDate.class);
        Integer ttl = (Integer) rs.getObject("ttl_days");
        boolean active = rs.getBoolean("active");
        return new AnnouncementResponse(rs.getLong("id"), rs.getString("kind"), rs.getString("title"), rs.getString("content"), rs.getString("link"), rs.getString("image_url"),
                rs.getString("position"), rs.getInt("sequence"), rs.getBoolean("all_employers"), employers, start, ttl, ttl == null ? null : start.plusDays(ttl - 1L), active, live(active, start, ttl, LocalDate.now()));
    }

    private List<Long> targets(long id) {
        return jdbc.sql("SELECT employer_id FROM organization.announcement_employers WHERE announcement_id = :a ORDER BY employer_id").param("a", id).query(Long.class).list();
    }

    // ── broker ────────────────────────────────────────────────────────────────────────────────────────────────
    @GetMapping("/api/v1/broker/announcements")
    List<AnnouncementResponse> list() {
        return jdbc.sql("SELECT * FROM organization.announcements WHERE broker_id = :b ORDER BY active DESC, sequence, id DESC").param("b", broker())
                .query((rs, n) -> row(rs, targets(rs.getLong("id")))).list();
    }

    @PostMapping("/api/v1/broker/announcements")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    @Transactional
    public AnnouncementResponse create(@jakarta.validation.Valid @RequestBody AnnouncementRequest r) {
        long b = broker();
        boolean all = r.allEmployers() == null || r.allEmployers();
        List<Long> ids = checkTargets(b, all, r.employerIds());
        long id = jdbc.sql("""
                INSERT INTO organization.announcements(broker_id, kind, title, content, link, image_url, position, sequence, all_employers, starts_on, ttl_days, active, created_by)
                VALUES (:b, :k, :t, :c, :l, :i, :p, :s, :a, :st, :ttl, :act, :u) RETURNING id""")
                .param("b", b).param("k", r.kind()).param("t", r.title()).param("c", r.content()).param("l", blank(r.link())).param("i", blank(r.imageUrl()))
                .param("p", r.position() == null ? "TOP" : r.position()).param("s", r.sequence() == null ? 0 : r.sequence()).param("a", all)
                .param("st", r.startsOn() == null ? LocalDate.now() : r.startsOn()).param("ttl", r.ttlDays()).param("act", r.active() == null || r.active())
                .param("u", TenantContextHolder.require().userId()).query(Long.class).single();
        setTargets(id, ids);
        return get(b, id);
    }

    @PutMapping("/api/v1/broker/announcements/{id}")
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    @Transactional
    public AnnouncementResponse update(@PathVariable long id, @jakarta.validation.Valid @RequestBody AnnouncementRequest r) {
        long b = broker();
        get(b, id);                                                                 // 404 for another broker's
        boolean all = r.allEmployers() == null || r.allEmployers();
        List<Long> ids = checkTargets(b, all, r.employerIds());
        jdbc.sql("""
                UPDATE organization.announcements SET kind = :k, title = :t, content = :c, link = :l, image_url = :i, position = :p, sequence = :s, all_employers = :a,
                       starts_on = :st, ttl_days = :ttl, active = :act, updated_at = now() WHERE id = :id AND broker_id = :b""")
                .param("id", id).param("b", b).param("k", r.kind()).param("t", r.title()).param("c", r.content()).param("l", blank(r.link())).param("i", blank(r.imageUrl()))
                .param("p", r.position() == null ? "TOP" : r.position()).param("s", r.sequence() == null ? 0 : r.sequence()).param("a", all)
                .param("st", r.startsOn() == null ? LocalDate.now() : r.startsOn()).param("ttl", r.ttlDays()).param("act", r.active() == null || r.active()).update();
        setTargets(id, ids);
        return get(b, id);
    }

    /** Retire (legacy soft delete): it stops showing, the record stays. */
    @DeleteMapping("/api/v1/broker/announcements/{id}")
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    void retire(@PathVariable long id) {
        if (jdbc.sql("UPDATE organization.announcements SET active = FALSE, updated_at = now() WHERE id = :id AND broker_id = :b").param("id", id).param("b", broker()).update() == 0)
            throw new NotFoundException("Announcement not found");
    }

    // ── employer ──────────────────────────────────────────────────────────────────────────────────────────────
    @GetMapping("/api/v1/employer/announcements")
    List<AnnouncementResponse> forMe() {
        TenantContext t = TenantContextHolder.require();
        if (t.brokerId() == null || t.employerId() == null) throw new ForbiddenTenantException("Caller is not employer-scoped");
        return jdbc.sql("""
                SELECT a.* FROM organization.announcements a
                WHERE a.broker_id = :b AND a.active
                  AND (a.all_employers OR EXISTS (SELECT 1 FROM organization.announcement_employers x WHERE x.announcement_id = a.id AND x.employer_id = :e))
                ORDER BY a.sequence, a.id DESC""").param("b", t.brokerId()).param("e", t.employerId())
                .query((rs, n) -> row(rs, List.of())).list().stream().filter(AnnouncementResponse::live)
                .map(a -> new AnnouncementResponse(a.id(), a.kind(), a.title(), a.content(), a.link(), a.imageUrl(), a.position(), a.sequence(), a.allEmployers(), List.of(),   // an employer never sees who else is targeted
                        a.startsOn(), a.ttlDays(), a.endsOn(), a.active(), a.live())).toList();
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────────────────
    private AnnouncementResponse get(long brokerId, long id) {
        return jdbc.sql("SELECT * FROM organization.announcements WHERE id = :id AND broker_id = :b").param("id", id).param("b", brokerId)
                .query((rs, n) -> row(rs, targets(id))).optional().orElseThrow(() -> new NotFoundException("Announcement not found"));
    }

    private List<Long> checkTargets(long brokerId, boolean all, List<Long> ids) {
        if (all) return List.of();
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("Name at least one employer, or send it to all employers");
        List<Long> distinct = ids.stream().distinct().toList();
        if (distinct.size() > 1000) throw new IllegalArgumentException("Too many employers (max 1000)");
        long found = jdbc.sql("SELECT count(*) FROM organization.employers WHERE broker_id = :b AND id IN (:ids)").param("b", brokerId).param("ids", distinct).query(Long.class).single();
        if (found != distinct.size()) throw new NotFoundException("One or more employers were not found");   // same answer for another broker's employer
        return distinct;
    }

    private void setTargets(long id, List<Long> ids) {
        jdbc.sql("DELETE FROM organization.announcement_employers WHERE announcement_id = :a").param("a", id).update();
        for (Long e : ids) jdbc.sql("INSERT INTO organization.announcement_employers(announcement_id, employer_id) VALUES (:a, :e)").param("a", id).param("e", e).update();
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s; }
}
