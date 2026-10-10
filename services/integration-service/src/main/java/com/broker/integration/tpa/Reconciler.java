package com.broker.integration.tpa;

import com.broker.integration.tpa.TpaAdapter.RosterMember;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Matches what a TPA says it covers (its "active lives") to our roster so each member gets the TPA's own member id.
 * The legacy rules (assignTpaMemberId): employee code compared trimmed and case-insensitively (Care: leading zeros ignored), the TPA's relation
 * label translated through a per-TPA mapping, and, because a family can hold several children, a child must also match by name.
 * Unlike the legacy code, a miss is reported (never silently skipped), one TPA record can only claim one member, one member can only be
 * claimed once, and the name check is a whole-word match rather than a position test that fails when the name starts the string.
 */
public final class Reconciler {
    private Reconciler() {}

    public record ActiveLife(String employeeCode, String relation, String memberId, String memberName, List<ActiveLife> dependents) {
        public ActiveLife(String employeeCode, String relation, String memberId, String memberName) { this(employeeCode, relation, memberId, memberName, List.of()); }
    }

    public record Assignment(long rosterId, String tpaMemberId, String tpaMemberName) {}

    public record Miss(String employeeCode, String relation, String memberId, String reason) {}

    public record Result(List<Assignment> assignments, List<Miss> misses, int rosterUnmatched) {}

    /** Label (lower-cased, trimmed) the TPA uses for a relation -> our relation code. */
    public static final Map<String, String> DEFAULT_RELATIONS = Map.ofEntries(
            Map.entry("self", "SELF"), Map.entry("employee", "SELF"), Map.entry("emp", "SELF"),
            Map.entry("spouse", "SPOUSE"), Map.entry("wife", "SPOUSE"), Map.entry("husband", "SPOUSE"),
            Map.entry("son", "SON"), Map.entry("daughter", "DAUGHTER"), Map.entry("child", "CHILD"),
            Map.entry("father", "FATHER"), Map.entry("mother", "MOTHER"),
            Map.entry("father-in-law", "FATHER_IN_LAW"), Map.entry("father in law", "FATHER_IN_LAW"),
            Map.entry("mother-in-law", "MOTHER_IN_LAW"), Map.entry("mother in law", "MOTHER_IN_LAW"),
            Map.entry("brother", "BROTHER"), Map.entry("sister", "SISTER"), Map.entry("partner", "DOMESTIC_PARTNER"));

    public static String key(String code, boolean stripLeadingZeros) {
        String k = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        if (stripLeadingZeros) k = k.replaceFirst("^0+(?=.)", "");
        return k;
    }

    /** SPOUSE covers HUSBAND/WIFE, SON covers SON_II..., so a TPA's "wife" finds a roster row stored as WIFE. */
    public static String family(String relationCode) {
        if (relationCode == null) return "";
        return switch (relationCode) {
            case "HUSBAND", "WIFE" -> "SPOUSE";
            default -> relationCode.replaceFirst("_(II|III|IV)$", "");
        };
    }

    private static boolean isChild(String fam) { return fam.equals("SON") || fam.equals("DAUGHTER") || fam.equals("CHILD"); }

    static String words(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").trim().replaceAll("\\s+", " "); }

    /** Full-name equality, or the roster first name appearing as a whole word in the TPA's name. */
    static boolean nameMatches(RosterMember m, String tpaName) { return nameMatches(m.firstName(), m.lastName(), tpaName); }

    static boolean nameMatches(String firstName, String lastName, String tpaName) {
        String t = words(tpaName), first = words(firstName);
        if (t.isEmpty() || first.isEmpty()) return false;
        if (t.equals(words((firstName + " " + (lastName == null ? "" : lastName)).trim()))) return true;
        String firstWord = first.split(" ")[0];
        return (" " + t + " ").contains(" " + firstWord + " ");
    }

    public static Result reconcile(List<ActiveLife> lives, List<RosterMember> roster, Map<String, String> relationLabels, boolean stripLeadingZeros) {
        Map<String, String> labels = new HashMap<>(DEFAULT_RELATIONS);
        if (relationLabels != null) relationLabels.forEach((k, v) -> labels.put(k.trim().toLowerCase(Locale.ROOT), v));
        Map<String, List<RosterMember>> byCode = new HashMap<>();
        for (RosterMember m : roster) byCode.computeIfAbsent(key(m.employeeCode(), stripLeadingZeros), k -> new ArrayList<>()).add(m);

        List<Assignment> out = new ArrayList<>();
        List<Miss> misses = new ArrayList<>();
        Set<Long> taken = new HashSet<>();
        Map<String, ActiveLife> flat = new LinkedHashMap<>();
        List<Object[]> work = new ArrayList<>();                     // {employeeCode, life}
        for (ActiveLife l : lives) {
            work.add(new Object[] {l.employeeCode(), l});
            for (ActiveLife d : l.dependents() == null ? List.<ActiveLife>of() : l.dependents()) work.add(new Object[] {l.employeeCode(), d});
        }
        for (Object[] w : work) {
            String code = (String) w[0];
            ActiveLife l = (ActiveLife) w[1];
            String label = l.relation() == null ? "" : l.relation().trim().toLowerCase(Locale.ROOT);
            String ours = labels.get(label);
            if (ours == null) { misses.add(new Miss(code, l.relation(), l.memberId(), "relation '" + l.relation() + "' is not mapped for this TPA")); continue; }
            if (l.memberId() == null || l.memberId().isBlank()) { misses.add(new Miss(code, l.relation(), l.memberId(), "the TPA record has no member id")); continue; }
            String fam = family(ours);
            List<RosterMember> sameFamily = byCode.getOrDefault(key(code, stripLeadingZeros), List.of()).stream()
                    .filter(m -> family(m.relationCode()).equals(fam) || (fam.equals("CHILD") && isChild(family(m.relationCode())))).toList();
            if (sameFamily.isEmpty()) { misses.add(new Miss(code, l.relation(), l.memberId(), "no such member on our roster")); continue; }
            List<RosterMember> free = sameFamily.stream().filter(m -> !taken.contains(m.id())).toList();
            if (free.isEmpty()) { misses.add(new Miss(code, l.relation(), l.memberId(), "every matching member already has a TPA record")); continue; }
            RosterMember pick;
            if (isChild(fam) || free.size() > 1) {
                List<RosterMember> named = free.stream().filter(m -> nameMatches(m, l.memberName())).toList();
                if (named.size() != 1) {
                    misses.add(new Miss(code, l.relation(), l.memberId(), named.isEmpty() ? "no " + fam.toLowerCase(Locale.ROOT) + " on our roster matches the name '" + l.memberName() + "'"
                            : "the name '" + l.memberName() + "' matches more than one member"));
                    continue;
                }
                pick = named.getFirst();
            } else {
                pick = free.getFirst();
            }
            taken.add(pick.id());
            out.add(new Assignment(pick.id(), l.memberId(), l.memberName()));
        }
        return new Result(out, misses, (int) roster.stream().filter(m -> !taken.contains(m.id())).count());
    }
}
