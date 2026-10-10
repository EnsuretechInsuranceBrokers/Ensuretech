package com.broker.policy.service;

import com.broker.policy.api.NotFoundException;
import com.broker.policy.domain.RateCardRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Caches rate card BODIES keyed by card id. A card is immutable once written (a change creates a new card and
 * moves the "active" pointer), so a cached body can never be stale and no eviction ordering can go wrong.
 * Separate bean so the @Cacheable proxy is actually used.
 */
@Component
class RateCardLoader {
    private final RateCardRepository rateCards;

    RateCardLoader(RateCardRepository rateCards) {
        this.rateCards = rateCards;
    }

    @Cacheable(cacheNames = RateCardService.CACHE, key = "#cardId")
    @Transactional(readOnly = true)
    public RateCardView byId(long cardId) {
        return rateCards.findById(cardId).map(RateCardView::of)
                .orElseThrow(() -> new NotFoundException("Rate card not found"));
    }
}
