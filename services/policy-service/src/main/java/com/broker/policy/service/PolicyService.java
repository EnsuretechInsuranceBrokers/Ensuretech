package com.broker.policy.service;

import com.broker.policy.api.ConflictException;
import com.broker.policy.api.NotFoundException;
import com.broker.policy.api.ValidationFailedException;
import com.broker.policy.domain.*;
import com.broker.policy.outbox.OutboxPublisher;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PolicyService {
    static final String TOPIC = "policy.events";

    private final PolicyRepository policies;
    private final PremiumTypeRepository premiumTypes;
    private final SumInsuredSubtypeRepository sumInsured;
    private final RaterTypeRepository raterTypes;
    private final EmployerRefRepository employers;
    private final PolicyReadinessService readiness;
    private final OutboxPublisher outbox;

    PolicyService(PolicyRepository policies, PremiumTypeRepository premiumTypes,
                  SumInsuredSubtypeRepository sumInsured, RaterTypeRepository raterTypes,
                  EmployerRefRepository employers, PolicyReadinessService readiness, OutboxPublisher outbox) {
        this.policies = policies;
        this.premiumTypes = premiumTypes;
        this.sumInsured = sumInsured;
        this.raterTypes = raterTypes;
        this.employers = employers;
        this.readiness = readiness;
        this.outbox = outbox;
    }

    @Transactional
    public Policy create(long brokerId, Policy draft) {
        // A policy may only attach to an employer this broker owns; anything else looks like "not found".
        EmployerRef emp = employers.findByEmployerIdAndBrokerId(draft.getEmployerId(), brokerId)
                .orElseThrow(() -> new NotFoundException("Employer not found"));
        if (!emp.isActive()) throw new ValidationFailedException("EMPLOYER_INACTIVE", "Employer is deactivated");
        PremiumType pt = premiumTypes.findById(draft.getPremiumTypeId())
                .filter(PremiumType::isActive)
                .orElseThrow(() -> new ValidationFailedException("UNKNOWN_PREMIUM_TYPE", "Unknown premium type"));
        sumInsured.findById(draft.getSuminsuredSubtypeId())
                .orElseThrow(() -> new ValidationFailedException("UNKNOWN_SUMINSURED_SUBTYPE", "Unknown sum insured subtype"));
        raterTypes.findById(draft.getRaterTypeId())
                .orElseThrow(() -> new ValidationFailedException("UNKNOWN_RATER_TYPE", "Unknown rater type"));
        if (!draft.getEndDate().isAfter(draft.getStartDate())) {
            throw new ValidationFailedException("DATE_RANGE", "end_date must be after start_date");
        }
        if (policies.existsByBrokerIdAndPolicyNumber(brokerId, draft.getPolicyNumber())) {
            throw new ConflictException("Policy number already exists");
        }
        Policy saved = policies.saveAndFlush(draft);
        emit("PolicyCreated", saved, pt.getCode());
        return saved;
    }

    @Transactional(readOnly = true)
    public Policy get(long brokerId, long id) {
        return policies.findByIdAndBrokerId(id, brokerId).orElseThrow(() -> new NotFoundException("Policy not found"));
    }

    @Transactional(readOnly = true)
    public Policy getForEmployer(long brokerId, long employerId, long id) {
        return policies.findByIdAndBrokerIdAndEmployerId(id, brokerId, employerId)
                .filter(p -> p.getStatus() == PolicyStatus.APPROVED_AND_LIVE)
                .orElseThrow(() -> new NotFoundException("Policy not found"));
    }

    @Transactional(readOnly = true)
    public Page<Policy> list(long brokerId, Long employerId, Pageable page) {
        return employerId == null ? policies.findByBrokerIdAndTestDataFalse(brokerId, page)
                : policies.findByBrokerIdAndEmployerIdAndTestDataFalse(brokerId, employerId, page);
    }

    @Transactional(readOnly = true)
    public Page<Policy> listLiveForEmployer(long brokerId, long employerId, Pageable page) {
        return policies.findByBrokerIdAndEmployerIdAndStatusAndTestDataFalse(
                brokerId, employerId, PolicyStatus.APPROVED_AND_LIVE, page);
    }

    @Transactional
    public Policy approve(long brokerId, long id) {
        Policy p = get(brokerId, id);
        if (p.getStatus() != PolicyStatus.NOT_APPROVED) {
            throw new ConflictException("Only NOT_APPROVED policies can be approved (is " + p.getStatus() + ")");
        }
        var issues = readiness.check(p);
        if (!issues.isEmpty()) throw new ValidationFailedException(issues);
        p.setStatus(PolicyStatus.APPROVED_AND_LIVE);
        p = policies.saveAndFlush(p);
        emit("PolicyApproved", p, null);
        return p;
    }

    @Transactional
    public Policy deactivate(long brokerId, long id) {
        Policy p = get(brokerId, id);
        if (p.getStatus() != PolicyStatus.APPROVED_AND_LIVE) {
            throw new ConflictException("Only APPROVED_AND_LIVE policies can be deactivated (is " + p.getStatus() + ")");
        }
        p.setStatus(PolicyStatus.INACTIVE);
        p = policies.saveAndFlush(p);
        emit("PolicyDeactivated", p, null);
        return p;
    }

    /** Daily sweep: live policies past their end date become EXPIRED. @return number expired */
    @Transactional
    public int expireDue(LocalDate today) {
        var due = policies.findByStatusAndEndDateBefore(PolicyStatus.APPROVED_AND_LIVE, today);
        for (Policy p : due) {
            p.setStatus(PolicyStatus.EXPIRED);
            emit("PolicyExpired", policies.saveAndFlush(p), null);
        }
        return due.size();
    }

    private void emit(String type, Policy p, String premiumTypeCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("broker_id", p.getBrokerId());
        body.put("employer_id", p.getEmployerId());
        body.put("policy_id", p.getId());
        body.put("policy_number", p.getPolicyNumber());
        body.put("status", p.getStatus().name());
        if (premiumTypeCode != null) body.put("premium_type", premiumTypeCode);
        outbox.publish("Policy", p.getId(), type, TOPIC, String.valueOf(p.getBrokerId()), body);
    }
}
