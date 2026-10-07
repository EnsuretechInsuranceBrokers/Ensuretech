package com.broker.org.api;

import com.broker.org.api.Dtos.*;
import com.broker.org.domain.BrokerBranch;
import com.broker.org.domain.BrokerBranchRepository;
import com.broker.org.security.TenantContextHolder;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/broker/branches")
public class BranchController {
    private final BrokerBranchRepository branches;

    BranchController(BrokerBranchRepository branches) {
        this.branches = branches;
    }

    @GetMapping
    List<BranchResponse> list() {
        return branches.findByBrokerIdAndTestDataFalseOrderByBranchCode(TenantContextHolder.requireBrokerId())
                .stream().map(BranchResponse::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    BranchResponse create(@Valid @RequestBody CreateBranchRequest r) {
        long brokerId = TenantContextHolder.requireBrokerId();
        return BranchResponse.of(branches.saveAndFlush(
                new BrokerBranch(brokerId, r.branchCode(), r.city(), r.state(), r.headOffice())));
    }
}
