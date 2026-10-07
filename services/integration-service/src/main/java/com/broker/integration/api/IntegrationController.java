package com.broker.integration.api;

import com.broker.integration.api.Dtos.*;
import com.broker.integration.domain.*;
import com.broker.integration.security.TenantContextHolder;
import com.broker.integration.sync.SyncService;
import com.broker.integration.tpa.TpaGateway;
import com.broker.integration.tpa.UrlGuard;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Broker-facing configuration and monitoring. Everything is scoped to the caller's broker (from the token); other brokers' rows are 404. */
@RestController
@RequestMapping("/api/v1/broker/integration")
public class IntegrationController {
    private final TpaConfigRepository configs;
    private final SyncJobRepository jobs;
    private final SyncRejectionRepository rejections;
    private final SyncService sync;
    private final TpaGateway gateway;
    private final UrlGuard guard;

    IntegrationController(TpaConfigRepository configs, SyncJobRepository jobs, SyncRejectionRepository rejections, SyncService sync,
                          TpaGateway gateway, UrlGuard guard) {
        this.configs = configs; this.jobs = jobs; this.rejections = rejections; this.sync = sync; this.gateway = gateway; this.guard = guard;
    }

    private static long broker() { return TenantContextHolder.requireBrokerId(); }

    @GetMapping("/tpa-configs")
    List<TpaConfigResponse> listConfigs() { return configs.findByBrokerIdOrderByTpaId(broker()).stream().map(TpaConfigResponse::of).toList(); }

    @PostMapping("/tpa-configs")
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    ResponseEntity<TpaConfigResponse> create(@Valid @RequestBody TpaConfigRequest r) {
        if (configs.findByBrokerIdAndTpaId(broker(), r.tpaId()).isPresent()) throw new ConflictException("A configuration for this TPA already exists");
        TpaConfig c = new TpaConfig(broker(), r.tpaId());
        apply(c, r);
        return ResponseEntity.status(201).body(TpaConfigResponse.of(configs.save(c)));
    }

    @PutMapping("/tpa-configs/{id}")
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    TpaConfigResponse update(@PathVariable long id, @Valid @RequestBody TpaConfigRequest r) {
        TpaConfig c = configs.findByIdAndBrokerId(id, broker()).orElseThrow(() -> new NotFoundException("No such TPA configuration"));
        if (!c.getTpaId().equals(r.tpaId())) throw new IllegalArgumentException("tpaId cannot be changed");
        apply(c, r);
        return TpaConfigResponse.of(configs.save(c));
    }

    private void apply(TpaConfig c, TpaConfigRequest r) {
        guard.check(r.baseUrl());                                                    // SSRF guard: IllegalArgumentException -> 400
        if (r.authType() != TpaConfig.AuthType.NONE && (r.secretRef() == null || r.secretRef().isBlank()))
            throw new IllegalArgumentException("secretRef is required when authType is not NONE");
        c.apply(r.adapter(), r.baseUrl(), r.authType(), r.authType() == TpaConfig.AuthType.NONE ? null : r.secretRef(),
                r.batchSize() == null ? 100 : r.batchSize(), r.timeoutMs() == null ? 5000 : r.timeoutMs(), r.enabled() == null || r.enabled());
    }

    @GetMapping("/sync-jobs")
    Page<SyncJobResponse> listJobs(@RequestParam(required = false) SyncJob.Status status, @RequestParam(required = false) Long policyId,
                                   @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        PageRequest p = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<SyncJob> r = status != null ? jobs.findByBrokerIdAndStatus(broker(), status, p)
                : policyId != null ? jobs.findByBrokerIdAndPolicyId(broker(), policyId, p) : jobs.findByBrokerId(broker(), p);
        return r.map(j -> SyncJobResponse.of(j, null));
    }

    @GetMapping("/sync-jobs/{id}")
    SyncJobResponse job(@PathVariable UUID id) {
        SyncJob j = jobs.findByIdAndBrokerId(id, broker()).orElseThrow(() -> new NotFoundException("No such sync job"));
        return SyncJobResponse.of(j, rejections.findBySyncJobIdOrderByMemberId(id));
    }

    @PostMapping("/sync-jobs/{id}/retry")
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    SyncJobResponse retry(@PathVariable UUID id) {
        if (!sync.retryDead(broker(), id)) throw new ConflictException("Only dead-lettered sync jobs can be retried");
        return job(id);
    }

    /** Circuit-breaker state per TPA for this broker (a TPA appears once it has been called). */
    @GetMapping("/status")
    Map<String, Object> status() {
        return Map.of("circuitBreakers", gateway.states(broker()),
                "deadLettered", jobs.findByBrokerIdAndStatus(broker(), SyncJob.Status.DEAD, PageRequest.of(0, 1)).getTotalElements(),
                "pending", jobs.findByBrokerIdAndStatus(broker(), SyncJob.Status.PENDING, PageRequest.of(0, 1)).getTotalElements());
    }
}
