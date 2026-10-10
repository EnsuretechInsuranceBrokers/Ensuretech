package com.broker.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.broker.policy.domain.PremiumType;
import com.broker.policy.domain.PremiumTypeRepository;
import com.broker.policy.engine.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PremiumEngineTest extends IntegrationTestBase {
    @Autowired PremiumEngine engine;
    @Autowired PremiumTypeRepository types;

    static final BigDecimal TAX = new BigDecimal("18.00");
    static final List<Map<String, Object>> BANDS = List.of(band(0, 17, 500), band(18, 45, 1000), band(46, 60, 1500), band(61, 99, 2500));

    static Map<String, Object> band(int from, int to, int premium) {
        return Map.of("from", from, "to", to, "premium", premium);
    }

    static Member self(int age) { return new Member("E1", "SELF", age, "A", "MGR", new BigDecimal("500000"), null); }
    static Member spouse(int age) { return new Member("S1", "SPOUSE", age, null, null, new BigDecimal("500000"), null); }
    static Member son(int age) { return new Member("C1", "SON", age, null, null, new BigDecimal("500000"), null); }

    PremiumType type(String code) {
        return types.findAll().stream().filter(t -> t.getCode().equals(code)).findFirst().orElseThrow();
    }

    private static List<BigDecimal> premiums(PremiumEngine.Quote q) {
        return q.members().stream().map(PremiumEngine.MemberPremium::premium).toList();
    }

    /** A valid table for whichever rater serves this premium type. */
    static Map<String, Object> tableFor(PremiumType t) {
        return switch (t.getRaterClass()) {
            case "FlatRater", "ProRataRater" -> "FLAT_FAMILY_DEFINITION".equals(t.getCode())
                    ? Map.of("family_definitions", Map.of("0", 900, "1", 1500, "2", 2000)) : Map.of("flat", 1000);
            case "AgeRater", "ProRataAgeRater" -> Map.of("age_bands", BANDS);
            case "GDACRater" -> Map.of("gdac_bands", BANDS);
            case "GradeRater" -> Map.of("grades", Map.of("A", 5000, "B", 4000));
            case "DesignationRater" -> Map.of("designations", Map.of("MGR", 3000));
            case "MatrixRater" -> t.getConstructRaters().contains("GRADE")
                    ? Map.of("grade_age_matrix", Map.of("A", BANDS)) : Map.of("designation_age_matrix", Map.of("MGR", BANDS));
            case "LOLRater" -> Map.of("lol", List.of(Map.of("limit", 500000, "premium", 1800)));
            case "PermillyRater" -> Map.of("permille", "3.5");
            case "FlexRater" -> Map.of("layers", Map.of("base", 1000, "enhance", 300, "flex", 150));
            case "BaseTopUpRater" -> Map.of("base", 1000, "topup", 400);
            case "BundleRater" -> Map.of("gmc", 1000, "wellness", 250);
            case "OpdFlatRater" -> Map.of("opd_flat", 600);
            case "OpdGradeRater" -> Map.of("opd_grades", Map.of("A", 900));
            case "CompositeRater", "ConstructRater" -> Map.of("sections", List.of(
                    Map.of("rater", "AgeRater", "table", Map.of("age_bands", BANDS)),
                    Map.of("rater", "GradeRater", "table", Map.of("grades", Map.of("A", 5000)))));
            default -> throw new IllegalStateException("no test table for " + t.getRaterClass());
        };
    }

    @Test
    void everyOneOfThe32PremiumTypesHasARaterAndPricesAFamily() {
        List<PremiumType> all = types.findAll();
        assertThat(all).hasSize(32);
        for (PremiumType t : all) {
            assertThat(engine.supports(t.getRaterClass())).as("rater for %s", t.getCode()).isTrue();
            Map<String, Object> table = tableFor(t);
            assertThat(engine.validate(t, table)).as("table valid for %s", t.getCode()).isEmpty();

            List<Member> family = switch (t.getCode()) {
                case "FLAT_EMPLOYEE_ONLY" -> List.of(self(40));
                case "FLAT_EMPLOYEE_PLUS_1" -> List.of(self(40), spouse(38));
                default -> List.of(self(40), spouse(38), son(10));
            };
            PremiumEngine.Quote q = engine.quote(t, table, TAX, family);

            assertThat(q.members()).as(t.getCode()).hasSize(family.size());
            assertThat(q.subtotal()).as("subtotal %s", t.getCode()).isPositive();
            assertThat(q.tax()).as("tax %s", t.getCode()).isEqualByComparingTo(q.subtotal().multiply(new BigDecimal("0.18")).setScale(2, java.math.RoundingMode.HALF_UP));
            assertThat(q.total()).as("total %s", t.getCode()).isEqualByComparingTo(q.subtotal().add(q.tax()));
        }
    }

    @Test
    void flatPerMemberVsFamilyUnit() {
        var family = List.of(self(40), spouse(38), son(10));
        assertThat(premiums(engine.quote(type("FLAT"), Map.of("flat", 1000), TAX, family)))
                .allSatisfy(p -> assertThat(p).isEqualByComparingTo("1000"));
        // floater: one charge for the whole family, booked on the employee
        var floater = premiums(engine.quote(type("FAMILY_FLOATER"), Map.of("flat", 1000), TAX, family));
        assertThat(floater).extracting(BigDecimal::intValue).containsExactly(1000, 0, 0);
    }

    @Test
    void employeeOnlyAndPlusNCapsAreEnforced() {
        var t = type("FLAT_EMPLOYEE_PLUS_1");
        assertThatThrownBy(() -> engine.quote(t, Map.of("flat", 1500), TAX, List.of(self(40), spouse(38), son(10))))
                .isInstanceOf(RatingException.class).hasMessageContaining("at most 1 dependent");
        assertThatThrownBy(() -> engine.quote(type("FLAT_EMPLOYEE_ONLY"), Map.of("flat", 800), TAX, List.of(self(40), spouse(38))))
                .isInstanceOf(RatingException.class);
    }

    @Test
    void familyDefinitionIsKeyedByDependentCount() {
        Map<String, Object> table = Map.of("family_definitions", Map.of("0", 900, "1", 1500, "2", 2000));
        assertThat(engine.quote(type("FLAT_FAMILY_DEFINITION"), table, TAX, List.of(self(40), spouse(38))).subtotal())
                .isEqualByComparingTo("1500");
        assertThatThrownBy(() -> engine.quote(type("FLAT_FAMILY_DEFINITION"), table, TAX,
                List.of(self(40), spouse(38), son(10), son(8))))
                .isInstanceOf(RatingException.class).hasMessageContaining("family definition");
    }

    @Test
    void ageBandBoundariesAreInclusive() {
        var t = type("AGE_BAND");
        Map<String, Object> table = Map.of("age_bands", BANDS);
        int[][] cases = {{17, 500}, {18, 1000}, {45, 1000}, {46, 1500}, {60, 1500}, {61, 2500}, {99, 2500}, {0, 500}};
        for (int[] c : cases) {
            assertThat(engine.quote(t, table, TAX, List.of(self(c[0]))).subtotal())
                    .as("age %d", c[0]).isEqualByComparingTo(String.valueOf(c[1]));
        }
        assertThatThrownBy(() -> engine.quote(t, table, TAX, List.of(self(100))))
                .isInstanceOf(RatingException.class).hasMessageContaining("age 100");
    }

    @Test
    void ageFloaterUsesEldestMember() {
        var q = engine.quote(type("FAMILY_FLOATER_AGE"), Map.of("age_bands", BANDS), TAX, List.of(self(40), spouse(62), son(10)));
        assertThat(premiums(q)).extracting(BigDecimal::intValue).containsExactly(2500, 0, 0);
    }

    @Test
    void gdacBoundaryAges() {
        var t = type("GDAC");
        Map<String, Object> table = Map.of("gdac_bands", List.of(band(0, 60, 1000), band(61, 65, 1800), band(66, 99, 3000)));
        for (int[] c : new int[][] {{60, 1000}, {61, 1800}, {65, 1800}, {66, 3000}}) {
            assertThat(engine.quote(t, table, TAX, List.of(self(c[0]))).subtotal()).as("age %d", c[0])
                    .isEqualByComparingTo(String.valueOf(c[1]));
        }
    }

    @Test
    void dependentsInheritEmployeeGrade() {
        var q = engine.quote(type("GRADE"), Map.of("grades", Map.of("A", 5000, "B", 4000)), TAX,
                List.of(self(40), spouse(38)));
        assertThat(premiums(q)).extracting(BigDecimal::intValue).containsExactly(5000, 5000);
        assertThatThrownBy(() -> engine.quote(type("GRADE"), Map.of("grades", Map.of("B", 4000)), TAX, List.of(self(40))))
                .isInstanceOf(RatingException.class).hasMessageContaining("grade 'A'");
    }

    @Test
    void lolRequiresExactLimit() {
        var t = type("LOL");
        Map<String, Object> table = Map.of("lol", List.of(Map.of("limit", 500000, "premium", 1800), Map.of("limit", 1000000, "premium", 3100)));
        assertThat(engine.quote(t, table, TAX, List.of(self(40))).subtotal()).isEqualByComparingTo("1800");
        var odd = new Member("E1", "SELF", 40, "A", "MGR", new BigDecimal("750000"), null);
        assertThatThrownBy(() -> engine.quote(t, table, TAX, List.of(odd))).isInstanceOf(RatingException.class);
    }

    @Test
    void permilleIsSumInsuredTimesRateOver1000() {
        assertThat(engine.quote(type("PERMILLY"), Map.of("permille", 3.5), TAX, List.of(self(40))).subtotal())
                .isEqualByComparingTo("1750.00");
    }

    @Test
    void flexLayersAreSelectedPerMember() {
        var t = type("BASE_ENHANCE_FLEX");
        Map<String, Object> table = Map.of("layers", Map.of("base", 1000, "enhance", 300, "flex", 150));
        var withLayers = new Member("E1", "SELF", 40, "A", "MGR", null, List.of("base", "enhance", "flex"));
        var baseOnly = new Member("S1", "SPOUSE", 38, null, null, null, null);
        assertThat(premiums(engine.quote(t, table, TAX, List.of(withLayers, baseOnly))))
                .extracting(BigDecimal::intValue).containsExactly(1450, 1000);
        var bad = new Member("E1", "SELF", 40, "A", "MGR", null, List.of("platinum"));
        assertThatThrownBy(() -> engine.quote(t, table, TAX, List.of(bad))).isInstanceOf(RatingException.class);
    }

    @Test
    void compositeSumsSectionsAndRejectsNesting() {
        var t = type("COMPOSITE");
        assertThat(engine.quote(t, tableFor(t), TAX, List.of(self(40))).subtotal()).isEqualByComparingTo("6000"); // 1000 age + 5000 grade
        Map<String, Object> nested = Map.of("sections", List.of(Map.of("rater", "CompositeRater", "table", Map.of())));
        assertThat(engine.validate(t, nested)).anyMatch(m -> m.contains("non-nestable"));
    }

    @Test
    void validationRejectsMalformedTables() {
        assertThat(engine.validate(type("FLAT"), Map.of())).isNotEmpty();
        assertThat(engine.validate(type("FLAT"), Map.of("flat", "abc"))).anyMatch(m -> m.contains("number"));
        assertThat(engine.validate(type("FLAT"), Map.of("flat", -5))).anyMatch(m -> m.contains("negative"));
        assertThat(engine.validate(type("AGE_BAND"), Map.of("age_bands", List.of(band(0, 20, 1), band(18, 30, 2)))))
                .anyMatch(m -> m.contains("overlapping"));
        assertThat(engine.validate(type("AGE_BAND"), Map.of("age_bands", List.of(band(30, 20, 1)))))
                .anyMatch(m -> m.contains("from > to"));
        assertThatThrownBy(() -> engine.quote(type("FLAT"), Map.of(), TAX, List.of(self(40))))
                .isInstanceOf(InvalidRateTableException.class);
    }

    @Test
    void roundingIsHalfUpToTwoDecimalsAndTaxIsOnSubtotal() {
        var q = engine.quote(type("PERMILLY"), Map.of("permille", "0.333"), TAX,
                List.of(new Member("E1", "SELF", 40, null, null, new BigDecimal("1000"), null)));
        assertThat(q.subtotal()).isEqualByComparingTo("0.33");
        assertThat(q.tax()).isEqualByComparingTo("0.06");
        assertThat(q.total()).isEqualByComparingTo("0.39");
    }
}
