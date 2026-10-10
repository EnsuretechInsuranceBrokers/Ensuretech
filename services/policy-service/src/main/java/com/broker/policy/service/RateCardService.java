package com.broker.policy.service;

import com.broker.policy.api.NotFoundException;
import com.broker.policy.api.ValidationFailedException;
import com.broker.policy.domain.*;
import com.broker.policy.engine.PremiumEngine;
import com.broker.policy.outbox.OutboxPublisher;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RateCardService {
    public static final String CACHE = "rateCards";

    private final PolicyRepository policies;
    private final RateCardRepository rateCards;
    private final PremiumTypeRepository premiumTypes;
    private final PremiumEngine engine;
    private final OutboxPublisher outbox;
    private final RateCardLoader loader;

    RateCardService(PolicyRepository policies, RateCardRepository rateCards, PremiumTypeRepository premiumTypes,
                    PremiumEngine engine, OutboxPublisher outbox, RateCardLoader loader) {
        this.policies = policies;
        this.rateCards = rateCards;
        this.premiumTypes = premiumTypes;
        this.engine = engine;
        this.outbox = outbox;
        this.loader = loader;
    }

    /**
     * Active card for a policy. The pointer lookup always hits the database and carries the tenant check;
     * only the immutable card body comes from Redis (1h TTL), so a replaced card is visible immediately.
     */
    @Transactional(readOnly = true)
    public RateCardView getActive(long brokerId, long policyId) {
        long cardId = rateCards.findActiveCardId(policyId, brokerId)
                .orElseThrow(() -> new NotFoundException("No active rate card for policy"));
        return loader.byId(cardId);
    }

    @Transactional
    public RateCardView replace(long brokerId, long policyId, LocalDate effectiveFrom, BigDecimal taxRate,
                                Map<String, Object> table) {
        Policy policy = policies.findByIdAndBrokerId(policyId, brokerId)
                .orElseThrow(() -> new NotFoundException("Policy not found"));
        PremiumType type = premiumTypes.findById(policy.getPremiumTypeId()).orElseThrow();
        var problems = engine.validate(type, table);
        if (!problems.isEmpty()) {
            throw new ValidationFailedException(problems.stream()
                    .map(m -> new ValidationFailedException.Issue("RATE_TABLE_INVALID", m)).toList());
        }
        rateCards.findByPolicyIdAndBrokerIdAndActiveTrue(policyId, brokerId).ifPresent(old -> {
            old.setActive(false);
            old.setEffectiveTo(effectiveFrom);
            rateCards.saveAndFlush(old);   // free the one-active-per-policy unique index first
        });
        RateCard card = rateCards.saveAndFlush(new RateCard(policyId, brokerId, effectiveFrom, taxRate, table));
        outbox.publish("Policy", policyId, "RateCardChanged", "policy.events", String.valueOf(brokerId),
                Map.of("broker_id", brokerId, "policy_id", policyId, "rate_card_id", card.getId()));
        return RateCardView.of(card);
    }
}
