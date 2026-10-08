package com.broker.integration.engine;

import com.broker.integration.tpa.TpaAdapter.RosterMember;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The endorsement engine's SYSTEM-only internal API: which roster rows a job changed, and where to record TPA member ids. */
@Component
public class EngineClient {
    public record Page(String opType, List<RosterMember> members, Long nextAfter) {}

    private final String base;
    private final int timeoutMs;
    private final ServiceTokenProvider tokens;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(3)).build();

    EngineClient(@Value("${app.engine.base-url}") String base, @Value("${app.engine.timeout-ms:5000}") int timeoutMs,
                 ServiceTokenProvider tokens, ObjectMapper mapper) {
        this.base = base.replaceAll("/+$", ""); this.timeoutMs = timeoutMs; this.tokens = tokens; this.mapper = mapper;
    }

    public Page members(UUID jobId, long after, int limit) {
        JsonNode n = send(HttpRequest.newBuilder(URI.create(base + "/api/v1/internal/endorsements/" + jobId + "/members/?after=" + after + "&limit=" + limit)).GET());
        List<RosterMember> out = new ArrayList<>();
        for (JsonNode m : n.path("members")) {
            out.add(new RosterMember(m.path("id").asLong(), m.path("employee_code").asString(""), m.path("relation_code").asString(""),
                    m.path("first_name").asString(""), m.path("last_name").asString(""), m.path("date_of_birth").asString(""),
                    nullable(m, "gender"), nullable(m, "email"), nullable(m, "mobile"), m.path("sum_insured").asString(""),
                    m.path("effective_date").asString(""), nullable(m, "end_date"), m.path("is_active").asBoolean(true)));
        }
        JsonNode next = n.path("next_after");
        return new Page(n.path("op_type").asString(""), out, next.isNull() || next.isMissingNode() ? null : next.asLong());
    }

    public void recordTpaIds(UUID jobId, Map<Long, String> tpaIdsByMember) {
        if (tpaIdsByMember.isEmpty()) return;
        List<Map<String, Object>> items = new ArrayList<>();
        tpaIdsByMember.forEach((id, tpa) -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("id", id); m.put("tpa_member_id", tpa); items.add(m); });
        send(HttpRequest.newBuilder(URI.create(base + "/api/v1/internal/endorsements/" + jobId + "/tpa-member-ids/"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("members", items)))));
    }

    /** Reports where an endorsement stands after the roster update (SENT_TO_INSURER, TPA_SYNC_COMPLETED, ...). The engine's state machine decides legality (409). */
    public void reportLifecycle(UUID jobId, String state, String reference) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", state);
        if (reference != null && !reference.isBlank()) body.put("reference", reference);
        send(HttpRequest.newBuilder(URI.create(base + "/api/v1/internal/endorsements/" + jobId + "/lifecycle/"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))));
    }

    public record PolicyPage(String policyNumber, List<RosterMember> members, Long nextAfter) {}

    /** One page of the policy's whole ACTIVE roster (not just what one job changed). */
    public PolicyPage policyMembers(long policyId, long after, int limit) {
        JsonNode n = send(HttpRequest.newBuilder(URI.create(base + "/api/v1/internal/policies/" + policyId + "/members/?after=" + after + "&limit=" + limit)).GET());
        List<RosterMember> out = new ArrayList<>();
        for (JsonNode m : n.path("members")) {
            out.add(new RosterMember(m.path("id").asLong(), m.path("employee_code").asString(""), m.path("relation_code").asString(""),
                    m.path("first_name").asString(""), m.path("last_name").asString(""), m.path("date_of_birth").asString(""),
                    nullable(m, "gender"), nullable(m, "email"), nullable(m, "mobile"), m.path("sum_insured").asString(""),
                    m.path("effective_date").asString(""), nullable(m, "end_date"), m.path("is_active").asBoolean(true)));
        }
        JsonNode next = n.path("next_after"), pn = n.path("policy_number");
        return new PolicyPage(pn.isNull() || pn.isMissingNode() ? null : pn.asString(), out, next.isNull() || next.isMissingNode() ? null : next.asLong());
    }

    public void recordPolicyTpaIds(long policyId, Map<Long, String> tpaIdsByMember) {
        if (tpaIdsByMember.isEmpty()) return;
        List<Map<String, Object>> items = new ArrayList<>();
        tpaIdsByMember.forEach((id, tpa) -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("id", id); m.put("tpa_member_id", tpa); items.add(m); });
        send(HttpRequest.newBuilder(URI.create(base + "/api/v1/internal/policies/" + policyId + "/tpa-member-ids/"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("members", items)))));
    }

    private JsonNode send(HttpRequest.Builder rb) {
        String token = tokens.get();
        try {
            HttpResponse<String> r = http.send(rb.timeout(Duration.ofMillis(timeoutMs)).header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json").build(), HttpResponse.BodyHandlers.ofString());
            int s = r.statusCode();
            if (s == 401) { tokens.invalidate(token); throw new EngineException("engine rejected our token", true); }
            if (s == 404) throw new EngineException("engine has no such job", false);
            if (s == 408 || s == 429 || s >= 500) throw new EngineException("engine answered HTTP " + s, true);
            if (s < 200 || s >= 300) throw new EngineException("engine refused the request with HTTP " + s, false);
            return mapper.readTree(r.body());
        } catch (IOException e) {
            throw new EngineException("engine unreachable: " + e.getClass().getSimpleName(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EngineException("interrupted", true);
        }
    }

    private static String nullable(JsonNode m, String f) { JsonNode v = m.path(f); return v.isNull() || v.isMissingNode() ? null : v.asString(); }
}
