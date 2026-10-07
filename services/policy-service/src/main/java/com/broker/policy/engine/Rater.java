package com.broker.policy.engine;

import com.broker.policy.domain.PremiumType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * A rating strategy, selected by premium_types.rater_class. Returns ANNUAL premium before tax; pro-rata is applied
 * by the caller (endorsement engine). Implementations must be stateless.
 */
public interface Rater {
    /** rater_class values this implementation serves. */
    Set<String> raterClasses();

    /** @return human-readable problems; empty when the table can price this premium type. */
    List<String> validate(PremiumType type, RateTable table, Registry registry);

    /** @return one annual premium per member, same order as {@code members}. */
    List<BigDecimal> rate(PremiumType type, RateTable table, List<Member> members, Registry registry);

    interface Registry {
        Rater get(String raterClass);
    }
}
