package com.broker.policy.engine;

import com.broker.policy.domain.PremiumType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** rate_table: {"designations": {"MANAGER": 4000}}. Per member; dependents inherit the employee's designation. */
@Component
class DesignationRater implements Rater {
    public Set<String> raterClasses() { return Set.of("DesignationRater"); }

    public List<String> validate(PremiumType t, RateTable table, Registry r) {
        List<String> p = new ArrayList<>();
        Rates.requireDecimalMap(table, "designations", p);
        return p;
    }

    public List<BigDecimal> rate(PremiumType t, RateTable table, List<Member> members, Registry r) {
        var d = table.map("designations");
        return members.stream().map(m -> Rates.lookup(d, Rates.designation(m, members), "designation")).toList();
    }
}
