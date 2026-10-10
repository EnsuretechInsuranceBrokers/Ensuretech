package com.broker.policy.engine;

import com.broker.policy.domain.PremiumType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * rate_table: {"flat": 1200} per member; family-unit types charge {"flat"} once per family on the employee row.
 * FLAT_FAMILY_DEFINITION uses {"family_definitions": {"0": 900, "1": 1500, "2": 2000}} keyed by dependent count.
 * ProRataRater shares this logic: pro-rata itself is applied by the caller.
 */
@Component
class FlatRater implements Rater {
    public Set<String> raterClasses() { return Set.of("FlatRater", "ProRataRater"); }

    public List<String> validate(PremiumType t, RateTable table, Registry r) {
        List<String> p = new ArrayList<>();
        if ("FLAT_FAMILY_DEFINITION".equals(t.getCode())) Rates.requireDecimalMap(table, "family_definitions", p);
        else Rates.requireDecimal(table, "flat", p);
        return p;
    }

    public List<BigDecimal> rate(PremiumType t, RateTable table, List<Member> members, Registry r) {
        if ("FLAT_FAMILY_DEFINITION".equals(t.getCode())) {
            Map<String, Object> defs = table.map("family_definitions");
            int deps = members.size() - 1;
            return Rates.charge(members, Rates.selfIndex(members),
                    Rates.lookup(defs, String.valueOf(deps), "family definition (dependents)"));
        }
        BigDecimal flat = table.decimal("flat");
        if (Rates.isFamilyUnit(t)) {
            Rates.checkDependentCap(t, members);
            return Rates.charge(members, Rates.selfIndex(members), flat);
        }
        return members.stream().map(m -> flat).toList();
    }
}
