package com.broker.sheetgen.gen;

import com.broker.sheetgen.spec.SheetSpec;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * The checks the generator builds toward and the validator enforces: one implementation, so a generated sheet cannot disagree with
 * the validation of the same rule. Ages are whole years as of the date of the run (the same convention policy-service uses).
 */
public final class Rules {
    private Rules() {}

    public static final DateTimeFormatter SHEET_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    public static final int ADULT_AGE = 18;
    /** Realism only (not a legacy rule): a parent is at least this much older than the child. */
    public static final int MIN_PARENT_CHILD_YEARS = 18;
    public static final Set<String> ADULT_GROUPS = Set.of("SELF", "SPOUSE", "PARENT", "PARENT_IN_LAW");

    public static String fmt(LocalDate d) { return d.format(SHEET_DATE); }

    /** dd-MM-yyyy or ISO yyyy-MM-dd; null when blank or unreadable (callers tell the two apart with {@link #blank}). */
    public static LocalDate parseDate(String s) {
        if (blank(s)) return null;
        String t = s.trim();
        try {
            return t.matches("\\d{4}-\\d{2}-\\d{2}") ? LocalDate.parse(t) : LocalDate.parse(t, SHEET_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public static boolean blank(String s) { return s == null || s.isBlank(); }

    public static int ageOn(LocalDate dob, LocalDate on) { return Period.between(dob, on).getYears(); }

    public static String groupOf(String code, SheetSpec spec) {
        if ("SELF".equals(code)) return "SELF";
        SheetSpec.Allowed a = spec.allowed().get(code);
        return a != null && a.group() != null ? a.group() : fallbackGroup(code);
    }

    /** Group of a relation the policy does not cover (needed only to word an error): the seeded relation table's grouping. */
    static String fallbackGroup(String code) {
        if (code.startsWith("SON") || code.startsWith("DAUGHTER") || code.endsWith("_CHILD")) return "CHILD";
        if (code.endsWith("_IN_LAW")) return "PARENT_IN_LAW";
        if (Set.of("SPOUSE", "HUSBAND", "WIFE", "DOMESTIC_PARTNER").contains(code)) return "SPOUSE";
        if (code.contains("FATHER") || code.contains("MOTHER")) return "PARENT";
        return "OTHER";
    }

    /** A person already placed in the family: relation code and whole-year age. */
    public record Person(String code, String group, int age) {}

    /**
     * The age-difference rule of {@code code}: every employee (or spouse) in the family must differ in age by at least the configured years.
     * Mirrors policy-service's FamilyConstructService.checkRelationRules.
     */
    public static boolean gapOk(SheetSpec spec, String code, int age, List<Person> others) {
        SheetSpec.RelRule r = spec.rules().get(code);
        if (r == null || r.ageDifference() == null) return true;
        String wanted = "SELF".equals(r.differenceFrom()) ? "SELF" : "SPOUSE";
        for (Person o : others) {
            if (wanted.equals(o.group()) && Math.abs(o.age() - age) < r.ageDifference()) return false;
        }
        return true;
    }

    /** Smallest and largest age this relation may have on this policy; {@code lo > hi} means no age works. */
    public static int[] ageWindow(SheetSpec spec, String code, String group, int defaultLo, int defaultHi) {
        int lo = defaultLo, hi = defaultHi;
        Integer min = spec.minAgeFor(code), max = spec.maxAgeFor(code);
        if (min != null) lo = Math.max(lo, min);
        else if (ADULT_GROUPS.contains(group)) lo = Math.max(lo, ADULT_AGE);
        if (max != null) hi = Math.min(hi, max);
        if (spec.ageMin() != null) lo = Math.max(lo, spec.ageMin());
        if (spec.ageMax() != null) hi = Math.min(hi, spec.ageMax());
        return new int[]{lo, hi};
    }
}
