package com.broker.policy.service;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Cache-safe (Serializable, detached) snapshot of an active rate card. */
public record RateCardView(Long id, Long policyId, LocalDate effectiveFrom, BigDecimal taxRate,
                           LinkedHashMap<String, Object> rateTable) implements Serializable {
    public static RateCardView of(com.broker.policy.domain.RateCard c) {
        return new RateCardView(c.getId(), c.getPolicyId(), c.getEffectiveFrom(), c.getTaxRate(),
                new LinkedHashMap<>(c.getRateTable()));
    }

    public Map<String, Object> table() { return rateTable; }
}
