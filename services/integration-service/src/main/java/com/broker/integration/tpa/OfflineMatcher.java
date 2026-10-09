package com.broker.integration.tpa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Puts the lines of an offline TPA sheet onto our roster. It matches the way the nightly pull does (same employee-code rule, same per-TPA relation labels,
 * same name check for a family with several children) so the two routes can never disagree about who is who, and it enforces what the legacy offline
 * upload enforced (every id and every employee/relation once per sheet, the endorsement reference and the cover start date agree with ours), with two
 * differences: every line gets a verdict with a reason and its line number, and one bad line never holds up the good ones.
 */
public final class OfflineMatcher {
    private OfflineMatcher() {}

    /** The roster as this feature needs it (the pull path's {@link TpaAdapter.RosterMember} lacks the id, card and age of the id). */
    public record Member(long id, String employeeCode, String relationCode, String firstName, String lastName, String effectiveDate,
                         String tpaMemberId, String tpaMemberName, String ecardUrl, String endorsementRef, String endorsedAt) {}

    public record Input(int line, String employeeCode, String relation, String tpaMemberId, String tpaMemberName, String ecardUrl, String coverStart, String endorsementRef) {}

    public enum Outcome { ASSIGNED, REASSIGNED, UPDATED, UNCHANGED, REJECTED }

    public record Verdict(Input in, Outcome outcome, String reasonCode, String reason, Member member, String previousTpaMemberId) {
        public boolean accepted() { return outcome != Outcome.REJECTED; }
    }

    public record Options(boolean stripLeadingZeros, Map<String, String> relationLabels, boolean ignoreCoverDate) {}

    private static Verdict reject(Input in, String code, String why) { return new Verdict(in, Outcome.REJECTED, code, why, null, null); }

    /** "Son II", "son-ii", "SON_II" -> SON_II; the TPA's own labels (wife, elder son...) win when mapped. */
    static String relationOf(String label, Map<String, String> labels) {
        if (label == null || label.isBlank()) return null;
        String l = label.trim().toLowerCase(Locale.ROOT);
        String mapped = labels.get(l);
        if (mapped != null) return mapped;
        String code = label.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
        return Reconciler.DEFAULT_RELATIONS.values().contains(code) || code.matches("(SON|DAUGHTER)(_(II|III|IV))?") ? code : null;
    }

    private static String sameDay(String a, String b) { return a == null || b == null ? null : (a.equals(b) ? a : null); }

