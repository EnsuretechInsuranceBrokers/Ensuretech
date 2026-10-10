package com.broker.sheetgen.gen;

import com.broker.sheetgen.api.BadRequestException;
import com.broker.sheetgen.spec.SheetSpec;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Builds REMOVE and UPDATE sheets from an earlier (ADD) sheet, so the members they name really exist. Stateless: the caller hands the
 * earlier rows back; nothing about a previous run is remembered here.
 */
public final class SheetDerive {
    private SheetDerive() {}

    public enum RemoveMode { FAMILY, MEMBER, MIX }

    private static final String[] NAMES = {"Aarav", "Vivaan", "Arjun", "Rohan", "Priya", "Ananya", "Meera", "Shreya", "Neha", "Kavya", "Rahul", "Amit", "Sneha", "Divya"};

    private record Family(String code, List<Map<String, String>> rows) {}

    /** Source rows keyed by header text → keyed by column id, grouped by employee code in file order. */
    private static List<Family> families(List<Map<String, String>> source) {
        Map<String, Family> by = new LinkedHashMap<>();
        for (Map<String, String> raw : source) {
            Map<String, String> r = new LinkedHashMap<>();
            raw.forEach((h, v) -> { String id = Column.idForHeader(h); if (id != null) r.put(id, v == null ? "" : v.trim()); });
            String code = r.getOrDefault(Column.EMP_CODE, "");
            if (code.isEmpty() || r.getOrDefault(Column.RELATION, "").isEmpty()) continue;
            r.put(Column.RELATION, r.get(Column.RELATION).toUpperCase(Locale.ROOT));
            by.computeIfAbsent(code, k -> new Family(k, new ArrayList<>())).rows().add(r);
        }
        if (by.isEmpty()) throw new BadRequestException("The source sheet has no rows with an Employee Code and a Relationship");
        return new ArrayList<>(by.values());
    }

    /** Leaving / change date: not before the member joined, inside the policy period, and not in the future unless the policy itself is. */
    private static LocalDate changeDate(SheetSpec spec, Map<String, String> row, LocalDate today, Random rnd) {
        LocalDate joined = Rules.parseDate(row.get(Column.EFFECTIVE));
        LocalDate lo = joined == null || joined.isBefore(spec.start()) ? spec.start() : joined;
        if (lo.isAfter(spec.end())) return spec.end();
        LocalDate hi = spec.end().isBefore(today) ? spec.end() : (today.isBefore(lo) ? lo : today);
        return lo.plusDays(rnd.nextInt((int) ChronoUnit.DAYS.between(lo, hi) + 1));
    }

    public static Sheet remove(SheetSpec spec, List<Map<String, String>> source, int families, RemoveMode mode, long seed, LocalDate today) {
        if (families < 1) throw new BadRequestException("families must be at least 1");
        Random rnd = new Random(seed);
        List<Family> all = families(source);
        Collections.shuffle(all, rnd);
        List<Column> cols = Column.ALL.stream().filter(c -> Set.of(Column.EMP_CODE, Column.EMP_FIRST, Column.EMP_LAST, Column.RELATION, Column.MEM_FIRST,
                Column.MEM_LAST, Column.EFFECTIVE).contains(c.id())).toList();
        List<Map<String, String>> out = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int taken = 0;
        for (Family f : all) {
            if (taken >= families) break;
            List<Map<String, String>> dependants = f.rows().stream().filter(r -> !"SELF".equals(r.get(Column.RELATION))).toList();
            boolean whole = mode == RemoveMode.FAMILY || (mode == RemoveMode.MIX && (dependants.isEmpty() || rnd.nextBoolean()));
            if (!whole && dependants.isEmpty()) continue;
            List<Map<String, String>> chosen = whole ? f.rows() : List.of(dependants.get(rnd.nextInt(dependants.size())));
            LocalDate date = changeDate(spec, f.rows().get(0), today, rnd);
            for (Map<String, String> s : chosen) {
                Map<String, String> r = new LinkedHashMap<>();
                for (Column c : cols) if (s.containsKey(c.id())) r.put(c.id(), s.get(c.id()));
                r.put(Column.EFFECTIVE, Rules.fmt(date));
                out.add(r);
            }
            taken++;
        }
        if (taken < families) warnings.add("Only " + taken + " of the " + families + " requested families could be used");
        return new Sheet("REMOVE", cols, out, warnings);
    }

