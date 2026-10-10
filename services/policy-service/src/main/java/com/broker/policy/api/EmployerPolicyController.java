package com.broker.policy.api;

import com.broker.policy.api.Dtos.PolicyResponse;
import com.broker.policy.security.TenantContext;
import com.broker.policy.security.TenantContextHolder;
import com.broker.policy.service.PolicyService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

/** Employer gateway: HR sees only LIVE policies of their own employer. No ids are taken from the client for scope. */
@RestController
@RequestMapping("/api/v1/employer/policies")
public class EmployerPolicyController {
    private final PolicyService policies;

    EmployerPolicyController(PolicyService policies) {
        this.policies = policies;
    }

    private static TenantContext employerScope() {
        TenantContext t = TenantContextHolder.require();
        if (t.brokerId() == null || t.employerId() == null) {
            throw new ForbiddenTenantException("Caller is not employer-scoped");
        }
        return t;
    }

    @GetMapping
    Page<PolicyResponse> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        TenantContext t = employerScope();
        return policies.listLiveForEmployer(t.brokerId(), t.employerId(),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200), Sort.by("id")))
                .map(PolicyResponse::of);
    }

    @GetMapping("/{id}")
    PolicyResponse get(@PathVariable long id) {
        TenantContext t = employerScope();
        return PolicyResponse.of(policies.getForEmployer(t.brokerId(), t.employerId(), id));
    }
}