    public static List<Verdict> match(List<Input> inputs, List<Member> roster, Options opt) {
        Map<String, String> labels = new HashMap<>(Reconciler.DEFAULT_RELATIONS);
        // our own spellings of the numbered children, so "Son II" needs no mapping
        labels.putAll(Map.of("son ii", "SON_II", "son iii", "SON_III", "son iv", "SON_IV", "daughter ii", "DAUGHTER_II", "daughter iii", "DAUGHTER_III"));
        if (opt.relationLabels() != null) opt.relationLabels().forEach((k, v) -> labels.put(k.trim().toLowerCase(Locale.ROOT), v));
        Map<String, List<Member>> byCode = new HashMap<>();
        for (Member m : roster) byCode.computeIfAbsent(Reconciler.key(m.employeeCode(), opt.stripLeadingZeros()), k -> new ArrayList<>()).add(m);
        Map<String, Long> idOwner = new HashMap<>();                                         // a TPA id already held by one of our members
        for (Member m : roster) if (m.tpaMemberId() != null && !m.tpaMemberId().isBlank()) idOwner.put(m.tpaMemberId().trim().toLowerCase(Locale.ROOT), m.id());

        // what the sheet says about itself: an id used twice, a person listed twice
        Map<String, Integer> idCount = new HashMap<>(), personCount = new HashMap<>();
        for (Input in : inputs) {
            if (in.tpaMemberId() != null && !in.tpaMemberId().isBlank()) idCount.merge(in.tpaMemberId().trim().toLowerCase(Locale.ROOT), 1, Integer::sum);
            String rel = relationOf(in.relation(), labels);
            if (in.employeeCode() != null && !in.employeeCode().isBlank() && rel != null) personCount.merge(Reconciler.key(in.employeeCode(), opt.stripLeadingZeros()) + "|" + rel, 1, Integer::sum);
        }

        List<Verdict> out = new ArrayList<>();
        Set<Long> taken = new HashSet<>();
        for (Input in : inputs) {
            if (in.employeeCode() == null || in.employeeCode().isBlank()) { out.add(reject(in, "EMPLOYEE_CODE_MISSING", "The employee code is empty.")); continue; }
            boolean hasId = in.tpaMemberId() != null && !in.tpaMemberId().isBlank(), hasCard = in.ecardUrl() != null && !in.ecardUrl().isBlank();
            if (!hasId && !hasCard) { out.add(reject(in, "ID_MISSING", "There is no TPA member id and no e-card link on this line.")); continue; }
            String ours = relationOf(in.relation(), labels);
            if (in.relation() == null || in.relation().isBlank()) { out.add(reject(in, "RELATION_MISSING", "The relationship is empty.")); continue; }
            if (ours == null) { out.add(reject(in, "RELATION_UNKNOWN", "The relationship '" + in.relation() + "' is not one we know for this TPA. Map it once under the TPA's reconciliation settings.")); continue; }
            if (hasId && idCount.get(in.tpaMemberId().trim().toLowerCase(Locale.ROOT)) > 1) { out.add(reject(in, "ID_DUPLICATE_IN_SHEET", "The TPA member id " + in.tpaMemberId() + " appears more than once in this sheet.")); continue; }
            if (personCount.getOrDefault(Reconciler.key(in.employeeCode(), opt.stripLeadingZeros()) + "|" + ours, 0) > 1) {
                out.add(reject(in, "PERSON_DUPLICATE_IN_SHEET", "This employee and relationship appear more than once in this sheet.")); continue; }
            if (hasCard && !in.ecardUrl().matches("(?i)https?://\\S{4,480}")) { out.add(reject(in, "ECARD_URL_INVALID", "The e-card link must be a full http or https address.")); continue; }
            List<Member> family = byCode.get(Reconciler.key(in.employeeCode(), opt.stripLeadingZeros()));
            if (family == null) { out.add(reject(in, "EMPLOYEE_NOT_ON_POLICY", "No employee with code " + in.employeeCode() + " is covered on this policy.")); continue; }
            Member pick = null;
            List<Member> exact = family.stream().filter(m -> m.relationCode().equals(ours) && !taken.contains(m.id())).toList();
            if (exact.size() == 1) pick = exact.getFirst();
            else if (exact.isEmpty()) {
                String fam = Reconciler.family(ours);
                List<Member> sameFamily = family.stream().filter(m -> Reconciler.family(m.relationCode()).equals(fam) && !taken.contains(m.id())).toList();
                if (sameFamily.isEmpty()) { out.add(reject(in, "RELATION_NOT_ON_ROSTER", "This employee has no " + in.relation().trim() + " covered on this policy.")); continue; }
                if (sameFamily.size() == 1) pick = sameFamily.getFirst();
                else {
                    List<Member> named = sameFamily.stream().filter(m -> Reconciler.nameMatches(m.firstName(), m.lastName(), in.tpaMemberName())).toList();
                    if (named.size() != 1) { out.add(reject(in, named.isEmpty() ? "NAME_NO_MATCH" : "NAME_AMBIGUOUS",
                            (named.isEmpty() ? "None of this employee's " : "More than one of this employee's ") + in.relation().trim().toLowerCase(Locale.ROOT) + "s matches the name '" + in.tpaMemberName() + "'. Add the member name column.")); continue; }
                    pick = named.getFirst();
                }
            } else {
                List<Member> named = exact.stream().filter(m -> Reconciler.nameMatches(m.firstName(), m.lastName(), in.tpaMemberName())).toList();
                if (named.size() != 1) { out.add(reject(in, "NAME_AMBIGUOUS", "More than one member of this employee has that relationship; add the member name.")); continue; }
                pick = named.getFirst();
            }
            if (in.endorsementRef() != null && !in.endorsementRef().isBlank()) {
                String ref = in.endorsementRef().trim().toLowerCase(Locale.ROOT), mine = pick.endorsementRef() == null ? "" : pick.endorsementRef().toLowerCase(Locale.ROOT);
                if (!(ref.equals(mine) || (ref.length() >= 8 && mine.length() >= 8 && ref.startsWith(mine)))) {
                    out.add(reject(in, "ENDORSEMENT_REF_MISMATCH", "The endorsement reference " + in.endorsementRef().trim() + " is not the one that added this member (" + pick.endorsementRef() + ").")); continue; }
            }
            if (!opt.ignoreCoverDate() && in.coverStart() != null && !in.coverStart().isBlank()) {
                String iso = OfflineSheet.isoDate(in.coverStart());
                if (iso == null) { out.add(reject(in, "COVER_START_UNREADABLE", "The cover start date '" + in.coverStart() + "' is not a date I can read.")); continue; }
                if (!iso.equals(pick.effectiveDate())) { out.add(reject(in, "COVER_START_MISMATCH", "The TPA says cover starts " + iso + "; ours is " + pick.effectiveDate() + ". Settle which is right first, then upload again.")); continue; }
            }
            String id = hasId ? in.tpaMemberId().trim() : null;
            if (id != null) {
                Long owner = idOwner.get(id.toLowerCase(Locale.ROOT));
                if (owner != null && owner != pick.id()) { out.add(reject(in, "ID_IN_USE", "The TPA member id " + id + " already belongs to another member of this policy.")); continue; }
            }
            taken.add(pick.id());
            String had = pick.tpaMemberId();
            boolean idChanges = id != null && !id.equals(had), cardChanges = hasCard && !in.ecardUrl().trim().equals(pick.ecardUrl());
            boolean nameChanges = in.tpaMemberName() != null && !in.tpaMemberName().isBlank() && !in.tpaMemberName().trim().equals(pick.tpaMemberName());
            if (id == null && (had == null || had.isBlank())) { out.add(reject(in, "ID_MISSING", "This member has no TPA member id yet, so an e-card alone cannot be attached.")); taken.remove(pick.id()); continue; }
            Outcome o = idChanges ? (had == null || had.isBlank() ? Outcome.ASSIGNED : Outcome.REASSIGNED) : cardChanges ? Outcome.UPDATED : nameChanges ? Outcome.UPDATED : Outcome.UNCHANGED;
            out.add(new Verdict(in, o, null, null, pick, idChanges && had != null && !had.isBlank() ? had : null));
        }
        return out;
    }
}
