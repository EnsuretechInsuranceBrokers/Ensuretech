package com.broker.policy.engine;

import com.broker.policy.domain.PremiumType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * rate_table: {"age_bands": [{"from":0,"to":17,"premium":900}, ...]} inclusive bounds, no overlaps.
 * Per member; family-unit types (FAMILY_FLOATER_AGE) are priced once on the ELDEST member's band.
 */
@Component
class AgeRater implements Rater {
    public Set<String> raterClasses() { return Set.of("AgeRater", "ProRataAgeRater"); }

    public List<String> validate(PremiumType t, RateTable table, Registry r) {
        List<String> p = new ArrayList<>();
        Rates.requireBands(table.list("age_bands"), "age_bands", p);
        return p;
    }

    public List<BigDecimal> rate(PremiumType t, RateTable table, List<Member> members, Registry r) {
        var bands = table.list("age_bands");
        if (Rates.isFamilyUnit(t)) {
            int eldest = members.stream().mapToInt(Member::age).max().orElse(0);
            return Rates.charge(members, Rates.selfIndex(members), Rates.band(bands, eldest, "age"));
        }
        return members.stream().map(m -> Rates.band(bands, m.age(), "age")).toList();
    }
}
