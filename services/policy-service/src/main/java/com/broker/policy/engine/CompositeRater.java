package com.broker.policy.engine;

import com.broker.policy.domain.PremiumType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Sum of independent sections, each with its own rater and table:
 * {"sections": [{"rater": "GradeRater", "table": {"grades": {...}}}, {"rater": "AgeRater", "table": {...}}]}.
 * Serves CompositeRater and ConstructRater. Sections cannot themselves be composite.
 */
@Component
class CompositeRater implements Rater {
    private static final Set<String> SELF = Set.of("CompositeRater", "ConstructRater");

    public Set<String> raterClasses() { return SELF; }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sectionTable(Map<String, Object> s) {
        return s.get("table") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    public List<String> validate(PremiumType t, RateTable table, Registry reg) {
        List<String> p = new ArrayList<>();
        var sections = table.list("sections");
        if (sections == null || sections.isEmpty()) {
            p.add("'sections' must be a non-empty list of {rater, table}");
            return p;
        }
        int i = 0;
        for (Map<String, Object> s : sections) {
            String cls = String.valueOf(s.get("rater"));
            Rater r = reg.get(cls);
            if (r == null || SELF.contains(cls)) {
                p.add("sections[" + i + "]: unknown or non-nestable rater '" + cls + "'");
            } else {
                final int idx = i;
                r.validate(t, new RateTable(sectionTable(s)), reg).forEach(x -> p.add("sections[" + idx + "]: " + x));
            }
            i++;
        }
        return p;
    }

    public List<BigDecimal> rate(PremiumType t, RateTable table, List<Member> members, Registry reg) {
        List<BigDecimal> total = new ArrayList<>(java.util.Collections.nCopies(members.size(), BigDecimal.ZERO));
        for (Map<String, Object> s : table.list("sections")) {
            List<BigDecimal> part = reg.get(String.valueOf(s.get("rater")))
                    .rate(t, new RateTable(sectionTable(s)), members, reg);
            for (int i = 0; i < total.size(); i++) total.set(i, total.get(i).add(part.get(i)));
        }
        return total;
    }
}
