package com.broker.policy.api;

import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** How legacy relation / premium-type / sum-insured-subtype ids translate to this system's ids, and where they do not (UNMAPPED / APPROXIMATE). */
@RestController
@RequestMapping("/api/v1/broker/lookups/legacy-ids")
public class LegacyIdController {
    public record Entry(String kind, int legacyId, String legacyCode, String legacyName, Integer newId, String status, String note) {}

    private static final Set<String> KINDS = Set.of("RELATION", "PREMIUM_TYPE", "SUMINSURED_SUBTYPE");
    private final JdbcClient jdbc;

    LegacyIdController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping
    List<Entry> list(@RequestParam(required = false) String kind) {
        if (kind != null && !KINDS.contains(kind)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "kind must be one of " + KINDS);
        String sql = "SELECT kind, legacy_id, legacy_code, legacy_name, new_id, status, note FROM policy_svc.legacy_id_map"
                + (kind == null ? "" : " WHERE kind = :kind") + " ORDER BY kind, legacy_id";
        var q = jdbc.sql(sql);
        if (kind != null) q = q.param("kind", kind);
        return q.query((rs, i) -> new Entry(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                rs.getObject(5) == null ? null : rs.getInt(5), rs.getString(6), rs.getString(7))).list();
    }
}
