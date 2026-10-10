package com.broker.policy.service;

import com.broker.policy.api.NotFoundException;
import com.broker.policy.api.ValidationFailedException;
import com.broker.policy.api.ValidationFailedException.Issue;
import com.broker.policy.domain.*;
import com.broker.policy.domain.DependantMidtermRules.Rule;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Mid-term dependant rules of a policy; `put` replaces the whole set. A group with no rule keeps the sheet's own effective date. */
@Service
public class DependantMidtermService {
    private final PolicyRepository policies;
    private final DependantMidtermRulesRepository repo;

    DependantMidtermService(PolicyRepository policies, DependantMidtermRulesRepository repo) { this.policies = policies; this.repo = repo; }

    @Transactional(readOnly = true)
    public Map<String, Rule> get(long brokerId, long policyId) {
        policies.findByIdAndBrokerId(policyId, brokerId).orElseThrow(() -> new NotFoundException("Policy not found"));
        return repo.findByPolicyIdAndBrokerId(policyId, brokerId).map(DependantMidtermRules::getRules).orElseGet(Map::of);
    }

    @Transactional
    public Map<String, Rule> put(long brokerId, long policyId, Map<String, Rule> in) {
        policies.findByIdAndBrokerId(policyId, brokerId).orElseThrow(() -> new NotFoundException("Policy not found"));
        List<Issue> bad = new ArrayList<>();
        Map<String, Rule> rules = in == null ? Map.of() : in;
        rules.forEach((g, r) -> {
            if (!DependantMidtermRules.GROUPS.contains(g)) bad.add(new Issue("UNKNOWN_GROUP", "Unknown group '" + g + "'; use " + DependantMidtermRules.GROUPS));
            if (r == null) { bad.add(new Issue("RULE_REQUIRED", g + ": rule is missing")); return; }
            if (!DependantMidtermRules.BASES.contains(r.startBasis())) bad.add(new Issue("START_BASIS", g + ": startBasis must be UPLOAD or EVENT"));
            if (r.windowDays() != null && (r.windowDays() < 0 || r.windowDays() > 3650)) bad.add(new Issue("WINDOW_DAYS", g + ": windowDays must be between 0 and 3650"));
        });
        if (!bad.isEmpty()) throw new ValidationFailedException(bad);
        DependantMidtermRules e = repo.findByPolicyIdAndBrokerId(policyId, brokerId).orElseGet(() -> new DependantMidtermRules(policyId, brokerId));
        e.setRules(rules);
        return repo.saveAndFlush(e).getRules();
    }
}
