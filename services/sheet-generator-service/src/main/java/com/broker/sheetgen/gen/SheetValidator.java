package com.broker.sheetgen.gen;

import com.broker.sheetgen.spec.SheetSpec;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Checks a sheet (ADD, UPDATE or REMOVE) against a policy's sheet-spec. The generator's own output must pass this with no issues. */
public final class SheetValidator {
    public static final int MAX_ISSUES = 1000;

    private final SheetSpec spec;
    private final LocalDate today;
    private final List<Issue> issues = new ArrayList<>();

    private SheetValidator(SheetSpec spec, LocalDate today) { this.spec = spec; this.today = today; }

    /** @param rows each row keyed by the sheet's header text (any spelling the column table knows). */
    public static List<Issue> validate(SheetSpec spec, String operation, List<Map<String, String>> rows, LocalDate today) {
        SheetValidator v = new SheetValidator(spec, today);
        List<Map<String, String>> byId = new ArrayList<>(rows.size());
        Set<String> present = new HashSet<>();
        for (Map<String, String> raw : rows) {
            Map<String, String> r = new HashMap<>();
            raw.forEach((h, val) -> {
                String id = Column.idForHeader(h);
                if (id != null) { r.put(id, val == null ? "" : val.trim()); present.add(id); }
            });
            byId.add(r);
        }
        if (rows.isEmpty()) v.add(0, "EMPTY_SHEET", "The sheet has no data rows");
        for (String need : required(operation)) {
            if (!present.contains(need)) v.add(0, "MISSING_COLUMN", "Column '" + Column.headerOf(need) + "' is missing");
        }
        switch (operation) {
            case "ADD" -> { v.adding = true; v.checkAdd(byId); }
            case "UPDATE", "REMOVE" -> v.checkChange(operation, byId);
            default -> v.add(0, "UNKNOWN_OPERATION", "Operation must be ADD, UPDATE or REMOVE");
        }
        return v.issues;
    }

    private static List<String> required(String op) {
        return switch (op) {
            case "ADD" -> List.of(Column.EMP_CODE, Column.RELATION, Column.EMP_DOB, Column.MEM_DOB, Column.SUM_INSURED, Column.EFFECTIVE);
            case "UPDATE", "REMOVE" -> List.of(Column.EMP_CODE, Column.RELATION, Column.EFFECTIVE);
            default -> List.of();
        };
    }

    private void add(int row, String code, String msg) {
        if (issues.size() < MAX_ISSUES) issues.add(new Issue(row, code, msg));
    }

    // ── ADD ─────────────────────────────────────────────────────────────────────────────────────────────────
    private record Line(int row, Map<String, String> v, String relation, boolean self, LocalDate dob) {}

