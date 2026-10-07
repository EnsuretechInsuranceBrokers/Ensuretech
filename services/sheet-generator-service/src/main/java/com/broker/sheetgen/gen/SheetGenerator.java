package com.broker.sheetgen.gen;

import com.broker.sheetgen.api.BadRequestException;
import com.broker.sheetgen.api.UnprocessableException;
import com.broker.sheetgen.spec.SheetSpec;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Generates ADD sheets that satisfy a policy's sheet-spec: relations the policy covers, ages inside each relation's window and gap,
 * children within the cap (twins only where the policy allows them), parents or in-laws (never both), marriage dates that are
 * in the past and 18+ years after both birth dates, sum insured / grade / designation taken from the rate card, and an effective
 * date inside the policy period. What the policy does not say is not guessed: a spec with gaps is refused.
 */
public final class SheetGenerator {

    /** Employee-code block reserved for one run: {@code prefix + zero-padded sequence}. */
    public record Ids(String prefix, long first, long brokerId) {
        public String code(int i) { return prefix + String.format("%06d", first + i); }
        long seq(int i) { return first + i; }
    }

    private static final String[] MALE = {"Aarav", "Vivaan", "Aditya", "Vihaan", "Arjun", "Sai", "Reyansh", "Krishna", "Ishaan", "Rohan", "Karan", "Nikhil",
            "Rahul", "Amit", "Sandeep", "Vikram", "Ankit", "Deepak", "Manoj", "Suresh", "Rajesh", "Anil", "Sunil", "Ashok", "Prakash", "Nitesh", "Rakesh",
            "Vijay", "Manish"};
    private static final String[] FEMALE = {"Priya", "Ananya", "Diya", "Saanvi", "Aadhya", "Ishita", "Kavya", "Riya", "Neha", "Pooja", "Sneha", "Anjali",
            "Divya", "Meera", "Shreya", "Nisha", "Swati", "Kavita", "Rekha", "Sunita", "Aisha", "Zoya", "Radhika", "Bhavna", "Preeti", "Simran", "Tanvi",
            "Vidya", "Komal"};
    private static final String[] LAST = {"Sharma", "Verma", "Gupta", "Iyer", "Nair", "Menon", "Reddy", "Rao", "Naidu", "Pillai", "Khan", "Ahmed", "Sheikh",
            "Patel", "Shah", "Mehta", "Desai", "Joshi", "Kulkarni", "Deshmukh", "Chavan", "Pawar", "Jadhav", "More", "Bhosale", "Singh", "Kaur", "Chauhan",
            "Rathore", "Yadav", "Mishra", "Tiwari", "Pandey", "Dubey", "Trivedi", "Bose", "Banerjee", "Chatterjee", "Mukherjee", "Sen", "Das", "Roy", "Ghosh",
            "Kapoor", "Malhotra", "Chopra", "Bhatia", "Arora", "Sethi", "Anand"};
    private static final String[][] STATE_CITY = {{"Maharashtra", "Mumbai"}, {"Maharashtra", "Pune"}, {"Karnataka", "Bengaluru"}, {"Tamil Nadu", "Chennai"},
            {"Delhi", "New Delhi"}, {"Gujarat", "Ahmedabad"}, {"Telangana", "Hyderabad"}, {"West Bengal", "Kolkata"}, {"Haryana", "Gurugram"}, {"Kerala", "Kochi"}};
    private static final List<String> SONS = List.of("SON", "SON_II", "SON_III", "SON_IV"), DAUGHTERS = List.of("DAUGHTER", "DAUGHTER_II", "DAUGHTER_III");
    static final String DEFAULT_SUM_INSURED = "500000";
    private static final int DOB_MARGIN_DAYS = 30;

    private record Member(String code, String group, boolean male, String first, String last, LocalDate dob, boolean self) {}

    private final Map<String, Integer> warned = new LinkedHashMap<>();
    private Random rnd;
    private SheetSpec spec;
    private Options opt;
    private LocalDate today;

