package com.broker.sheetgen;

import static org.assertj.core.api.Assertions.assertThat;

import com.broker.sheetgen.gen.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Each rule the generator builds toward must also be caught when a sheet breaks it. */
class ValidatorTest {
    static final LocalDate TODAY = GeneratorTest.TODAY;

    static Map<String, String> self(String code, String dob) {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("Employee Code", code);
        r.put("Employee First Name", "Asha");
        r.put("Employee Gender", "Female");
        r.put("Employee DOB", dob);
        r.put("Employee Date of Joining", "01-01-2018");
        r.put("Employee Email", code.toLowerCase() + "@x.test");
        r.put("Relationship with Employee", "SELF");
        r.put("Effective Date", "01-04-2026");
        r.put("Sum Insured", "500000");
        return r;
    }

    static Map<String, String> dep(String code, String rel, String dob) {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("Employee Code", code);
        r.put("Relationship with Employee", rel);
        r.put("Insured Member First Name", "Dep");
        r.put("Insured Member Gender", "Male");
        r.put("Insured Member DOB", dob);
        r.put("Effective Date", "01-04-2026");
        r.put("Sum Insured", "500000");
        return r;
    }

    static List<String> codes(List<Map<String, String>> rows) { return codes(Specs.flat(), rows); }

    static List<String> codes(com.broker.sheetgen.spec.SheetSpec spec, List<Map<String, String>> rows) {
        return SheetValidator.validate(spec, "ADD", rows, TODAY).stream().map(Issue::code).distinct().toList();
    }

    @Test
    void aCorrectFamilyIsClean() {
        var rows = List.of(self("E1", "01-01-1985"), dep("E1", "SON", "01-01-2015"));
        assertThat(codes(rows)).isEmpty();
    }

    @Test
    void ageRulesAreEnforced() {
        assertThat(codes(List.of(self("E1", "01-01-2012")))).contains("ADULT_UNDER_18");
        assertThat(codes(List.of(self("E1", "01-01-1985"), dep("E1", "SON", "01-01-1990")))).contains("RELATION_AGE_ABOVE_MAX");
    }

    @Test
    void strictPolicyWindowsAndGaps() {
        var spec = Specs.strict();
        assertThat(codes(spec, List.of(self("E1", "01-01-1985"), dep("E1", "WIFE", "01-01-2008")))).contains("RELATION_AGE_BELOW_MIN");
        assertThat(codes(spec, List.of(self("E1", "01-01-1990"), dep("E1", "SON", "01-01-2008")))).contains("AGE_GAP_TOO_SMALL");
        assertThat(codes(spec, List.of(self("E1", "01-01-1985"), dep("E1", "FATHER", "01-01-1980")))).contains("AGE_GAP_TOO_SMALL");
    }

    @Test
    void familyShapeLimits() {
        var kids = new ArrayList<>(List.of(self("E1", "01-01-1980")));
        for (String c : List.of("SON", "SON_II", "DAUGHTER", "DAUGHTER_II")) kids.add(dep("E1", c, "01-01-20" + (10 + kids.size())));
        assertThat(codes(kids)).contains("MAX_CHILDREN_EXCEEDED");
        assertThat(codes(List.of(self("E1", "01-01-1980"), dep("E1", "FATHER", "01-01-1950"), dep("E1", "FATHER_IN_LAW", "01-01-1950")))).contains("PARENTS_AND_IN_LAWS");
        assertThat(codes(List.of(self("E1", "01-01-1980"), dep("E1", "SON", "01-01-2010"), dep("E1", "SON", "01-01-2012")))).contains("DUPLICATE_RELATION");
        assertThat(codes(List.of(dep("E1", "SON", "01-01-2010")))).contains("SELF_COUNT");
        assertThat(codes(List.of(self("E1", "01-01-1980"), dep("E1", "UNCLE", "01-01-1950")))).contains("RELATION_NOT_COVERED");
    }

    @Test
    void twinsOnlyWhereThePolicyAllowsThem() {
        var rows = List.of(self("E1", "01-01-1980"), dep("E1", "SON", "01-01-2012"), dep("E1", "DAUGHTER", "01-01-2012"));
        assertThat(codes(rows)).contains("TWINS_NOT_ALLOWED");
        assertThat(codes(Specs.strict(), rows)).doesNotContain("TWINS_NOT_ALLOWED");
    }

    @Test
    void marriageDateRules() {
        var base = new ArrayList<>(List.of(self("E1", "01-01-1985"), dep("E1", "WIFE", "01-01-1987")));
        base.get(0).put("Date of Marriage", "01-01-2003");              // spouse was 16
        assertThat(codes(base)).contains("DOM_UNDER_18");
        base.get(0).put("Date of Marriage", "01-01-2030");
        assertThat(codes(base)).contains("DOM_NOT_IN_PAST");
        var noSpouse = new ArrayList<>(List.of(self("E1", "01-01-1985")));
        noSpouse.get(0).put("Date of Marriage", "01-01-2012");
        assertThat(codes(noSpouse)).contains("DOM_WITHOUT_SPOUSE");
        base.get(0).put("Date of Marriage", "01-01-2012");
        assertThat(codes(base)).doesNotContain("DOM_UNDER_18", "DOM_NOT_IN_PAST");
    }

    @Test
    void sumInsuredEffectiveDateGradeAndUniqueness() {
        var g = Specs.grade();
        var r = self("E1", "01-01-1985");
        r.put("Sum Insured", "123");
        r.put("Employee Grade", "Z");
        r.put("Effective Date", "01-01-2025");
        assertThat(codes(g, List.of(r))).contains("SUM_INSURED_NOT_OFFERED", "GRADE_UNKNOWN", "EFFECTIVE_DATE_OUTSIDE_POLICY", "DESIGNATION_MISSING");
        var a = self("E1", "01-01-1985"); var b = self("E2", "01-01-1986");
        b.put("Employee Email", a.get("Employee Email"));
        assertThat(codes(List.of(a, b))).contains("DUPLICATE_EMAIL");
        assertThat(codes(List.of(self("E1", "01-01-1985"), self("E2", "01-01-1986"), self("E1", "01-01-1985")))).contains("DUPLICATE_EMPLOYEE_CODE", "SELF_COUNT");
    }

    @Test
    void missingColumnsAndBadDatesAreReportedOnce() {
        var r = self("E1", "31/02/1985");
        r.remove("Sum Insured");
        var issues = SheetValidator.validate(Specs.flat(), "ADD", List.of(r), TODAY);
        assertThat(issues.stream().map(Issue::code)).contains("MISSING_COLUMN", "DOB_UNREADABLE");
        assertThat(SheetValidator.validate(Specs.flat(), "ADD", List.of(), TODAY).stream().map(Issue::code)).contains("EMPTY_SHEET");
    }

    @Test
    void headerSpellingsOfTheLegacySheetAreRecognised() {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("EMPLOYEE CODE", "E1"); r.put("employee first name", "A"); r.put("Employee DOB", "01-01-1985"); r.put("Insured Member DOB", "");
        r.put("Relationship With Employee", "SELF"); r.put("effective date", "01-04-2026"); r.put("SUMINSURED", "500000");
        assertThat(SheetValidator.validate(Specs.flat(), "ADD", List.of(r), TODAY)).isEmpty();
    }
}
