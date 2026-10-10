package com.broker.policy.engine;

import com.broker.policy.domain.PremiumType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Base + Enhance + Flex: {"layers": {"base": 1000, "enhance": 300, "flex": 150}}; member.layers selects, default ["base"]. */
@Component
class FlexRater implements Rater {
    public Set<String> raterClasses() { return Set.of("FlexRater"); }

    public List<String> validate(PremiumType t, RateTable table, Registry r) {
        List<String> p = new ArrayList<>();
        Rates.requireDecimalMap(table, "layers", p);
        if (table.map("layers") != null && table.map("layers").get("base") == null) p.add("layers.base is required");
        return p;
    }

    public List<BigDecimal> rate(PremiumType t, RateTable table, List<Member> members, Registry r) {
        Map<String, Object> layers = table.map("layers");
        return members.stream().map(m -> {
            List<String> chosen = m.layers().isEmpty() ? List.of("base") : m.layers();
            BigDecimal sum = BigDecimal.ZERO;
            for (String l : chosen) sum = sum.add(Rates.lookup(layers, l, "layer"));
            return sum;
        }).toList();
    }
}

/** Base + Top-Up: {"base": 1000, "topup": 400}; both always apply, per member. */
@Component
class BaseTopUpRater implements Rater {
    public Set<String> raterClasses() { return Set.of("BaseTopUpRater"); }

    public List<String> validate(PremiumType t, RateTable table, Registry r) {
        List<String> p = new ArrayList<>();
        Rates.requireDecimal(table, "base", p);
        Rates.requireDecimal(table, "topup", p);
        return p;
    }

    public List<BigDecimal> rate(PremiumType t, RateTable table, List<Member> members, Registry r) {
        BigDecimal v = table.decimal("base").add(table.decimal("topup"));
        return members.stream().map(m -> v).toList();
    }
}

/** Wellness + GMC bundle: {"gmc": 1000, "wellness": 250}; both always apply, per member. */
@Component
class BundleRater implements Rater {
    public Set<String> raterClasses() { return Set.of("BundleRater"); }

    public List<String> validate(PremiumType t, RateTable table, Registry r) {
        List<String> p = new ArrayList<>();
        Rates.requireDecimal(table, "gmc", p);
        Rates.requireDecimal(table, "wellness", p);
        return p;
    }

    public List<BigDecimal> rate(PremiumType t, RateTable table, List<Member> members, Registry r) {
        BigDecimal v = table.decimal("gmc").add(table.decimal("wellness"));
        return members.stream().map(m -> v).toList();
    }
}

/** OPD flat benefit: {"opd_flat": 600}, per member. */
@Component
class OpdFlatRater implements Rater {
    public Set<String> raterClasses() { return Set.of("OpdFlatRater"); }

    public List<String> validate(PremiumType t, RateTable table, Registry r) {
        List<String> p = new ArrayList<>();
        Rates.requireDecimal(table, "opd_flat", p);
        return p;
    }

    public List<BigDecimal> rate(PremiumType t, RateTable table, List<Member> members, Registry r) {
        BigDecimal v = table.decimal("opd_flat");
        return members.stream().map(m -> v).toList();
    }
}

/** OPD by grade: {"opd_grades": {"A": 900}}, per member (dependents inherit the employee's grade). */
@Component
class OpdGradeRater implements Rater {
    public Set<String> raterClasses() { return Set.of("OpdGradeRater"); }

    public List<String> validate(PremiumType t, RateTable table, Registry r) {
        List<String> p = new ArrayList<>();
        Rates.requireDecimalMap(table, "opd_grades", p);
        return p;
    }

    public List<BigDecimal> rate(PremiumType t, RateTable table, List<Member> members, Registry r) {
        var g = table.map("opd_grades");
        return members.stream().map(m -> Rates.lookup(g, Rates.grade(m, members), "OPD grade")).toList();
    }
}