    private SheetGenerator() {}

    /** Columns the sheet carries for this policy. */
    public static List<Column> layout(SheetSpec spec, Options o) {
        Set<String> want = new HashSet<>(List.of(Column.EMP_CODE, Column.EMP_FIRST, Column.EMP_LAST, Column.EMP_GENDER, Column.EMP_DOB, Column.EMP_DOJ,
                Column.EMP_EMAIL, Column.EMP_MOBILE, Column.RELATION, Column.MEM_FIRST, Column.MEM_LAST, Column.MEM_GENDER, Column.MEM_DOB, Column.DOM,
                Column.EFFECTIVE, Column.SUM_INSURED));
        if (spec.needsGrade()) want.add(Column.GRADE);
        if (spec.needsDesignation()) want.add(Column.DESIGNATION);
        if (spec.salaryBased()) want.add(Column.SALARY);
        if (spec.needsFlexPlan()) want.add(Column.FLEX);
        if (o.fillOptional()) { want.add(Column.STATE); want.add(Column.CITY); }
        if (o.includePremiums() || spec.noRule()) { want.add(Column.EMP_PREMIUM); want.add(Column.ER_PREMIUM); }
        return Column.ALL.stream().filter(c -> want.contains(c.id())).toList();
    }

    /** Refuses to generate from a spec that is incomplete or contradicts itself. */
    public static void preflight(SheetSpec spec) {
        List<String> problems = new ArrayList<>(spec.gaps());
        if (spec.needsGrade() && spec.grades().isEmpty()) problems.add("The premium type needs a grade but the rate card lists none");
        if (spec.needsDesignation() && spec.designations().isEmpty()) problems.add("The premium type needs a designation but the rate card lists none");
        int[] w = Rules.ageWindow(spec, "SELF", "SELF", Rules.ADULT_AGE, 60);
        if (w[0] > w[1]) problems.add("The policy's age rules leave no valid employee age (" + w[0] + ".." + w[1] + ")");
        if (!problems.isEmpty()) throw new UnprocessableException("The policy's sheet-spec cannot produce a valid sheet yet", problems);
    }

