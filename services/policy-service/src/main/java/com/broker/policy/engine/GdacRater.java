package com.broker.policy.engine;

import com.broker.policy.domain.PremiumType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** rate_table: {"gdac_bands": [{"from","to","premium"}]}, inclusive bounds; per member (boundary ages are scenario-tested). */
@Component
class GdacRater implements Rater {
    public Set<String> raterClasses() { return Set.of("GDACRater"); }

    public List<String> validate(PremiumType t, RateTable table, Registry r) {
        List<String> p = new ArrayList<>();
        Rates.requireBands(table.list("gdac_bands"), "gdac_bands", p);
        return p;
    }

    public List<BigDecimal> rate(PremiumType t, RateTable table, List<Member> members, Registry r) {
        var bands = table.list("gdac_bands");
        return members.stream().map(m -> Rates.band(bands, m.age(), "GDAC")).toList();
    }
}
