package com.broker.policy.service;

import com.broker.policy.api.NotFoundException;
import com.broker.policy.api.ValidationFailedException;
import com.broker.policy.domain.*;
import com.broker.policy.engine.Member;
import com.broker.policy.engine.PremiumEngine;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PremiumService {
    private final PolicyRepository policies;
    private final PremiumTypeRepository premiumTypes;
    private final RelationTypeRepository relations;
    private final RateCardService rateCards;
    private final PremiumEngine engine;

    PremiumService(PolicyRepository policies, PremiumTypeRepository premiumTypes, RelationTypeRepository relations,
                   RateCardService rateCards, PremiumEngine engine) {
        this.policies = policies;
        this.premiumTypes = premiumTypes;
        this.relations = relations;
        this.rateCards = rateCards;
        this.engine = engine;
    }

    /** Annual, pre-tax premiums + tax for the given members under the policy's active rate card. */
    @Transactional(readOnly = true)
    public PremiumEngine.Quote quote(long brokerId, long policyId, List<Member> members) {
        Policy policy = policies.findByIdAndBrokerId(policyId, brokerId)
                .orElseThrow(() -> new NotFoundException("Policy not found"));
        for (Member m : members) {
            if (relations.findByCode(m.relationCode()).isEmpty()) {
                throw new ValidationFailedException("UNKNOWN_RELATION", "Unknown relation '" + m.relationCode() + "'");
            }
        }
        PremiumType type = premiumTypes.findById(policy.getPremiumTypeId()).orElseThrow();
        RateCardView card = rateCards.getActive(brokerId, policyId);
        return engine.quote(type, card.table(), card.taxRate(), members);
    }
}
