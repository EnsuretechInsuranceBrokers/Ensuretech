package com.broker.org.api;

import com.broker.org.api.Dtos.EmployerResponse;
import com.broker.org.security.TenantContext;
import com.broker.org.security.TenantContextHolder;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Employer gateway: HR users see only the employer in their own token. No id is accepted from the client. */
@RestController
@RequestMapping("/api/v1/employer/employer")
public class EmployerSelfController {
    private final EmployerService service;

    EmployerSelfController(EmployerService service) {
        this.service = service;
    }

    @GetMapping
    EmployerResponse me() {
        TenantContext t = TenantContextHolder.require();
        if (t.brokerId() == null || t.employerId() == null) {
            throw new ForbiddenTenantException("Caller is not employer-scoped");
        }
        return EmployerResponse.of(service.get(t.brokerId(), t.employerId()));
    }

    /** A parent company's HR sees its child companies (and only those). A child, or an employer without children, gets an empty list. */
    @GetMapping("/children")
    List<EmployerResponse> children() {
        TenantContext t = TenantContextHolder.require();
        if (t.brokerId() == null || t.employerId() == null) {
            throw new ForbiddenTenantException("Caller is not employer-scoped");
        }
        return service.children(t.brokerId(), t.employerId()).stream().map(EmployerResponse::of).toList();
    }
}
