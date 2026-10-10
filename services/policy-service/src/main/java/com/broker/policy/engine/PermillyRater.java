package com.broker.policy.engine;

import com.broker.policy.domain.PremiumType;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** {"permille": 3.5}: premium = sum insured x permille / 1000, per member. */
@Component
class PermillyRater implements Rater {
    public Set<String> raterClasses() { return Set.of("PermillyRater"); }

    public List<String> validate(PremiumType t, RateTable table, Registry r) {
        List<String> p = new ArrayList<>();
        Rates.requireDecimal(table, "permille", p);
        return p;
    }

    public List<BigDecimal> rate(PremiumType t, RateTable table, List<Member> members, Registry r) {
        BigDecimal permille = table.decimal("permille");
        return members.stream().map(m -> {
            if (m.sumInsured() == null) throw new RatingException("sum insured required for permille rating");
            return m.sumInsured().multiply(permille).divide(BigDecimal.valueOf(1000), MathContext.DECIMAL64);
        }).toList();
    }
}