    public static Sheet update(SheetSpec spec, List<Map<String, String>> source, int count, long seed, LocalDate today) {
        if (count < 1) throw new BadRequestException("count must be at least 1");
        Random rnd = new Random(seed);
        List<Family> all = families(source);
        Collections.shuffle(all, rnd);
        List<Map<String, String>> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        int made = 0;
        for (Family f : all) {
            if (made >= count) break;
            Map<String, String> self = f.rows().stream().filter(r -> "SELF".equals(r.get(Column.RELATION))).findFirst().orElse(null);
            List<String> kinds = new ArrayList<>(List.of("NAME"));
            if (self != null) kinds.addAll(List.of("MOBILE", "EMAIL"));
            if (spec.siOneOf() && spec.siValues().size() > 1) kinds.add("SUM_INSURED");
            if (self != null && spec.needsGrade() && spec.grades().size() > 1) kinds.add("GRADE");
            if (self != null && spec.needsDesignation() && spec.designations().size() > 1) kinds.add("DESIGNATION");
            if (self != null && spec.salaryBased()) kinds.add("SALARY");
            String kind = kinds.get(rnd.nextInt(kinds.size()));
            LocalDate date = changeDate(spec, f.rows().get(0), today, rnd);
            String eff = Rules.fmt(date);
            switch (kind) {
                case "NAME" -> {
                    Map<String, String> m = f.rows().get(rnd.nextInt(f.rows().size()));
                    boolean s = "SELF".equals(m.get(Column.RELATION));
                    String col = s ? Column.EMP_FIRST : Column.MEM_FIRST, old = m.getOrDefault(col, "");
                    String fresh;
                    do { fresh = NAMES[rnd.nextInt(NAMES.length)]; } while (fresh.equals(old));
                    out.add(row(f.code(), m.get(Column.RELATION), eff, col, fresh));
                }
                case "SUM_INSURED" -> {
                    String old = f.rows().get(0).getOrDefault(Column.SUM_INSURED, "");
                    List<String> others = spec.siValues().stream().filter(v -> !v.equals(old)).toList();
                    String fresh = others.get(rnd.nextInt(others.size()));
                    for (Map<String, String> m : f.rows()) out.add(row(f.code(), m.get(Column.RELATION), eff, Column.SUM_INSURED, fresh));
                }
                case "GRADE", "DESIGNATION" -> {
                    boolean g = kind.equals("GRADE");
                    List<String> pool = g ? spec.grades() : spec.designations();
                    String col = g ? Column.GRADE : Column.DESIGNATION, old = self.getOrDefault(col, "");
                    List<String> others = pool.stream().filter(v -> !v.equals(old)).toList();
                    out.add(row(f.code(), "SELF", eff, col, others.get(rnd.nextInt(others.size()))));
                }
                case "SALARY" -> out.add(row(f.code(), "SELF", eff, Column.SALARY, String.valueOf(300_000 + 10_000 * rnd.nextInt(371))));
                case "MOBILE" -> out.add(row(f.code(), "SELF", eff, Column.EMP_MOBILE, "9" + String.format("%09d", 100_000_000L + rnd.nextInt(800_000_000))));
                default -> {
                    String old = self.getOrDefault(Column.EMP_EMAIL, "");
                    int at = old.indexOf('@');
                    String fresh = at > 0 ? old.substring(0, at) + ".u" + (1 + rnd.nextInt(99)) + old.substring(at) : f.code().toLowerCase(Locale.ROOT) + ".u@ensuretech.test";
                    out.add(row(f.code(), "SELF", eff, Column.EMP_EMAIL, fresh));
                }
            }
            made++;
            used.add(f.code());
        }
        Set<String> changed = new HashSet<>(List.of(Column.EMP_CODE, Column.RELATION, Column.EFFECTIVE));
        out.forEach(r -> changed.addAll(r.keySet()));
        List<Column> cols = Column.ALL.stream().filter(c -> changed.contains(c.id())).toList();
        List<String> warnings = made < count ? List.of("Only " + made + " of the " + count + " requested updates could be made (one per family)") : List.of();
        return new Sheet("UPDATE", cols, out, warnings);
    }

    private static Map<String, String> row(String code, String relation, String eff, String col, String value) {
        Map<String, String> r = new LinkedHashMap<>();
        r.put(Column.EMP_CODE, code);
        r.put(Column.RELATION, relation);
        r.put(col, value);
        r.put(Column.EFFECTIVE, eff);
        return r;
    }
}
