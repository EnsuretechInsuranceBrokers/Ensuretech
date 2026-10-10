package com.broker.policy.engine;

import com.broker.policy.domain.PremiumType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Two-dimensional table keyed by grade (GRADE_AGE_BAND) or designation (DESIGNATION_AGE), then age band:
 * {"grade_age_matrix": {"A": [{"from":0,"to":30,"premium":4000}, ...]}} or "designation_age_matrix".
 */
@Component
class MatrixRater implements Rater {
    public Set<String> raterClasses() { return Set.of("MatrixRater"); }

    private static boolean byGrade(PremiumType t) { return t.getConstructRaters().contains("GRADE"); }

    private static String key(PremiumType t) { return byGrade(t) ? "grade_age_matrix" : "designation_age_matrix"; }

    public List<String> validate(PremiumType t, RateTable table, Registry r) {
        List<String> p = new ArrayList<>();
        Map<String, Object> m = table.map(key(t));
        if (m == null || m.isEmpty()) {
            p.add("'" + key(t) + "' must be a non-empty object of band lists");
            return p;
        }
        m.forEach((k, v) -> Rates.requireBands(
                new RateTable(Map.of("x", v)).list("x"), key(t) + "." + k, p));
        return p;
    }

    public List<BigDecimal> rate(PremiumType t, RateTable table, List<Member> members, Registry r) {
        Map<String, Object> matrix = table.map(key(t));
        return members.stream().map(m -> {
            String dim = byGrade(t) ? Rates.grade(m, members) : Rates.designation(m, members);
            if (dim == null || matrix.get(dim) == null) throw new RatingException("no matrix row for '" + dim + "'");
            return Rates.band(new RateTable(Map.of("x", matrix.get(dim))).list("x"), m.age(), "matrix");
        }).toList();
    }
}
