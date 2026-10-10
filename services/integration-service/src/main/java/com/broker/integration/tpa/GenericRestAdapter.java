package com.broker.integration.tpa;

import com.broker.integration.domain.TpaConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reference adapter for a TPA that speaks plain JSON over HTTPS. This is OUR protocol, not any real TPA's: the legacy Care /
 * MediAssist / FHPL formats are not in the repository, so each needs its own {@link TpaAdapter} once the spec (or the legacy
 * code) is available.
 *
 * <pre>
 * POST {baseUrl}/enrollments   Idempotency-Key: &lt;key&gt;
 * { "operation": "ADD|UPDATE|REMOVE", "policy_id": 1, "members": [ {"ref": 11, "employee_code": ..., ...} ] }
 * 200 { "results": [ {"ref": 11, "status": "ACCEPTED", "tpa_member_id": "T-1"} | {"ref": 12, "status": "REJECTED", "reason": ".."} ] }
 * </pre>
 */
@Component
public class GenericRestAdapter implements TpaAdapter {
    private final ObjectMapper mapper;
    private final UrlGuard guard;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)   // a redirect could point inside our network
            .connectTimeout(Duration.ofSeconds(5)).build();

    GenericRestAdapter(ObjectMapper mapper, UrlGuard guard) {
        this.mapper = mapper;
        this.guard = guard;
    }

    @Override
    public TpaConfig.Adapter type() { return TpaConfig.Adapter.GENERIC_REST; }

    @Override
    public List<MemberResult> enroll(TpaConfig cfg, Credentials creds, SyncBatch batch) {
        URI base;
        try {
            base = guard.check(cfg.getBaseUrl());
        } catch (IllegalArgumentException e) {
            throw new TpaException("TPA base URL rejected: " + e.getMessage(), false);
        }
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(base.toString().replaceAll("/+$", "") + "/enrollments"))
                .timeout(Duration.ofMillis(cfg.getTimeoutMs()))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .header("Idempotency-Key", batch.idempotencyKey())
                .POST(HttpRequest.BodyPublishers.ofString(body(batch)));
        switch (cfg.getAuthType()) {
            case BASIC -> rb.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (creds.username() + ":" + creds.password()).getBytes(StandardCharsets.UTF_8)));
            case BEARER -> rb.header("Authorization", "Bearer " + creds.token());
            case NONE -> { }
        }
        HttpResponse<String> r;
        try {
            r = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TpaException("interrupted", true, e);
        } catch (IOException e) {
            throw new TpaException("TPA unreachable or timed out: " + e.getClass().getSimpleName(), true, e);   // no URL/credentials in the message
        }
        int s = r.statusCode();
        if (s == 408 || s == 429 || s >= 500) throw new TpaException("TPA answered HTTP " + s, true);
        if (s < 200 || s >= 300) throw new TpaException("TPA refused the request with HTTP " + s, false);
        return parse(r.body(), batch);
    }

    /**
     * GET {baseUrl}/active-lives?policy_number=P
     * 200 { "members": [ {"employee_code": "E1", "relation": "Self", "member_id": "T1", "member_name": "Asha Rao",
     *                     "dependents": [ {"relation": "Wife", "member_id": "T2", "member_name": "Meera Rao"} ]} ] }
     */
    @Override
    public List<Reconciler.ActiveLife> activeLives(TpaConfig cfg, Credentials creds, String policyNumber) {
        URI base;
        try {
            base = guard.check(cfg.getBaseUrl());
        } catch (IllegalArgumentException e) {
            throw new TpaException("TPA base URL rejected: " + e.getMessage(), false);
        }
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(base.toString().replaceAll("/+$", "") + "/active-lives?policy_number="
                        + java.net.URLEncoder.encode(policyNumber, StandardCharsets.UTF_8)))
                .timeout(Duration.ofMillis(cfg.getTimeoutMs())).header("Accept", "application/json").GET();
        switch (cfg.getAuthType()) {
            case BASIC -> rb.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((creds.username() + ":" + creds.password()).getBytes(StandardCharsets.UTF_8)));
            case BEARER -> rb.header("Authorization", "Bearer " + creds.token());
            case NONE -> { }
        }
        HttpResponse<String> r;
        try {
            r = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TpaException("interrupted", true, e);
        } catch (IOException e) {
            throw new TpaException("TPA unreachable or timed out: " + e.getClass().getSimpleName(), true, e);
        }
        int s = r.statusCode();
        if (s == 408 || s == 429 || s >= 500) throw new TpaException("TPA answered HTTP " + s, true);
        if (s < 200 || s >= 300) throw new TpaException("TPA refused the request with HTTP " + s, false);
        try {
            JsonNode members = mapper.readTree(r.body()).path("members");
            if (!members.isArray()) throw new TpaException("TPA response has no members array", false);
            List<Reconciler.ActiveLife> out = new ArrayList<>();
            for (JsonNode n : members) {
                List<Reconciler.ActiveLife> deps = new ArrayList<>();
                for (JsonNode d : n.path("dependents")) deps.add(new Reconciler.ActiveLife(null, d.path("relation").asString(""), d.path("member_id").asString(""), d.path("member_name").asString("")));
                out.add(new Reconciler.ActiveLife(n.path("employee_code").asString(""), n.path("relation").asString(""), n.path("member_id").asString(""),
                        n.path("member_name").asString(""), deps));
            }
            return out;
        } catch (TpaException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new TpaException("TPA response was not valid JSON", false, e);
        }
    }

    private String body(SyncBatch batch) {
        List<Map<String, Object>> members = new ArrayList<>();
        for (RosterMember m : batch.members()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("ref", m.id());
            o.put("employee_code", m.employeeCode());
            o.put("relation", m.relationCode());
            o.put("first_name", m.firstName());
            o.put("last_name", m.lastName());
            o.put("date_of_birth", m.dateOfBirth());
            o.put("gender", m.gender());
            o.put("email", m.email());
            o.put("mobile", m.mobile());
            o.put("sum_insured", m.sumInsured());
            o.put("effective_date", m.effectiveDate());
            o.put("end_date", m.endDate());
            o.put("active", m.active());
            members.add(o);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("operation", batch.operation());
        root.put("policy_id", batch.policyId());
        root.put("members", members);
        return mapper.writeValueAsString(root);
    }

    private List<MemberResult> parse(String json, SyncBatch batch) {
        Map<Long, MemberResult> byRef = new HashMap<>();
        try {
            JsonNode results = mapper.readTree(json).path("results");
            if (!results.isArray()) throw new TpaException("TPA response has no results array", false);
            for (JsonNode n : results) {
                long ref = n.path("ref").asLong(-1);
                boolean ok = "ACCEPTED".equals(n.path("status").asString(""));
                String tpaId = n.path("tpa_member_id").asString("");
                byRef.put(ref, ok && !tpaId.isBlank() ? new MemberResult(ref, true, tpaId, null)
                        : new MemberResult(ref, false, null, ok ? "accepted without a tpa_member_id" : n.path("reason").asString("rejected by TPA")));
            }
        } catch (TpaException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new TpaException("TPA response was not valid JSON", false, e);
        }
        List<MemberResult> out = new ArrayList<>();
        for (RosterMember m : batch.members()) {      // a member the TPA did not mention must not look synced
            out.add(byRef.getOrDefault(m.id(), new MemberResult(m.id(), false, null, "TPA returned no result for this member")));
        }
        return out;
    }
}
