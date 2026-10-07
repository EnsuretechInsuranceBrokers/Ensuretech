package com.broker.policy.service;

import com.broker.policy.api.NotFoundException;
import com.broker.policy.api.ValidationFailedException;
import com.broker.policy.api.ValidationFailedException.Issue;
import com.broker.policy.domain.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The employee / employer premium split of a policy. Absent = {@code NONE}: the split is not computed, never assumed. */
@Service
public class ContributionService {
    private static final BigDecimal HUNDRED = new BigDecimal(100);
    private final PolicyRepository policies;
    private final ContributionPlanRepository plans;

    ContributionService(PolicyRepository policies, ContributionPlanRepository plans) { this.policies = policies; this.plans = plans; }

    @Transactional(readOnly = true)
    public ContributionPlan get(long brokerId, long policyId) {
        policies.findByIdAndBrokerId(policyId, brokerId).orElseThrow(() -> new NotFoundException("Policy not found"));
        return plans.findByPolicyIdAndBrokerId(policyId, brokerId).orElseGet(() -> new ContributionPlan(policyId, brokerId));
    }

    @Transactional
    public ContributionPlan put(long brokerId, long policyId, ContributionPlan.Type type, Map<String, BigDecimal> groups) {
        policies.findByIdAndBrokerId(policyId, brokerId).orElseThrow(() -> new NotFoundException("Policy not found"));
        List<Issue> bad = new ArrayList<>();
        if (type == null) bad.add(new Issue("TYPE_REQUIRED", "type must be NONE, PERCENT or FIXED"));
        Map<String, BigDecimal> g = groups == null ? Map.of() : groups;
        if (type == ContributionPlan.Type.NONE && !g.isEmpty()) bad.add(new Issue("NONE_TAKES_NO_GROUPS", "type NONE cannot carry contributions"));
        for (var e : g.entrySet()) {
            if (!ContributionPlan.GROUPS.contains(e.getKey())) bad.add(new Issue("UNKNOWN_GROUP", "Unknown group '" + e.getKey() + "'; use " + ContributionPlan.GROUPS));
            else if (e.getValue() == null || e.getValue().signum() < 0) bad.add(new Issue("NEGATIVE", e.getKey() + ": contribution cannot be negative"));
            else if (type == ContributionPlan.Type.PERCENT && e.getValue().compareTo(HUNDRED) > 0) bad.add(new Issue("PERCENT_ABOVE_100", e.getKey() + ": a percentage cannot exceed 100"));
        }
        if (!bad.isEmpty()) throw new ValidationFailedException(bad);
        ContributionPlan p = plans.findByPolicyIdAndBrokerId(policyId, brokerId).orElseGet(() -> new ContributionPlan(policyId, brokerId));
        p.setType(type);
        p.setGroups(g);
        return plans.saveAndFlush(p);
    }
}
