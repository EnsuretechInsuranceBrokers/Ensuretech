package com.broker.policy.service;

import com.broker.policy.api.NotFoundException;
import com.broker.policy.api.ValidationFailedException;
import com.broker.policy.domain.*;
import com.broker.policy.engine.PremiumEngine;
import com.broker.policy.outbox.OutboxPublisher;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class RateCardService {
    public static final String CACHE = "rateCards";

    private final PolicyRepository policies;
    private final RateCardRepository rateCards;
    private final PremiumTypeRepository premiumTypes;
    private final PremiumEngine engine;
    private final OutboxPublisher outbox;
    private final CacheManager caches;

    RateCardService(PolicyRepository policies, RateCardRepository rateCards, PremiumTypeRepository premiumTypes,
                    PremiumEngine engine, OutboxPublisher outbox, CacheManager caches) {
        this.policies = policies;
        this.rateCards = rateCards;
        this.premiumTypes = premiumTypes;
        this.engine = engine;
        this.outbox = outbox;
        this.caches = caches;
    }

    static String key(long brokerId, long policyId) { return brokerId + ":" + policyId; }

    /** Cache-aside, keyed by broker AND policy so one tenant can never be served another's entry. */
    @Cacheable(cacheNames = CACHE, key = "#brokerId + ':' + #policyId")
    @Transactional(readOnly = true)
    public RateCardView getActive(long brokerId, long policyId) {
        return rateCards.findByPolicyIdAndBrokerIdAndActiveTrue(policyId, brokerId)
                .map(RateCardView::of)
                .orElseThrow(() -> new NotFoundException("No active rate card for policy"));
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
        evictAfterCommit(key(brokerId, policyId));
        return RateCardView.of(card);
    }

    /** Evict only once the new card is committed, so a concurrent reader cannot re-cache the old one. */
    private void evictAfterCommit(String key) {
        Cache cache = caches.getCache(CACHE);
        if (cache == null) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { cache.evict(key); }
            });
        } else {
            cache.evict(key);
        }
    }
}
