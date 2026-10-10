package com.broker.policy.service;

import com.broker.policy.api.ValidationFailedException.Issue;
import com.broker.policy.domain.*;
import com.broker.policy.engine.PremiumEngine;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Answers "can this policy accept endorsements?" Used by approve and by the ValidatePolicyConfig RPC. */
@Service
public class PolicyReadinessService {
    private static final Set<String> NEEDS_FAMILY_CONSTRUCT = Set.of("GMC", "OPD", "WELLNESS");

    private final PremiumTypeRepository premiumTypes;
    private final RateCardRepository rateCards;
    private final FamilyConstructRepository constructs;
    private final EmployerRefRepository employers;
    private final PremiumEngine engine;

    PolicyReadinessService(PremiumTypeRepository premiumTypes, RateCardRepository rateCards,
                           FamilyConstructRepository constructs, EmployerRefRepository employers,
                           PremiumEngine engine) {
        this.premiumTypes = premiumTypes;
        this.rateCards = rateCards;
        this.constructs = constructs;
        this.employers = employers;
        this.engine = engine;
    }

    @Transactional(readOnly = true)
    public List<Issue> check(Policy p) {
        List<Issue> issues = new ArrayList<>();
        if (p.getEndDate().isBefore(LocalDate.now())) {
            issues.add(new Issue("POLICY_EXPIRED", "Policy end date " + p.getEndDate() + " has passed"));
        }
        employers.findByEmployerIdAndBrokerId(p.getEmployerId(), p.getBrokerId()).ifPresentOrElse(e -> {
            if (!e.isActive()) issues.add(new Issue("EMPLOYER_INACTIVE", "Employer is deactivated"));
        }, () -> issues.add(new Issue("EMPLOYER_UNKNOWN", "Employer not known to this broker")));

        var card = rateCards.findByPolicyIdAndBrokerIdAndActiveTrue(p.getId(), p.getBrokerId());
        if (card.isEmpty()) {
            issues.add(new Issue("NO_ACTIVE_RATE_CARD", "No active rate card"));
        } else {
            PremiumType type = premiumTypes.findById(p.getPremiumTypeId()).orElseThrow();
            engine.validate(type, card.get().getRateTable())
                    .forEach(m -> issues.add(new Issue("RATE_TABLE_INVALID", m)));
        }
        if (NEEDS_FAMILY_CONSTRUCT.contains(p.getPolicyType())
                && constructs.findByPolicyIdAndBrokerId(p.getId(), p.getBrokerId()).isEmpty()) {
            issues.add(new Issue("NO_FAMILY_CONSTRUCT", "No family construct for a " + p.getPolicyType() + " policy"));
        }
        return issues;
    }
}
