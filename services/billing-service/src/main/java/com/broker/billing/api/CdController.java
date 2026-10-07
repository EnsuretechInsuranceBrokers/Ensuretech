package com.broker.billing.api;

import com.broker.billing.api.Dtos.*;
import com.broker.billing.domain.*;
import com.broker.billing.domain.LedgerEntry.ReferenceType;
import com.broker.billing.security.TenantContext;
import com.broker.billing.security.TenantContextHolder;
import com.broker.billing.service.CdLedgerService;
import com.broker.billing.service.ReplenishmentService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Deposit accounts for brokers (/broker/cd) and HR users (/employer/cd). Scope always comes from the token: brokers see
 * their own broker's accounts, HR users only their own employer's. Anything else is a 404, never a 403.
 */
@RestController
public class CdController {
    private final CdAccountRepository accounts;
    private final LedgerEntryRepository ledgerRepo;
    private final CdLedgerService ledger;
    private final ReplenishmentService replenishments;

    CdController(CdAccountRepository accounts, LedgerEntryRepository ledgerRepo, CdLedgerService ledger, ReplenishmentService replenishments) {
        this.accounts = accounts;
        this.ledgerRepo = ledgerRepo;
        this.ledger = ledger;
        this.replenishments = replenishments;
    }

    /** The caller's view of one policy's account, or 404. Employer callers are additionally pinned to their employer. */
    private CdAccount visible(long policyId) {
        TenantContext t = TenantContextHolder.require();
        if (t.brokerId() == null) throw new ForbiddenTenantException("Caller is not tenant-scoped");
        CdAccount a = accounts.findByPolicyIdAndBrokerId(policyId, t.brokerId()).orElseThrow(() -> new NotFoundException("No CD account for policy"));
        if (t.isEmployerScoped() && !a.getEmployerId().equals(t.employerId())) throw new NotFoundException("No CD account for policy");
        return a;
    }

    private static String actor() { return TenantContextHolder.require().userId(); }

    // ── reads (broker and employer gateways) ──────────────────────────────────────────────────────────────────
    @GetMapping({"/api/v1/broker/cd/accounts", "/api/v1/employer/cd/accounts"})
    List<AccountResponse> list() {
        TenantContext t = TenantContextHolder.require();
        if (t.brokerId() == null) throw new ForbiddenTenantException("Caller is not tenant-scoped");
        List<CdAccount> list = t.isEmployerScoped()
                ? accounts.findByBrokerIdAndEmployerIdOrderByPolicyId(t.brokerId(), t.employerId())
                : accounts.findByBrokerIdOrderByPolicyId(t.brokerId());
        return list.stream().map(AccountResponse::of).toList();
    }

    @GetMapping({"/api/v1/broker/cd/accounts/{policyId}", "/api/v1/employer/cd/accounts/{policyId}"})
    AccountResponse get(@PathVariable long policyId) { return AccountResponse.of(visible(policyId)); }

    @GetMapping({"/api/v1/broker/cd/accounts/{policyId}/statements", "/api/v1/employer/cd/accounts/{policyId}/statements"})
    Page<EntryResponse> statements(@PathVariable long policyId, @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "50") int size) {
        CdAccount a = visible(policyId);
        return ledgerRepo.findByAccountIdOrderByIdDesc(a.getId(), PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200)))
                .map(EntryResponse::of);
    }

    @GetMapping({"/api/v1/broker/cd/accounts/{policyId}/replenishments", "/api/v1/employer/cd/accounts/{policyId}/replenishments"})
    List<ReplenishmentResponse> listReplenishments(@PathVariable long policyId) {
        CdAccount a = visible(policyId);
        return replenishments.list(a.getBrokerId(), policyId).stream().map(ReplenishmentResponse::of).toList();
    }

    // ── broker writes ─────────────────────────────────────────────────────────────────────────────────────────
    /** Records money received. Replaying the same reference is harmless: 200 with the original entry. */
    @PostMapping("/api/v1/broker/cd/accounts/{policyId}/credits")
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    ResponseEntity<EntryResponse> credit(@PathVariable long policyId, @Valid @RequestBody CreditRequest r) {
        visible(policyId);
        boolean existed = ledgerRepo.findByAccountIdAndReferenceTypeAndReferenceId(
                accounts.findByPolicyIdAndBrokerId(policyId, TenantContextHolder.requireBrokerId()).orElseThrow().getId(),
                ReferenceType.valueOf(r.type()), r.reference()).isPresent();
        LedgerEntry e = ledger.credit(TenantContextHolder.requireBrokerId(), policyId, r.amount(),
                ReferenceType.valueOf(r.type()), r.reference(), r.narration(), actor());
        return ResponseEntity.status(existed ? 200 : 201).body(EntryResponse.of(e));
    }

    @PutMapping("/api/v1/broker/cd/accounts/{policyId}/threshold")
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    AccountResponse threshold(@PathVariable long policyId, @Valid @RequestBody ThresholdRequest r) {
        visible(policyId);
        return AccountResponse.of(ledger.setThreshold(TenantContextHolder.requireBrokerId(), policyId, r.thresholdAmount()));
    }

    @PostMapping("/api/v1/broker/cd/accounts/{policyId}/replenishments/{id}/approve")
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    ReplenishmentResponse approve(@PathVariable long policyId, @PathVariable long id) {
        visible(policyId);
        return ReplenishmentResponse.of(replenishments.approve(TenantContextHolder.requireBrokerId(), policyId, id, actor()));
    }

    @PostMapping("/api/v1/broker/cd/accounts/{policyId}/replenishments/{id}/reject")
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    ReplenishmentResponse reject(@PathVariable long policyId, @PathVariable long id, @Valid @RequestBody(required = false) DecisionRequest r) {
        visible(policyId);
        return ReplenishmentResponse.of(replenishments.reject(TenantContextHolder.requireBrokerId(), policyId, id, actor(), r == null ? null : r.note()));
    }

    // ── asking for more money (either gateway) ────────────────────────────────────────────────────────────────
    @PostMapping("/api/v1/broker/cd/accounts/{policyId}/replenishments")
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    ResponseEntity<ReplenishmentResponse> requestAsBroker(@PathVariable long policyId, @Valid @RequestBody ReplenishmentCreate r) {
        return requestReplenishment(policyId, r);
    }

    @PostMapping("/api/v1/employer/cd/accounts/{policyId}/replenishments")
    @PreAuthorize("hasRole('EMPLOYER_ADMIN')")
    ResponseEntity<ReplenishmentResponse> requestAsEmployer(@PathVariable long policyId, @Valid @RequestBody ReplenishmentCreate r) {
        return requestReplenishment(policyId, r);
    }

    private ResponseEntity<ReplenishmentResponse> requestReplenishment(long policyId, ReplenishmentCreate r) {
        CdAccount a = visible(policyId);
        return ResponseEntity.status(201).body(ReplenishmentResponse.of(
                replenishments.request(a.getBrokerId(), policyId, r.amount(), r.reference(), actor())));
    }
}
