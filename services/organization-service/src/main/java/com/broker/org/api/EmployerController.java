package com.broker.org.api;

import com.broker.org.api.Dtos.*;
import com.broker.org.security.TenantContext;
import com.broker.org.security.TenantContextHolder;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/broker/employers")
public class EmployerController {
    private final EmployerService service;

    EmployerController(EmployerService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    ResponseEntity<EmployerResponse> create(@Valid @RequestBody CreateEmployerRequest r) {
        var e = service.create(TenantContextHolder.requireBrokerId(), r);
        return ResponseEntity.created(URI.create("/api/v1/broker/employers/" + e.getId()))
                .body(EmployerResponse.of(e));
    }

    @GetMapping
    Page<EmployerResponse> list(@RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "50") int size) {
        TenantContext t = TenantContextHolder.require();
        boolean admin = "BROKER_ADMIN".equals(t.role());   // hidden employers are visible to admins only
        return service.list(TenantContextHolder.requireBrokerId(), admin,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200), Sort.by("id")))
                .map(EmployerResponse::of);
    }

    @GetMapping("/{id}")
    EmployerResponse get(@PathVariable long id) {
        return EmployerResponse.of(service.get(TenantContextHolder.requireBrokerId(), id));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    EmployerResponse update(@PathVariable long id, @Valid @RequestBody UpdateEmployerRequest r) {
        return EmployerResponse.of(service.update(TenantContextHolder.requireBrokerId(), id, r));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    ResponseEntity<Void> deactivate(@PathVariable long id) {
        service.deactivate(TenantContextHolder.requireBrokerId(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/configs")
    List<ConfigResponse> configs(@PathVariable long id) {
        return service.listConfigs(TenantContextHolder.requireBrokerId(), id);
    }

    @PutMapping("/{id}/configs/{key}")
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    ConfigResponse putConfig(@PathVariable long id, @PathVariable String key, @Valid @RequestBody ConfigRequest r) {
        return service.putConfig(TenantContextHolder.requireBrokerId(), id, key, r);
    }
}
