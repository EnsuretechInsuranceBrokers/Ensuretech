package com.broker.integration.api;

import com.broker.integration.domain.TpaConfigRepository;
import com.broker.integration.security.TenantContextHolder;
import com.broker.integration.sync.ReconcileService;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Pull-and-reconcile: attach the TPA's member ids to our roster. Scoped to the caller's broker (other brokers' policies are 404). */
@RestController
@RequestMapping("/api/v1/broker/integration")
public class ReconcileController {
    public record Settings(@NotNull Boolean stripLeadingZeros, @NotNull @Size(max = 200) Map<String, String> relationLabels) {}

    private final ReconcileService service;
    private final TpaConfigRepository configs;

    ReconcileController(ReconcileService service, TpaConfigRepository configs) { this.service = service; this.configs = configs; }

    private static long broker() { return TenantContextHolder.requireBrokerId(); }

    @PostMapping("/policies/{policyId}/reconcile")
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    ReconcileService.Run reconcile(@PathVariable long policyId) { return service.reconcile(broker(), policyId); }

    @GetMapping("/reconcile-runs")
    List<ReconcileService.Run> runs(@RequestParam(required = false) Long policyId) { return service.runs(broker(), policyId); }

    @GetMapping("/reconcile-runs/{id}")
    Map<String, Object> run(@PathVariable UUID id) { return Map.of("run", service.run(broker(), id), "misses", service.misses(broker(), id)); }

    @PutMapping("/tpa-configs/{id}/reconciliation")
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    Settings put(@PathVariable long id, @jakarta.validation.Valid @RequestBody Settings s) {
        var c = configs.findByIdAndBrokerId(id, broker()).orElseThrow(() -> new NotFoundException("No such TPA configuration"));
        for (var e : s.relationLabels().entrySet()) {
            if (e.getKey().isBlank() || e.getKey().length() > 60 || e.getValue() == null || !e.getValue().matches("[A-Z_]{2,40}")) {
                throw new IllegalArgumentException("relationLabels maps a TPA label (up to 60 characters) to one of our relation codes, e.g. \"wife\": \"SPOUSE\"");
            }
        }
        service.putSettings(broker(), c.getTpaId(), s.stripLeadingZeros(), s.relationLabels());
        return s;
    }
}
