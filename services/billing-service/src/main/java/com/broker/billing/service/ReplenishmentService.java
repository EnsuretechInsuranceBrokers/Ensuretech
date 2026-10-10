package com.broker.billing.service;

import com.broker.billing.api.ConflictException;
import com.broker.billing.api.NotFoundException;
import com.broker.billing.domain.*;
import com.broker.billing.domain.LedgerEntry.ReferenceType;
import com.broker.billing.domain.ReplenishmentRequest.Status;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Employer says "we paid X (reference R)"; a broker admin confirms receipt, which credits the deposit exactly once. */
@Service
public class ReplenishmentService {
    private final CdAccountRepository accounts;
    private final ReplenishmentRequestRepository requests;
    private final CdLedgerService ledger;

    ReplenishmentService(CdAccountRepository accounts, ReplenishmentRequestRepository requests, CdLedgerService ledger) {
        this.accounts = accounts;
        this.requests = requests;
        this.ledger = ledger;
    }

    @Transactional
    public ReplenishmentRequest request(long brokerId, long policyId, BigDecimal amount, String reference, String actor) {
        CdAccount acct = accounts.findByPolicyIdAndBrokerId(policyId, brokerId).orElseThrow(() -> new NotFoundException("No CD account for policy"));
        boolean dup = requests.findByAccountIdOrderByIdDesc(acct.getId()).stream().anyMatch(r -> r.getReference().equals(reference));
        if (dup) throw new ConflictException("A request with payment reference '" + reference + "' already exists");
        return requests.saveAndFlush(new ReplenishmentRequest(acct.getId(), CdLedgerService.money(amount), reference, actor));
    }

    @Transactional(readOnly = true)
    public List<ReplenishmentRequest> list(long brokerId, long policyId) {
        CdAccount acct = accounts.findByPolicyIdAndBrokerId(policyId, brokerId).orElseThrow(() -> new NotFoundException("No CD account for policy"));
        return requests.findByAccountIdOrderByIdDesc(acct.getId());
    }

    /** Lock order is always request -> account, so two approvals of the same request serialise and credit once. */
    @Transactional
    public ReplenishmentRequest approve(long brokerId, long policyId, long requestId, String actor) {
        ReplenishmentRequest r = lockRequest(brokerId, policyId, requestId);
        if (r.getStatus() != Status.REQUESTED) throw new ConflictException("Request is already " + r.getStatus());
        CdAccount acct = accounts.lockById(r.getAccountId()).orElseThrow();
        ledger.creditLocked(acct, r.getAmount(), ReferenceType.REPLENISHMENT, String.valueOf(r.getId()),
                "Replenishment " + r.getReference(), actor);
        r.decide(Status.APPROVED, actor, null);
        return r;
    }

    @Transactional
    public ReplenishmentRequest reject(long brokerId, long policyId, long requestId, String actor, String note) {
        ReplenishmentRequest r = lockRequest(brokerId, policyId, requestId);
        if (r.getStatus() != Status.REQUESTED) throw new ConflictException("Request is already " + r.getStatus());
        r.decide(Status.REJECTED, actor, note);
        return r;
    }

    private ReplenishmentRequest lockRequest(long brokerId, long policyId, long requestId) {
        CdAccount acct = accounts.findByPolicyIdAndBrokerId(policyId, brokerId).orElseThrow(() -> new NotFoundException("No CD account for policy"));
        return requests.lockByIdAndAccountId(requestId, acct.getId()).orElseThrow(() -> new NotFoundException("Request not found"));
    }
}
