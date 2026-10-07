package com.broker.policy.engine;

import com.broker.policy.domain.PremiumType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * rate_table: {"grades": {"A": 5000, "B": 4000}}. Per member (dependents inherit the employee's grade);
 * family-unit types are priced once on the employee's grade.
 */
@Component
class GradeRater implements Rater {
    public Set<String> raterClasses() { return Set.of("GradeRater"); }

    public List<String> validate(PremiumType t, RateTable table, Registry r) {
        List<String> p = new ArrayList<>();
        Rates.requireDecimalMap(table, "grades", p);
        return p;
    }

    public List<BigDecimal> rate(PremiumType t, RateTable table, List<Member> members, Registry r) {
        var grades = table.map("grades");
        if (Rates.isFamilyUnit(t)) {
            int s = Rates.selfIndex(members);
            return Rates.charge(members, s, Rates.lookup(grades, Rates.grade(members.get(s), members), "grade"));
        }
        return members.stream().map(m -> Rates.lookup(grades, Rates.grade(m, members), "grade")).toList();
    }
}