    public static Sheet add(SheetSpec spec, Options o, Ids ids, LocalDate today) {
        if (o.families() < 1) throw new BadRequestException("families must be at least 1");
        for (int p : new int[]{o.spousePct(), o.childPct(), o.twinPct(), o.parentPct(), o.midTermPct()}) {
            if (p < 0 || p > Options.MAX_PCT) throw new BadRequestException("percentages must be between 0 and 100");
        }
        if (o.effectiveDate() != null && (o.effectiveDate().isBefore(spec.start()) || o.effectiveDate().isAfter(spec.end()))) {
            throw new BadRequestException("effectiveDate must be inside the policy period " + spec.start() + " .. " + spec.end());
        }
        preflight(spec);
        SheetGenerator g = new SheetGenerator();
        g.spec = spec;
        g.opt = o;
        g.today = today;
        g.rnd = new Random(o.seed() != null ? o.seed() : System.nanoTime());
        List<Column> cols = layout(spec, o);
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < o.families(); i++) rows.addAll(g.family(ids, i, cols));
        if (!spec.siOneOf() && Rules.blank(o.sumInsured())) g.warn("The policy accepts any sum insured; used " + DEFAULT_SUM_INSURED + " (set sumInsured to change)");
        if (spec.needsFlexPlan() && Rules.blank(o.flexPlan())) g.warn("Flex Plan left blank: the sheet-spec does not list the plans (set flexPlan)");
        List<String> warnings = new ArrayList<>();
        g.warned.forEach((m, n) -> warnings.add(n > 1 ? m + " (" + n + " families)" : m));
        return new Sheet("ADD", cols, rows, warnings);
    }

    // ── one family ──────────────────────────────────────────────────────────────────────────────────────────
    private List<Map<String, String>> family(Ids ids, int index, List<Column> cols) {
        String code = ids.code(index);
        LocalDate eff = effectiveDate();
        List<Member> members = new ArrayList<>();
        List<Rules.Person> people = new ArrayList<>();
        LocalDate cutoff = min(eff, today);            // anything that "already happened" must be before this

        boolean male = rnd.nextBoolean();
        int[] w = Rules.ageWindow(spec, "SELF", "SELF", Rules.ADULT_AGE, 60);
        int span = (int) Math.max(0, ChronoUnit.YEARS.between(eff, today)) + 1;     // the employee must also be an adult on the effective date
        int hi = Math.max(w[0], w[1] - span);
        int selfAge = w[0] + rnd.nextInt(hi - w[0] + 1);
        LocalDate selfDob = min(dobForAge(selfAge), cutoff.minusYears(Rules.ADULT_AGE).minusDays(DOB_MARGIN_DAYS));
        String last = pick(LAST);
        Member self = new Member("SELF", "SELF", male, pick(male ? MALE : FEMALE), last, selfDob, true);
        members.add(self);
        people.add(new Rules.Person("SELF", "SELF", Rules.ageOn(selfDob, today)));
        int selfAgeNow = people.get(0).age();

        int room = spec.maxMembers() - 1;
        Member spouse = null;
        LocalDate dom = null;
        if (room > 0 && spec.hasFamily() && chance(opt.spousePct())) {
            boolean spouseMale = !male;
            String sc = spouseCode(spouseMale);
            if (sc != null) {
                int[] sw = Rules.ageWindow(spec, sc, "SPOUSE", Rules.ADULT_AGE, 70);
                LocalDate domHi = min(eff, today.minusDays(1));
                for (int tries = 0; tries < 40 && spouse == null; tries++) {
                    int lo = Math.max(sw[0], selfAgeNow - 8), hh = Math.min(sw[1], selfAgeNow + 8);
                    if (lo > hh) { lo = sw[0]; hh = sw[1]; }
                    if (lo > hh) break;
                    int a = lo + rnd.nextInt(hh - lo + 1);
                    LocalDate dob = dobForAge(a);
                    LocalDate domLo = max(selfDob.plusYears(Rules.ADULT_AGE), dob.plusYears(Rules.ADULT_AGE)).plusDays(1);
                    if (Rules.ageOn(dob, today) != a || domLo.isAfter(domHi) || !Rules.gapOk(spec, sc, a, people)) continue;
                    spouse = new Member(sc, "SPOUSE", spouseMale, pick(spouseMale ? MALE : FEMALE), rnd.nextInt(4) == 0 ? pick(LAST) : last, dob, false);
                    dom = domLo.plusDays(rnd.nextInt((int) ChronoUnit.DAYS.between(domLo, domHi) + 1));
                }
                if (spouse == null) warn("Spouse skipped: no age satisfies the policy's rules for " + sc);
            } else {
                warn("Spouse skipped: the policy does not cover a spouse relation");
            }
        }
        if (spouse != null) {
            members.add(spouse);
            people.add(new Rules.Person(spouse.code(), "SPOUSE", Rules.ageOn(spouse.dob(), today)));
            room--;
        }

        List<Member> kids = children(people, room, last, spouse);
        members.addAll(kids);
        room -= kids.size();

        if (room > 0 && spec.hasFamily() && chance(opt.parentPct())) members.addAll(parents(people, room, spouse, last));

        return rows(members, spouse, dom, code, ids, index, eff, cols);
    }

    private List<Member> children(List<Rules.Person> people, int room, String last, Member spouse) {
        List<Member> out = new ArrayList<>();
        int sons = allowedPrefix(SONS), daughters = allowedPrefix(DAUGHTERS);
        int cap = Math.min(Math.min(spec.maxChildren() == null ? 4 : spec.maxChildren(), sons + daughters), room);
        if (cap < 1 || !spec.hasFamily() || !chance(opt.childPct())) return out;
        int want = Math.min(cap, rnd.nextInt(100) < 50 ? 1 : rnd.nextInt(100) < 70 ? 2 : 3);
        int usedSons = 0, usedDaughters = 0;
        int parentAge = people.stream().mapToInt(Rules.Person::age).min().orElse(30);       // the younger of employee and spouse
        while (out.size() < want) {
            boolean twin = want - out.size() >= 2 && chance(opt.twinPct());
            int need = twin ? 2 : 1;
            boolean[] genders = new boolean[need];            // true = son
            int s = usedSons, d = usedDaughters;
            for (int k = 0; k < need; k++) {
                boolean canSon = s < sons, canDaughter = d < daughters;
                if (!canSon && !canDaughter) { need = k; break; }
                genders[k] = canSon && (!canDaughter || rnd.nextBoolean());
                if (genders[k]) s++; else d++;
            }
            if (need == 0) break;
            Integer age = childAge(genders, need, people, parentAge);
            if (age == null) { warn("Children skipped: no age satisfies the policy's child rules"); break; }
            LocalDate dob = dobForAge(age);
            for (int k = 0; k < need; k++) {
                boolean son = genders[k];
                out.add(new Member(null, "CHILD", son, pick(son ? MALE : FEMALE), last, dob, false));
            }
            usedSons = s;
            usedDaughters = d;
        }
        return assignChildCodes(out);
    }

    /** An age every candidate child code of the chosen genders accepts (window and gap rules), or null. */
    private Integer childAge(boolean[] genders, int n, List<Rules.Person> people, int parentAge) {
        List<String> codes = new ArrayList<>();
        for (int k = 0; k < n; k++) codes.addAll((genders[k] ? SONS : DAUGHTERS).stream().filter(spec::covers).toList());
        int lo = 0, hi = 25;
        for (String c : codes) {
            int[] w = Rules.ageWindow(spec, c, "CHILD", 0, 25);
            lo = Math.max(lo, w[0]);
            hi = Math.min(hi, w[1]);
        }
        hi = Math.min(hi, parentAge - Rules.MIN_PARENT_CHILD_YEARS);
        List<Integer> ok = new ArrayList<>();
        for (int a = lo; a <= hi; a++) {
            final int age = a;
            if (codes.stream().allMatch(c -> Rules.gapOk(spec, c, age, people))) ok.add(a);
        }
        return ok.isEmpty() ? null : ok.get(rnd.nextInt(ok.size()));
    }

    /** Eldest first: SON, SON_II, ...; twins share a birth date and take consecutive codes. Drops a child the policy's twin limit cannot take. */
    private List<Member> assignChildCodes(List<Member> kids) {
        List<Member> sorted = new ArrayList<>(kids);
        sorted.sort(Comparator.comparing(Member::dob));
        int s = 0, d = 0;
        List<Member> out = new ArrayList<>();
        for (Member m : sorted) {
            String c = m.male() ? SONS.get(s++) : DAUGHTERS.get(d++);
            out.add(new Member(c, "CHILD", m.male(), m.first(), m.last(), m.dob(), false));
        }
        Map<LocalDate, List<Member>> byDob = new LinkedHashMap<>();
        for (Member m : out) byDob.computeIfAbsent(m.dob(), k -> new ArrayList<>()).add(m);
        for (List<Member> g : byDob.values()) {
            int limit = g.stream().mapToInt(m -> spec.maxTwinsFor(m.code())).min().orElse(1);
            for (int i = g.size() - 1; i >= limit; i--) { out.remove(g.get(i)); warn("Twin dropped: the policy's max twins is " + limit); }
        }
        return out;
    }

    private List<Member> parents(List<Rules.Person> people, int room, Member spouse, String last) {
        boolean parentsOk = spec.includeParents() && (spec.covers("FATHER") || spec.covers("MOTHER"));
        boolean inLawsOk = spec.includeParentsInLaw() && spouse != null && (spec.covers("FATHER_IN_LAW") || spec.covers("MOTHER_IN_LAW"));
        if (!parentsOk && !inLawsOk) return List.of();
        boolean inLaws = inLawsOk && (!parentsOk || rnd.nextBoolean());
        List<String> codes = (inLaws ? List.of("FATHER_IN_LAW", "MOTHER_IN_LAW") : List.of("FATHER", "MOTHER")).stream().filter(spec::covers).toList();
        int cap = Math.min(Math.min(codes.size(), spec.maxParents() == null ? 2 : spec.maxParents()), room);
        if (cap < 1) return List.of();
        int n = rnd.nextBoolean() ? Math.min(2, cap) : 1;
        List<String> chosen = new ArrayList<>(codes);
        Collections.shuffle(chosen, rnd);
        chosen = chosen.subList(0, n);
        Member anchor = inLaws ? spouse : null;
        int anchorAge = anchor != null ? Rules.ageOn(anchor.dob(), today) : people.get(0).age();
        List<Member> out = new ArrayList<>();
        String lastName = inLaws ? pick(LAST) : last;
        List<Rules.Person> placed = new ArrayList<>(people);
        for (String c : chosen) {
            boolean male = c.startsWith("FATHER");
            int[] w = Rules.ageWindow(spec, c, inLaws ? "PARENT_IN_LAW" : "PARENT", Rules.ADULT_AGE, 80);
            int lo = Math.max(w[0], anchorAge + Rules.MIN_PARENT_CHILD_YEARS), hi = Math.min(w[1], anchorAge + 35);
            List<Integer> ok = new ArrayList<>();
            for (int a = lo; a <= hi; a++) if (Rules.gapOk(spec, c, a, placed)) ok.add(a);
            if (ok.isEmpty()) { warn("Parent skipped: no age satisfies the policy's rules for " + c); continue; }
            int a = ok.get(rnd.nextInt(ok.size()));
            out.add(new Member(c, inLaws ? "PARENT_IN_LAW" : "PARENT", male, pick(male ? MALE : FEMALE), lastName, dobForAge(a), false));
            placed.add(new Rules.Person(c, inLaws ? "PARENT_IN_LAW" : "PARENT", a));
        }
        return out;
    }

    // ── rows ────────────────────────────────────────────────────────────────────────────────────────────────
    private List<Map<String, String>> rows(List<Member> members, Member spouse, LocalDate dom, String code, Ids ids, int index, LocalDate eff, List<Column> cols) {
        Set<String> has = new HashSet<>();
        cols.forEach(c -> has.add(c.id()));
        Member self = members.get(0);
        String si = sumInsured();
        String grade = spec.needsGrade() ? pick(spec.grades().toArray(new String[0])) : null;
        String desig = spec.needsDesignation() ? pick(spec.designations().toArray(new String[0])) : null;
        String[] stateCity = STATE_CITY[rnd.nextInt(STATE_CITY.length)];
        LocalDate dojLo = self.dob().plusYears(Rules.ADULT_AGE).plusDays(1), dojHi = min(eff, today);
        LocalDate doj = dojHi.isBefore(dojLo) ? dojLo : dojLo.plusDays(rnd.nextInt((int) Math.min(ChronoUnit.DAYS.between(dojLo, dojHi) + 1, 365 * 15)));
        List<Map<String, String>> out = new ArrayList<>();
        for (Member m : members) {
            Map<String, String> r = new LinkedHashMap<>();
            r.put(Column.EMP_CODE, code);
            r.put(Column.RELATION, m.code());
            r.put(Column.EFFECTIVE, Rules.fmt(eff));
            r.put(Column.SUM_INSURED, si);
            if (m.self()) {
                r.put(Column.EMP_FIRST, m.first());
                r.put(Column.EMP_LAST, m.last());
                r.put(Column.EMP_GENDER, m.male() ? "Male" : "Female");
                r.put(Column.EMP_DOB, Rules.fmt(m.dob()));
                r.put(Column.EMP_DOJ, Rules.fmt(doj));
                r.put(Column.EMP_EMAIL, email(m, ids, index));
                r.put(Column.EMP_MOBILE, mobile(ids, index));
                if (grade != null) r.put(Column.GRADE, grade);
                if (desig != null) r.put(Column.DESIGNATION, desig);
                if (has.contains(Column.SALARY)) r.put(Column.SALARY, String.valueOf(300_000 + 10_000 * rnd.nextInt(371)));
                if (has.contains(Column.STATE)) { r.put(Column.STATE, stateCity[0]); r.put(Column.CITY, stateCity[1]); }
                if (spouse != null) r.put(Column.DOM, Rules.fmt(dom));
            } else {
                r.put(Column.MEM_FIRST, m.first());
                r.put(Column.MEM_LAST, m.last());
                r.put(Column.MEM_GENDER, m.male() ? "Male" : "Female");
                r.put(Column.MEM_DOB, Rules.fmt(m.dob()));
                if (m == spouse) r.put(Column.DOM, Rules.fmt(dom));
            }
            if (has.contains(Column.FLEX) && !Rules.blank(opt.flexPlan())) r.put(Column.FLEX, opt.flexPlan());
            if (has.contains(Column.EMP_PREMIUM)) {
                r.put(Column.EMP_PREMIUM, Rules.blank(opt.employeePremium()) ? "12000" : opt.employeePremium());
                r.put(Column.ER_PREMIUM, Rules.blank(opt.employerPremium()) ? "8000" : opt.employerPremium());
            }
            out.add(r);
        }
        return out;
    }

    private String sumInsured() {
        if (spec.siOneOf()) return spec.siValues().get(rnd.nextInt(spec.siValues().size()));
        return Rules.blank(opt.sumInsured()) ? DEFAULT_SUM_INSURED : opt.sumInsured();
    }

    private static String email(Member m, Ids ids, int index) {
        String base = (m.first() + "." + m.last()).toLowerCase(Locale.ROOT).replaceAll("[^a-z.]", "");
        return base + "." + ids.code(index).toLowerCase(Locale.ROOT) + "@b" + ids.brokerId() + ".ensuretech.test";     // .test never routes anywhere
    }

    private static String mobile(Ids ids, int index) {
        return "9" + String.format("%09d", ((ids.brokerId() % 90) * 10_000_000L + ids.seq(index)) % 1_000_000_000L);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────
    private LocalDate effectiveDate() {
        if (opt.effectiveDate() != null) return opt.effectiveDate();
        if (!chance(opt.midTermPct())) return spec.start();
        LocalDate hi = min(spec.end(), max(spec.start(), today));
        return spec.start().plusDays(rnd.nextInt((int) ChronoUnit.DAYS.between(spec.start(), hi) + 1));
    }

    /** A birth date whose whole-year age today is exactly {@code age}. */
    private LocalDate dobForAge(int age) {
        LocalDate lo = today.minusYears(age + 1L).plusDays(1), hi = min(today.minusYears(age), today.minusDays(1));     // a newborn is born before today
        return lo.plusDays(rnd.nextInt((int) ChronoUnit.DAYS.between(lo, hi) + 1));
    }

    private String spouseCode(boolean spouseMale) {
        String specific = spouseMale ? "HUSBAND" : "WIFE";
        if (spec.covers(specific)) return specific;
        return spec.covers("SPOUSE") ? "SPOUSE" : null;
    }

    private int allowedPrefix(List<String> seq) {
        int n = 0;
        while (n < seq.size() && spec.covers(seq.get(n))) n++;
        return n;
    }

    private boolean chance(int pct) { return rnd.nextInt(100) < pct; }

    private String pick(String[] a) { return a[rnd.nextInt(a.length)]; }

    private void warn(String m) { warned.merge(m, 1, Integer::sum); }

    private static LocalDate min(LocalDate a, LocalDate b) { return a.isBefore(b) ? a : b; }

    private static LocalDate max(LocalDate a, LocalDate b) { return a.isAfter(b) ? a : b; }
}
