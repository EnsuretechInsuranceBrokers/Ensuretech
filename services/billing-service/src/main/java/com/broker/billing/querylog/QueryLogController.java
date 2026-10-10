package com.broker.billing.querylog;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The recent requests with their controller method and SQL. SYSTEM tokens only (SecurityConfig: /api/v1/ops/**), and absent unless ops.querylog.enabled=true. */
@RestController
@RequestMapping("/api/v1/ops/queries")
@ConditionalOnProperty(name = "ops.querylog.enabled", havingValue = "true")
class QueryLogController {
    private final String service;

    QueryLogController(@Value("${spring.application.name}") String service) { this.service = service; }

    @GetMapping
    Map<String, Object> recent(@RequestParam(defaultValue = "100") int limit, @RequestParam(required = false) Long after) {
        List<QueryLog.Req> requests = after == null ? QueryLog.INSTANCE.recent(limit) : QueryLog.INSTANCE.after(after, limit);
        return Map.of("service", service, "enabled", true, "latest", QueryLog.INSTANCE.latest(), "requests", requests);
    }

    @DeleteMapping
    ResponseEntity<Void> clear() { QueryLog.INSTANCE.clear(); return ResponseEntity.noContent().build(); }
}
