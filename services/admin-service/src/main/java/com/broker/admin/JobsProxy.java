package com.broker.admin;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

/**
 * The Jobs page's backend: forwards to integration-service's /api/v1/ops/jobs with the console's SYSTEM token. The job-killer password the operator types is passed straight
 * through in its header and is neither logged nor stored here; integration-service checks it against its bcrypt hash and rate-limits wrong guesses.
 */
@RestController
class JobsProxy {
    private final RestClient http = RestClient.create();
    private final SystemToken token;
    private final String base;

    JobsProxy(SystemToken token, @Value("${admin.integration.url}") String base) { this.token = token; this.base = base + "/api/v1/ops/jobs"; }

    private ResponseEntity<String> send(org.springframework.http.HttpMethod m, String path, String password, String body) {
        var req = http.method(m).uri(base + path).headers(h -> { h.setBearerAuth(token.get()); String cid = com.broker.admin.logging.CorrelationFilter.current(); if (cid != null) h.set("X-Correlation-Id", cid); if (password != null && !password.isEmpty()) h.set("X-Job-Killer-Password", password); });
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).body(body);
        return req.exchange((rq, rs) -> ResponseEntity.status(HttpStatusCode.valueOf(rs.getStatusCode().value())).contentType(MediaType.APPLICATION_JSON).body(new String(rs.getBody().readAllBytes())));
    }

    /** The page itself. A controller, because Spring Boot Admin's own UI handler answers every plain static path. */
    @GetMapping(value = "/jobs.html", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> page() throws java.io.IOException {
        try (var in = new org.springframework.core.io.ClassPathResource("ops/jobs.html").getInputStream()) { return ResponseEntity.ok(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)); }
    }

    @GetMapping("/ops-api/jobs")
    ResponseEntity<String> list(@RequestParam(required = false) String status) { return send(org.springframework.http.HttpMethod.GET, status == null ? "" : "?status=" + status.replaceAll("[^A-Z_]", ""), null, null); }

    @GetMapping("/ops-api/jobs/audit")
    ResponseEntity<String> audit() { return send(org.springframework.http.HttpMethod.GET, "/audit", null, null); }

    @PostMapping("/ops-api/jobs/{id}/kill")
    ResponseEntity<String> kill(@PathVariable String id, @RequestHeader(value = "X-Job-Killer-Password", required = false) String pw, @RequestBody String body) {
        return send(org.springframework.http.HttpMethod.POST, "/" + id.replaceAll("[^0-9a-fA-F-]", "") + "/kill", pw, body);
    }

    @PostMapping("/ops-api/jobs/{id}/requeue")
    ResponseEntity<String> requeue(@PathVariable String id, @RequestHeader(value = "X-Job-Killer-Password", required = false) String pw) {
        return send(org.springframework.http.HttpMethod.POST, "/" + id.replaceAll("[^0-9a-fA-F-]", "") + "/requeue", pw, null);
    }
}
