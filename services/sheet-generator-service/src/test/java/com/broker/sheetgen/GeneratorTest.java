package com.broker.sheetgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.broker.sheetgen.api.BadRequestException;
import com.broker.sheetgen.api.UnprocessableException;
import com.broker.sheetgen.gen.*;
import com.broker.sheetgen.spec.SheetSpec;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class GeneratorTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 7);
    static final SheetGenerator.Ids IDS = new SheetGenerator.Ids("GEN", 1, 5);

    static Options opts(int families, long seed) {
        return new Options(families, 90, 80, 30, 50, 40, true, false, null, seed, null, null, null, null);
    }

    static void assertClean(SheetSpec spec, Sheet sheet) {
        List<Issue> issues = SheetValidator.validate(spec, sheet.operation(), sheet.byHeader(), TODAY);
        assertThat(issues).as("first issues: %s", issues.stream().limit(5).toList()).isEmpty();
    }

    @Test
    void flatPolicyOutputPassesTheValidatorAcrossManySeeds() {
        SheetSpec spec = Specs.flat();
        for (long seed = 1; seed <= 6; seed++) assertClean(spec, SheetGenerator.add(spec, opts(500, seed), IDS, TODAY));
    }

    @Test
    void gradePolicyPutsGradeAndDesignationOnTheSelfRowOnlyAndTakesSumInsuredFromTheRateCard() {
        SheetSpec spec = Specs.grade();
        Sheet s = SheetGenerator.add(spec, opts(300, 11), IDS, TODAY);
        assertClean(spec, s);
        for (var r : s.rows()) {
            boolean self = r.get(Column.RELATION).equals("SELF");
            assertThat(r.containsKey(Column.GRADE)).isEqualTo(self);
            assertThat(r.containsKey(Column.DESIGNATION)).isEqualTo(self);
            assertThat(r.get(Column.SUM_INSURED)).isIn("300000", "500000", "1000000");
        }
        assertThat(s.columns().stream().map(Column::id)).contains(Column.GRADE, Column.DESIGNATION);
    }

    @Test
    void strictPolicyRespectsWindowsGapsTwinsAndCaps() {
        SheetSpec spec = Specs.strict();
        for (long seed = 1; seed <= 6; seed++) {
            Sheet s = SheetGenerator.add(spec, opts(500, 100 + seed), IDS, TODAY);
            assertClean(spec, s);
            Map<String, List<Map<String, String>>> byFamily = s.rows().stream().collect(Collectors.groupingBy(r -> r.get(Column.EMP_CODE), LinkedHashMap::new, Collectors.toList()));
            assertThat(byFamily.values()).allSatisfy(f -> assertThat(f.size()).isLessThanOrEqualTo(6));
        }
    }

    @Test
    void twinsAreGeneratedOnlyWhenThePolicyAllowsThemAndNeverBeyondTheLimit() {
        long twinFamilies = 0;
        SheetSpec strict = Specs.strict(), flat = Specs.flat();
        Sheet s = SheetGenerator.add(strict, new Options(2000, 100, 100, 100, 0, 0, false, false, null, 5L, null, null, null, null), IDS, TODAY);
        for (var fam : s.rows().stream().collect(Collectors.groupingBy(r -> r.get(Column.EMP_CODE))).values()) {
            Map<String, Long> dobs = fam.stream().filter(r -> !r.get(Column.RELATION).equals("SELF") && !r.get(Column.RELATION).contains("SPOUSE") && r.get(Column.MEM_DOB) != null
                    && (r.get(Column.RELATION).startsWith("SON") || r.get(Column.RELATION).startsWith("DAUGHTER"))).collect(Collectors.groupingBy(r -> r.get(Column.MEM_DOB), Collectors.counting()));
            if (dobs.values().stream().anyMatch(c -> c == 2)) twinFamilies++;
        }
        assertThat(twinFamilies).as("strict policy allows twins").isGreaterThan(50);
        Sheet none = SheetGenerator.add(flat, new Options(2000, 100, 100, 100, 0, 0, false, false, null, 5L, null, null, null, null), IDS, TODAY);
        for (var fam : none.rows().stream().collect(Collectors.groupingBy(r -> r.get(Column.EMP_CODE))).values()) {
            var kids = fam.stream().filter(r -> r.get(Column.RELATION).startsWith("SON") || r.get(Column.RELATION).startsWith("DAUGHTER")).map(r -> r.get(Column.MEM_DOB)).toList();
            assertThat(new HashSet<>(kids)).as("no twins without a maxTwins rule").hasSameSizeAs(kids);
        }
    }

    @Test
    void parentsAndInLawsAreNeverMixed() {
        SheetSpec spec = Specs.flat();
        Sheet s = SheetGenerator.add(spec, new Options(2000, 100, 50, 0, 100, 0, false, false, null, 9L, null, null, null, null), IDS, TODAY);
        assertClean(spec, s);
        boolean sawParent = false, sawInLaw = false;
        for (var fam : s.rows().stream().collect(Collectors.groupingBy(r -> r.get(Column.EMP_CODE))).values()) {
            boolean p = fam.stream().anyMatch(r -> r.get(Column.RELATION).matches("FATHER|MOTHER")), i = fam.stream().anyMatch(r -> r.get(Column.RELATION).endsWith("IN_LAW"));
            assertThat(p && i).isFalse();
            sawParent |= p;
            sawInLaw |= i;
        }
        assertThat(sawParent && sawInLaw).isTrue();
    }

    @Test
    void marriageDatesAreInThePastAndEighteenYearsAfterBothBirthDates() {
        SheetSpec spec = Specs.flat();
        Sheet s = SheetGenerator.add(spec, new Options(1500, 100, 0, 0, 0, 0, false, false, null, 3L, null, null, null, null), IDS, TODAY);
        assertClean(spec, s);
        assertThat(s.rows().stream().filter(r -> r.containsKey(Column.DOM)).count()).isGreaterThan(1000);
    }

    @Test
    void selfOnlyWhenThePolicyHasNoFamilyConstruct() {
        SheetSpec spec = Specs.parse(Specs.spec("2026-04-01", "2027-03-31", "\"premiumType\":\"FLAT\",\"raterClass\":\"FlatRater\",\"dimensions\":[],\"sumInsured\":{\"mode\":\"FREE\",\"values\":[]}",
                "null", "[]", Specs.NO_NEEDS, "[]"));
        Sheet s = SheetGenerator.add(spec, opts(50, 1), IDS, TODAY);
        assertThat(s.rows()).hasSize(50).allSatisfy(r -> assertThat(r.get(Column.RELATION)).isEqualTo("SELF"));
        assertClean(spec, s);
    }

    @Test
    void noRulePolicyCarriesBothPremiumColumnsOnEveryRow() {
        SheetSpec spec = Specs.parse(Specs.spec("2026-04-01", "2027-03-31", "\"premiumType\":\"NO_RULE\",\"raterClass\":\"FlatRater\",\"dimensions\":[],\"sumInsured\":{\"mode\":\"FREE\",\"values\":[]}",
                Specs.family(Specs.ALL_RELATIONS, 6, 2, 2, true, false, null, null), "[]", Specs.NO_NEEDS, "[]"));
        Sheet s = SheetGenerator.add(spec, opts(100, 2), IDS, TODAY);
        assertThat(s.rows()).allSatisfy(r -> assertThat(r).containsKeys(Column.EMP_PREMIUM, Column.ER_PREMIUM));
        assertClean(spec, s);
    }

    @Test
    void salaryPoliciesGetASalaryOnEachEmployeeRow() {
        SheetSpec spec = Specs.parse(Specs.spec("2026-04-01", "2027-03-31",
                "\"premiumType\":\"FLAT\",\"suminsuredSubtype\":\"SALARY_MULTIPLE\",\"raterClass\":\"FlatRater\",\"dimensions\":[],\"sumInsured\":{\"mode\":\"FREE\",\"values\":[]}",
                Specs.family(Specs.ALL_RELATIONS, 6, 2, 2, false, false, null, null), "[]", Specs.NO_NEEDS, "[]"));
        Sheet s = SheetGenerator.add(spec, opts(100, 4), IDS, TODAY);
        assertThat(s.rows().stream().filter(r -> r.get(Column.RELATION).equals("SELF"))).allSatisfy(r -> assertThat(r).containsKey(Column.SALARY));
        assertClean(spec, s);
    }

    @Test
    void effectiveDateStaysInsideThePolicyPeriodAndMidTermIsExercised() {
        SheetSpec spec = Specs.flat();
        Sheet s = SheetGenerator.add(spec, new Options(800, 50, 30, 0, 0, 50, false, false, null, 8L, null, null, null, null), IDS, TODAY);
        Set<String> dates = s.rows().stream().map(r -> r.get(Column.EFFECTIVE)).collect(Collectors.toSet());
        assertThat(dates.size()).isGreaterThan(20);
        assertThat(dates).contains("01-04-2026");
        assertClean(spec, s);
        Sheet fixed = SheetGenerator.add(spec, new Options(20, 50, 30, 0, 0, 0, false, false, LocalDate.of(2026, 8, 15), 8L, null, null, null, null), IDS, TODAY);
        assertThat(fixed.rows()).allSatisfy(r -> assertThat(r.get(Column.EFFECTIVE)).isEqualTo("15-08-2026"));
        assertClean(spec, fixed);
    }

    @Test
    void policiesInThePastOrFutureStillProduceValidSheets() {
        for (String[] period : new String[][]{{"2024-04-01", "2025-03-31"}, {"2026-12-01", "2027-11-30"}}) {
            SheetSpec spec = Specs.parse(Specs.spec(period[0], period[1], "\"premiumType\":\"FLAT\",\"raterClass\":\"FlatRater\",\"dimensions\":[],\"sumInsured\":{\"mode\":\"FREE\",\"values\":[]}",
                    Specs.family(Specs.ALL_RELATIONS, 6, 2, 2, true, false, null, null), "[]", Specs.NO_NEEDS, "[]"));
            Sheet s = SheetGenerator.add(spec, new Options(600, 90, 60, 20, 30, 70, false, false, null, 12L, null, null, null, null), IDS, TODAY);
            List<Issue> issues = SheetValidator.validate(spec, "ADD", s.byHeader(), TODAY);
            assertThat(issues).as(period[0] + " first: %s", issues.stream().limit(5).toList()).isEmpty();
        }
    }

    @Test
    void sameSeedAndCodesGiveTheSameSheetAndNewCodesGiveNewEmails() {
        SheetSpec spec = Specs.flat();
        Sheet a = SheetGenerator.add(spec, opts(40, 77), IDS, TODAY), b = SheetGenerator.add(spec, opts(40, 77), IDS, TODAY);
        assertThat(a.rows()).isEqualTo(b.rows());
        Sheet c = SheetGenerator.add(spec, opts(40, 77), new SheetGenerator.Ids("GEN", 41, 5), TODAY);
        Set<String> ea = a.rows().stream().map(r -> r.get(Column.EMP_EMAIL)).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> ec = c.rows().stream().map(r -> r.get(Column.EMP_EMAIL)).filter(Objects::nonNull).collect(Collectors.toSet());
        assertThat(ea).hasSize(40);
        assertThat(ec).doesNotContainAnyElementsOf(ea);
        Sheet otherBroker = SheetGenerator.add(spec, opts(40, 77), new SheetGenerator.Ids("GEN", 1, 6), TODAY);
        assertThat(otherBroker.rows().stream().map(r -> r.get(Column.EMP_EMAIL)).filter(Objects::nonNull)).doesNotContainAnyElementsOf(ea);
    }

    @Test
    void aSpecWithGapsIsRefusedNotGuessed() {
        SheetSpec spec = Specs.parse(Specs.spec("2026-04-01", "2027-03-31", "\"premiumType\":\"GRADE\",\"raterClass\":\"GradeRater\",\"dimensions\":[\"GRADE\"],\"sumInsured\":{\"mode\":\"FREE\",\"values\":[]}",
                "null", "[]", "{\"needsGrade\":true,\"needsDesignation\":false,\"needsFlexPlan\":false}", "[\"NO_ACTIVE_RATE_CARD: grades unknown\"]"));
        assertThatThrownBy(() -> SheetGenerator.add(spec, opts(5, 1), IDS, TODAY)).isInstanceOf(UnprocessableException.class)
                .satisfies(e -> assertThat(((UnprocessableException) e).details()).anyMatch(d -> d.startsWith("NO_ACTIVE_RATE_CARD")));
    }

    @Test
    void anEffectiveDateOutsideThePolicyIsRejected() {
        assertThatThrownBy(() -> SheetGenerator.add(Specs.flat(), new Options(5, 50, 50, 0, 0, 0, false, false, LocalDate.of(2028, 1, 1), 1L, null, null, null, null), IDS, TODAY))
                .isInstanceOf(BadRequestException.class);
    }
}
