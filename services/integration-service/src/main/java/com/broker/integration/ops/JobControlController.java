package com.broker.integration.ops;

import com.broker.integration.security.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Operator controls over the sync-job queue. For service accounts only (SYSTEM token, which is also what gets past the gateway lock): nothing here is reachable
 * from the portal. Reading needs just that; stopping or re-queuing a job also needs the job-killer password in the X-Job-Killer-Password header
 * (a header, never a URL, so it is not written to access logs or browser history).
 */
@RestController
@RequestMapping("/api/v1/ops/jobs")
@PreAuthorize("hasRole('SYSTEM')")
public class JobControlController {
    private final JobControlService service;

    JobControlController(JobControlService service) { this.service = service; }

    private static String actor() { return TenantContextHolder.require().userId(); }

    @GetMapping
    List<Map<String, Object>> list(@RequestParam(required = false) String status, @RequestParam(defaultValue = "100") int limit) {
        if (status != null && !status.matches("PENDING|IN_PROGRESS|SUCCEEDED|DEAD|SKIPPED|CANCELLED")) throw new IllegalArgumentException("unknown status");
        return service.list(status, limit);
    }

    @GetMapping("/{id}")
    Map<String, Object> one(@PathVariable UUID id) { return service.get(id); }

    @GetMapping("/audit")
    List<Map<String, Object>> audit(@RequestParam(defaultValue = "100") int limit) { return service.audit(limit); }

    public record Kill(String reason) {}

    @PostMapping("/{id}/kill")
    Map<String, Object> kill(@PathVariable UUID id, @RequestHeader(value = "X-Job-Killer-Password", required = false) String password, @RequestBody Kill body, HttpServletRequest req) {
        return service.kill(id, password, body.reason(), actor(), req.getRemoteAddr());
    }

    @PostMapping("/{id}/requeue")
    Map<String, Object> requeue(@PathVariable UUID id, @RequestHeader(value = "X-Job-Killer-Password", required = false) String password, HttpServletRequest req) {
        return service.requeue(id, password, actor(), req.getRemoteAddr());
    }

    /** The reason travels in the body (the default error page hides it), so the CLI and the Jobs page can say why. */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    org.springframework.http.ResponseEntity<Map<String, String>> reason(org.springframework.web.server.ResponseStatusException e) {
        return org.springframework.http.ResponseEntity.status(e.getStatusCode()).body(Map.of("detail", e.getReason() == null ? "" : e.getReason()));
    }

    public record NewPassword(String newPassword) {}

    @PutMapping("/password")
    Map<String, String> password(@RequestHeader(value = "X-Job-Killer-Password", required = false) String current, @RequestBody NewPassword body, HttpServletRequest req) {
        service.changePassword(current, body.newPassword(), actor(), req.getRemoteAddr());
        return Map.of("status", "changed");
    }
}
