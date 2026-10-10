package com.broker.policy.engine;

import com.broker.policy.domain.PremiumType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared helpers for raters. */
final class Rates {
    private Rates() {}

    private static final Map<String, Integer> MAX_DEPENDENTS = Map.of(
            "FLAT_EMPLOYEE_ONLY", 0, "FLAT_EMPLOYEE_PLUS_1", 1, "FLAT_EMPLOYEE_PLUS_2", 2);
    private static final Set<String> FAMILY_CODES = MAX_DEPENDENTS.keySet();

    /** Family-unit types charge one premium for the whole family, booked on the employee row. */
    static boolean isFamilyUnit(PremiumType t) {
        return (t.getConstructRaters() != null && t.getConstructRaters().contains("FAMILY"))
                || FAMILY_CODES.contains(t.getCode());
    }

    static int selfIndex(List<Member> members) {
        for (int i = 0; i < members.size(); i++) {
            if ("SELF".equals(members.get(i).relationCode())) return i;
        }
        return 0;
    }

    static List<BigDecimal> charge(List<Member> members, int index, BigDecimal premium) {
        List<BigDecimal> out = new ArrayList<>(Collections.nCopies(members.size(), BigDecimal.ZERO));
        out.set(index, premium);
        return out;
    }

    static void checkDependentCap(PremiumType t, List<Member> members) {
        Integer cap = MAX_DEPENDENTS.get(t.getCode());
        int dependents = members.size() - 1;
        if (cap != null && dependents > cap) {
            throw new RatingException(t.getCode() + " covers at most " + cap + " dependent(s), got " + dependents);
        }
    }

    static String grade(Member m, List<Member> all) {
        if (m.grade() != null && !m.grade().isBlank()) return m.grade();
        return all.get(selfIndex(all)).grade();
    }

    static String designation(Member m, List<Member> all) {
        if (m.designation() != null && !m.designation().isBlank()) return m.designation();
        return all.get(selfIndex(all)).designation();
    }

    static BigDecimal lookup(Map<String, Object> table, String key, String what) {
        if (key == null || table == null || table.get(key) == null) {
            throw new RatingException("no rate for " + what + " '" + key + "'");
        }
        return RateTable.toDecimal(table.get(key));
    }

    static BigDecimal band(List<Map<String, Object>> bands, int age, String what) {
        for (Map<String, Object> b : bands) {
            if (age >= RateTable.toDecimal(b.get("from")).intValueExact()
                    && age <= RateTable.toDecimal(b.get("to")).intValueExact()) {
                return RateTable.toDecimal(b.get("premium"));
            }
        }
        throw new RatingException("no " + what + " band for age " + age);
    }

    // ---- validation helpers -------------------------------------------------------------------------------

    static void requireDecimal(RateTable t, String key, List<String> problems) {
        try {
            BigDecimal v = t.decimal(key);
            if (v == null) problems.add("'" + key + "' is required");
            else if (v.signum() < 0) problems.add("'" + key + "' must not be negative");
        } catch (NumberFormatException e) {
            problems.add("'" + key + "' must be a number");
        }
    }

    static void requireDecimalMap(RateTable t, String key, List<String> problems) {
        Map<String, Object> m = t.map(key);
        if (m == null || m.isEmpty()) {
            problems.add("'" + key + "' must be a non-empty object");
            return;
        }
        m.forEach((k, v) -> {
            try {
                if (RateTable.toDecimal(v).signum() < 0) problems.add(key + "." + k + " must not be negative");
            } catch (NumberFormatException e) {
                problems.add(key + "." + k + " must be a number");
            }
        });
    }

    static void requireBands(List<Map<String, Object>> bands, String key, List<String> problems) {
        if (bands == null || bands.isEmpty()) {
            problems.add("'" + key + "' must be a non-empty list of {from,to,premium}");
            return;
        }
        List<int[]> ranges = new ArrayList<>();
        for (Map<String, Object> b : bands) {
            try {
                int from = RateTable.toDecimal(b.get("from")).intValueExact();
                int to = RateTable.toDecimal(b.get("to")).intValueExact();
                if (RateTable.toDecimal(b.get("premium")).signum() < 0) problems.add(key + ": negative premium");
                if (from > to) problems.add(key + ": band " + from + "-" + to + " has from > to");
                ranges.add(new int[] {from, to});
            } catch (RuntimeException e) {
                problems.add(key + ": each band needs integer 'from','to' and numeric 'premium'");
                return;
            }
        }
        ranges.sort((a, b) -> Integer.compare(a[0], b[0]));
        for (int i = 1; i < ranges.size(); i++) {
            if (ranges.get(i)[0] <= ranges.get(i - 1)[1]) problems.add(key + ": overlapping age bands");
        }
    }
}