    private void checkAdd(List<Map<String, String>> rows) {
        Map<String, List<Line>> families = new LinkedHashMap<>();
        Set<String> closed = new HashSet<>(), emails = new HashSet<>();
        String current = null;
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> r = rows.get(i);
            int n = i + 1;
            String code = r.getOrDefault(Column.EMP_CODE, "");
            if (code.isEmpty()) { add(n, "EMPLOYEE_CODE_MISSING", "Employee Code is blank"); continue; }
            if (!code.equals(current)) {
                if (current != null) closed.add(current);
                if (closed.contains(code)) add(n, "DUPLICATE_EMPLOYEE_CODE", "Employee code " + code + " appears in two separate blocks");
                current = code;
            }
            String rel = r.getOrDefault(Column.RELATION, "").toUpperCase(Locale.ROOT);
            boolean self = "SELF".equals(rel);
            if (rel.isEmpty()) { add(n, "RELATION_MISSING", "Relationship is blank"); continue; }
            if (!spec.covers(rel)) add(n, "RELATION_NOT_COVERED", rel + " is not covered by this policy");
            LocalDate dob = identity(n, r, self);
            families.computeIfAbsent(code, k -> new ArrayList<>()).add(new Line(n, r, rel, self, dob));
            checkCommon(n, r, self);
            if (self) {
                String em = r.getOrDefault(Column.EMP_EMAIL, "").toLowerCase(Locale.ROOT);
                if (!em.isEmpty() && !emails.add(em)) add(n, "DUPLICATE_EMAIL", "Email " + em + " is used by two employees");
            }
        }
        families.forEach((code, lines) -> checkFamily(code, lines));
    }

    /** Name, gender and DOB of the person on this row (the employee block for SELF, the insured-member block otherwise). */
    private LocalDate identity(int n, Map<String, String> r, boolean self) {
        String first = r.getOrDefault(self ? Column.EMP_FIRST : Column.MEM_FIRST, ""), dobText = r.getOrDefault(self ? Column.EMP_DOB : Column.MEM_DOB, "");
        String gender = r.getOrDefault(self ? Column.EMP_GENDER : Column.MEM_GENDER, "");
        if (first.isEmpty()) add(n, "NAME_MISSING", "First name is blank");
        if (!gender.isEmpty() && !Set.of("male", "female", "m", "f").contains(gender.toLowerCase(Locale.ROOT))) add(n, "GENDER_INVALID", "Gender '" + gender + "' is not Male or Female");
        LocalDate dob = Rules.parseDate(dobText);
        if (dobText.isEmpty()) add(n, "DOB_MISSING", "Date of birth is blank");
        else if (dob == null) add(n, "DOB_UNREADABLE", "Date of birth '" + dobText + "' is not dd-MM-yyyy");
        else if (!dob.isBefore(today)) add(n, "DOB_IN_FUTURE", "Date of birth is not in the past");
        return dob;
    }

    /** Checks that apply to every row of ADD, UPDATE and REMOVE sheets alike. */
    private void checkCommon(int n, Map<String, String> r, boolean self) {
        String eff = r.getOrDefault(Column.EFFECTIVE, "");
        LocalDate e = Rules.parseDate(eff);
        if (eff.isEmpty()) add(n, "EFFECTIVE_DATE_MISSING", "Effective Date is blank");
        else if (e == null) add(n, "EFFECTIVE_DATE_UNREADABLE", "Effective Date '" + eff + "' is not dd-MM-yyyy");
        else if (e.isBefore(spec.start()) || e.isAfter(spec.end())) add(n, "EFFECTIVE_DATE_OUTSIDE_POLICY", "Effective Date " + eff + " is outside the policy period " + spec.start() + " .. " + spec.end());
        checkSumInsured(n, r);
        if (self) {
            checkChoice(n, r, Column.GRADE, spec.needsGrade(), spec.grades(), "grade");
            checkChoice(n, r, Column.DESIGNATION, spec.needsDesignation(), spec.designations(), "designation");
        }
    }

    private void checkSumInsured(int n, Map<String, String> r) {
        String si = r.getOrDefault(Column.SUM_INSURED, "");
        if (si.isEmpty()) return;                      // required-ness is the caller's: ADD demands it, UPDATE may leave it alone
        BigDecimal v = number(si);
        if (v == null || v.signum() <= 0) { add(n, "SUM_INSURED_INVALID", "Sum Insured '" + si + "' is not a positive number"); return; }
        if (spec.siOneOf() && spec.siValues().stream().noneMatch(x -> new BigDecimal(x).compareTo(v) == 0)) {
            add(n, "SUM_INSURED_NOT_OFFERED", "Sum Insured " + si + " is not one of " + spec.siValues());
        }
    }

    private void checkChoice(int n, Map<String, String> r, String col, boolean needed, List<String> allowed, String what) {
        String v = r.getOrDefault(col, "");
        if (v.isEmpty()) {
            if (needed && isAddRow(r)) add(n, what.toUpperCase(Locale.ROOT) + "_MISSING", "The policy rates by " + what + " but the employee row has none");
        } else if (!allowed.isEmpty() && !allowed.contains(v)) {
            add(n, what.toUpperCase(Locale.ROOT) + "_UNKNOWN", "'" + v + "' is not a " + what + " on the rate card " + allowed);
        }
    }

    private boolean isAddRow(Map<String, String> r) { return adding; }

    private boolean adding;       // grade / designation are mandatory only when members are being added

    private void checkFamily(String code, List<Line> lines) {
        int first = lines.get(0).row();
        long selves = lines.stream().filter(Line::self).count();
        if (selves != 1) add(first, "SELF_COUNT", "Employee " + code + ": exactly one SELF row is required, found " + selves);
        if (lines.size() > spec.maxMembers()) add(first, "MAX_MEMBERS_EXCEEDED", code + ": " + lines.size() + " members, the policy allows " + spec.maxMembers());
        Map<String, Integer> perRelation = new HashMap<>();
        lines.forEach(l -> perRelation.merge(l.relation(), 1, Integer::sum));
        perRelation.forEach((rel, c) -> { if (c > 1) add(first, "DUPLICATE_RELATION", code + ": " + rel + " appears " + c + " times"); });

        int children = 0, parents = 0, inLaws = 0;
        List<Rules.Person> people = new ArrayList<>();
        Map<LocalDate, List<Line>> byDob = new LinkedHashMap<>();
        for (Line l : lines) {
            if (l.dob() == null) continue;
            String g = Rules.groupOf(l.relation(), spec);
            people.add(new Rules.Person(l.relation(), g, Rules.ageOn(l.dob(), today)));
            if ("CHILD".equals(g)) { children++; byDob.computeIfAbsent(l.dob(), k -> new ArrayList<>()).add(l); }
            if ("PARENT".equals(g)) parents++;
            if ("PARENT_IN_LAW".equals(g)) inLaws++;
        }
        if (spec.maxChildren() != null && children > spec.maxChildren()) add(first, "MAX_CHILDREN_EXCEEDED", code + ": " + children + " children, the policy allows " + spec.maxChildren());
        if (spec.maxParents() != null && parents + inLaws > spec.maxParents()) add(first, "MAX_PARENTS_EXCEEDED", code + ": " + (parents + inLaws) + " parents / in-laws, the policy allows " + spec.maxParents());
        if (parents > 0 && inLaws > 0) add(first, "PARENTS_AND_IN_LAWS", code + ": parents and parents-in-law cannot both be covered");
        byDob.forEach((dob, same) -> {
            int limit = same.stream().mapToInt(l -> spec.maxTwinsFor(l.relation())).min().orElse(1);
            if (same.size() > limit) add(same.get(0).row(), "TWINS_NOT_ALLOWED", code + ": " + same.size() + " children share DOB " + Rules.fmt(dob) + " but the policy allows " + limit);
        });
        for (Line l : lines) checkAges(code, l, people);
        checkDates(code, lines);
    }

    private void checkAges(String code, Line l, List<Rules.Person> people) {
        if (l.dob() == null) return;
        int age = Rules.ageOn(l.dob(), today);
        String rel = l.relation(), group = Rules.groupOf(rel, spec);
        Integer min = spec.minAgeFor(rel), max = spec.maxAgeFor(rel);
        if (min == null && Rules.ADULT_GROUPS.contains(group) && age < Rules.ADULT_AGE) add(l.row(), "ADULT_UNDER_18", rel + " must be at least 18, is " + age);
        if (min != null && age < min) add(l.row(), "RELATION_AGE_BELOW_MIN", rel + " age " + age + " is below " + min);
        if (max != null && age > max) add(l.row(), "RELATION_AGE_ABOVE_MAX", rel + " age " + age + " is above " + max);
        if ((spec.ageMin() != null && age < spec.ageMin()) || (spec.ageMax() != null && age > spec.ageMax())) {
            add(l.row(), "AGE_OUT_OF_RANGE", rel + " age " + age + " is outside " + spec.ageMin() + ".." + spec.ageMax());
        }
        List<Rules.Person> others = new ArrayList<>(people);
        others.remove(new Rules.Person(rel, group, age));
        if (!Rules.gapOk(spec, rel, age, others)) add(l.row(), "AGE_GAP_TOO_SMALL", code + ": " + rel + " is too close in age to the " + spec.rules().get(rel).differenceFrom().toLowerCase(Locale.ROOT)
                + " (the policy requires " + spec.rules().get(rel).ageDifference() + " years)");
    }

    private void checkDates(String code, List<Line> lines) {
        Line self = lines.stream().filter(Line::self).findFirst().orElse(null);
        Line spouse = lines.stream().filter(l -> "SPOUSE".equals(Rules.groupOf(l.relation(), spec))).findFirst().orElse(null);
        for (Line l : lines) {
            String domText = l.v().getOrDefault(Column.DOM, "");
            if (!domText.isEmpty()) {
                LocalDate dom = Rules.parseDate(domText);
                if (dom == null) { add(l.row(), "DOM_UNREADABLE", "Date of Marriage '" + domText + "' is not dd-MM-yyyy"); }
                else if (spouse == null) add(l.row(), "DOM_WITHOUT_SPOUSE", code + ": Date of Marriage given but the family has no spouse");
                else {
                    if (!dom.isBefore(today)) add(l.row(), "DOM_NOT_IN_PAST", "Date of Marriage " + domText + " is not in the past");
                    for (Line p : new Line[]{self, spouse}) {
                        if (p != null && p.dob() != null && dom.isBefore(p.dob().plusYears(Rules.ADULT_AGE))) {
                            add(l.row(), "DOM_UNDER_18", "Date of Marriage " + domText + " is before the " + (p == self ? "employee" : "spouse") + " turned 18");
                        }
                    }
                }
            }
            if (l.self()) {
                String dojText = l.v().getOrDefault(Column.EMP_DOJ, "");
                LocalDate doj = Rules.parseDate(dojText);
                if (!dojText.isEmpty() && doj == null) add(l.row(), "DOJ_UNREADABLE", "Date of Joining '" + dojText + "' is not dd-MM-yyyy");
                else if (doj != null && l.dob() != null && doj.isBefore(l.dob().plusYears(Rules.ADULT_AGE))) add(l.row(), "DOJ_UNDER_18", "Date of Joining is before the employee turned 18");
                if (spec.salaryBased()) {
                    BigDecimal s = number(l.v().getOrDefault(Column.SALARY, ""));
                    if (s == null || s.signum() <= 0) add(l.row(), "SALARY_MISSING", "The sum insured is a salary multiple but the employee row has no annual salary");
                }
            }
            if (spec.noRule()) {
                for (String pc : new String[]{Column.EMP_PREMIUM, Column.ER_PREMIUM}) {
                    BigDecimal p = number(l.v().getOrDefault(pc, ""));
                    if (p == null || p.signum() < 0) add(l.row(), "PREMIUM_MISSING", "The policy has no rate rule, so " + Column.headerOf(pc) + " must be given on every row");
                }
            }
        }
    }

    // ── UPDATE / REMOVE ─────────────────────────────────────────────────────────────────────────────────────
    private void checkChange(String op, List<Map<String, String>> rows) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> r = rows.get(i);
            int n = i + 1;
            String code = r.getOrDefault(Column.EMP_CODE, ""), rel = r.getOrDefault(Column.RELATION, "").toUpperCase(Locale.ROOT);
            if (code.isEmpty() || rel.isEmpty()) { add(n, "KEY_MISSING", "Employee Code and Relationship identify the member and are both required"); continue; }
            if (!spec.covers(rel)) add(n, "RELATION_NOT_COVERED", rel + " is not covered by this policy");
            if (!seen.add(code + "|" + rel)) add(n, "DUPLICATE_MEMBER", code + " " + rel + " appears twice");
            checkCommon(n, r, "SELF".equals(rel));
            if ("UPDATE".equals(op)) {
                boolean any = Column.ALL.stream().map(Column::id).filter(id -> !Set.of(Column.EMP_CODE, Column.RELATION, Column.EFFECTIVE).contains(id))
                        .anyMatch(id -> !r.getOrDefault(id, "").isEmpty());
                if (!any) add(n, "NOTHING_TO_UPDATE", "An UPDATE row must change at least one value");
            }
        }
    }

    static BigDecimal number(String s) {
        try {
            return s == null || s.isBlank() ? null : new BigDecimal(s.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
