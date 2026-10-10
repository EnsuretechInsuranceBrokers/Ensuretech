package com.broker.admin;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Queries page's backend, and the live feed behind it. Every Spring service keeps its recent requests (who, controller method, SQL) in memory and serves them at
 * GET /api/v1/ops/queries?after=SEQ to a SYSTEM token. This service is the one place that asks: while at least one browser is watching it polls each service every second for what is new,
 * merges it into one bounded buffer, and pushes it to every open page over server-sent events. With nobody watching it asks no one. A service that is down, or has the log switched off
 * (QUERYLOG_ENABLED), is reported as such rather than failing the page. The console's own login (ADMIN_UI_USER / ADMIN_UI_PASSWORD) protects it like every other page.
 */
@RestController
class QueriesProxy {
    private static final Logger log = LoggerFactory.getLogger(QueriesProxy.class);
    private static final int KEEP = 2000, PER_POLL = 300;
    private final RestClient http = RestClient.builder().requestFactory(timeouts()).build();
    private final SystemToken token;
    private final Map<String, String> services = new LinkedHashMap<>();
    private final Map<String, Long> cursor = new LinkedHashMap<>();
    private final Map<String, String> state = new LinkedHashMap<>();         // service -> "ok" | error text
    private final ArrayDeque<ObjectNode> buffer = new ArrayDeque<>();        // newest first
    private final List<SseEmitter> watchers = new CopyOnWriteArrayList<>();

    QueriesProxy(SystemToken token, @Value("${admin.querylog.services}") List<String> services) {
        this.token = token;
        for (String s : services) { int i = s.indexOf('='); if (i > 0) this.services.put(s.substring(0, i).trim(), s.substring(i + 1).trim()); }
    }

    private static org.springframework.http.client.SimpleClientHttpRequestFactory timeouts() {
        var f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(java.time.Duration.ofSeconds(2));
        f.setReadTimeout(java.time.Duration.ofSeconds(5));
        return f;
    }

    @GetMapping(value = "/queries.html", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> page() throws IOException {
        try (var in = new org.springframework.core.io.ClassPathResource("ops/queries.html").getInputStream()) { return ResponseEntity.ok(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)); }
    }

    /** What is in the buffer now (newest first) and the state of each service. Catches up first, so it is current even when nobody is streaming. */
    @GetMapping("/ops-api/queries")
    synchronized Map<String, Object> snapshot(@RequestParam(defaultValue = "500") int limit) {
        poll();
        List<Object> reqs = new ArrayList<>();
        for (ObjectNode n : buffer) { if (reqs.size() >= Math.max(1, Math.min(limit, KEEP))) break; reqs.add(n); }
        return Map.of("services", new LinkedHashMap<>(state), "requests", reqs);
    }

    /** The live feed: each event carries the requests that finished since the last one. */
    @GetMapping(value = "/ops-api/queries/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream() {
        SseEmitter e = new SseEmitter(0L);
        watchers.add(e);
        Runnable gone = () -> watchers.remove(e);
        e.onCompletion(gone); e.onTimeout(gone); e.onError(x -> gone.run());
        return e;
    }

    @Scheduled(fixedDelay = 1000)
    void tick() {
        if (watchers.isEmpty()) return;
        List<ObjectNode> fresh;
        synchronized (this) { fresh = poll(); }
        if (fresh.isEmpty()) return;
        for (SseEmitter w : watchers) {
            try { w.send(SseEmitter.event().name("requests").data(fresh, MediaType.APPLICATION_JSON)); } catch (IOException | IllegalStateException gone) { watchers.remove(w); }
        }
    }

    @DeleteMapping("/ops-api/queries")
    synchronized ResponseEntity<Void> clear() {
        for (String base : services.values()) {
            try { http.delete().uri(base + "/api/v1/ops/queries").headers(this::auth).retrieve().toBodilessEntity(); } catch (RuntimeException ignored) { }
        }
        buffer.clear(); cursor.clear();
        return ResponseEntity.noContent().build();
    }

    /** Ask each service for what finished after our last position, add the service name, merge into the buffer. Returns the new entries, oldest first. */
    private List<ObjectNode> poll() {
        List<ObjectNode> fresh = new ArrayList<>();
        for (var e : services.entrySet()) {
            String name = e.getKey();
            long after = cursor.getOrDefault(name, 0L);
            try {
                JsonNode r = http.get().uri(e.getValue() + "/api/v1/ops/queries?after=" + after + "&limit=" + PER_POLL).headers(this::auth).retrieve().body(JsonNode.class);
                long latest = r.path("latest").asLong(0);
                if (latest < after) { cursor.put(name, 0L); continue; }              // the service restarted: its numbering began again
                for (JsonNode req : r.path("requests")) {
                    ObjectNode n = ((ObjectNode) req).deepCopy();
                    n.put("service", name);
                    fresh.add(n);
                    after = Math.max(after, req.path("seq").asLong());
                }
                cursor.put(name, after);
                state.put(name, "ok");
            } catch (org.springframework.web.client.HttpClientErrorException.NotFound nf) {
                state.put(name, "query log is off (start with QUERYLOG_ENABLED=true)");
            } catch (RuntimeException ex) {
                state.put(name, "not reachable");
            }
        }
        fresh.sort((a, b) -> a.path("at").asString().compareTo(b.path("at").asString()));
        for (ObjectNode n : fresh) buffer.addFirst(n);
        while (buffer.size() > KEEP) buffer.removeLast();
        return fresh;
    }

    private void auth(org.springframework.http.HttpHeaders h) {
        h.setBearerAuth(token.get());
        String cid = com.broker.admin.logging.CorrelationFilter.current();
        if (cid != null) h.set("X-Correlation-Id", cid);
    }
}
