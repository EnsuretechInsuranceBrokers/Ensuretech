package com.broker.policy.engine;

import com.broker.policy.domain.PremiumType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Limit of Liability: {"lol": [{"limit": 500000, "premium": 1800}]}; the member's sum insured must match a limit exactly. */
@Component
class LolRater implements Rater {
    public Set<String> raterClasses() { return Set.of("LOLRater"); }

    public List<String> validate(PremiumType t, RateTable table, Registry r) {
        List<String> p = new ArrayList<>();
        var lol = table.list("lol");
        if (lol == null || lol.isEmpty()) p.add("'lol' must be a non-empty list of {limit,premium}");
        else for (Map<String, Object> e : lol) {
            try {
                RateTable.toDecimal(e.get("limit"));
                if (RateTable.toDecimal(e.get("premium")).signum() < 0) p.add("lol: negative premium");
            } catch (RuntimeException ex) {
                p.add("lol: each entry needs numeric 'limit' and 'premium'");
                break;
            }
        }
        return p;
    }

    public List<BigDecimal> rate(PremiumType t, RateTable table, List<Member> members, Registry r) {
        var lol = table.list("lol");
        return members.stream().map(m -> {
            if (m.sumInsured() == null) throw new RatingException("sum insured required for LOL rating");
            return lol.stream()
                    .filter(e -> RateTable.toDecimal(e.get("limit")).compareTo(m.sumInsured()) == 0)
                    .findFirst()
                    .map(e -> RateTable.toDecimal(e.get("premium")))
                    .orElseThrow(() -> new RatingException("no LOL limit equal to sum insured " + m.sumInsured()));
        }).toList();
    }
}
