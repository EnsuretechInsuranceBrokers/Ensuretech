package com.broker.policy.service;

import com.broker.policy.api.NotFoundException;
import com.broker.policy.api.ValidationFailedException;
import com.broker.policy.domain.*;
import com.broker.policy.engine.Member;
import com.broker.policy.engine.PremiumEngine;
import com.broker.policy.engine.InvalidRateTableException;
import com.broker.policy.engine.RatingException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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

    /** One family's outcome in a batch: a quote, or the reason it could not be priced. */
    public record FamilyQuote(String ref, PremiumEngine.Quote quote, String error) {}

    /**
     * Prices many families against one policy. Policy, premium type, rate card and relation codes are loaded ONCE,
     * which is the whole point: per-family calls were dominated by repeated lookups, not by pricing.
     * An unpriceable family yields an error entry; it does not fail the batch. Result order = input order.
     */
    @Transactional(readOnly = true)
    public List<FamilyQuote> quoteMany(long brokerId, long policyId, Map<String, List<Member>> families) {
        Policy policy = policies.findByIdAndBrokerId(policyId, brokerId)
                .orElseThrow(() -> new NotFoundException("Policy not found"));
        PremiumType type = premiumTypes.findById(policy.getPremiumTypeId()).orElseThrow();
        RateCardView card = rateCards.getActive(brokerId, policyId);
        Set<String> knownRelations = relations.findAll().stream().map(RelationType::getCode).collect(Collectors.toSet());
        return families.entrySet().stream().map(e -> {
            for (Member m : e.getValue()) {
                if (!knownRelations.contains(m.relationCode())) {
                    return new FamilyQuote(e.getKey(), null, "unknown relation '" + m.relationCode() + "'");
                }
            }
            try {
                return new FamilyQuote(e.getKey(), engine.quote(type, card.table(), card.taxRate(), e.getValue()), null);
            } catch (RatingException | InvalidRateTableException ex) {
                return new FamilyQuote(e.getKey(), null, ex.getMessage());
            }
        }).toList();
    }
}
