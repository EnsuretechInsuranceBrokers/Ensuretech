package com.broker.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.broker.integration.tpa.Reconciler;
import com.broker.integration.tpa.Reconciler.ActiveLife;
import com.broker.integration.tpa.TpaAdapter.RosterMember;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The matching rules of the legacy assignTpaMemberId, minus its bugs. */
class ReconcilerTest {
    private static RosterMember m(long id, String code, String rel, String first, String last) {
        return new RosterMember(id, code, rel, first, last, "1990-01-01", null, null, null, "500000", "2030-04-01", null, true);
    }

    private static final List<RosterMember> ROSTER = List.of(m(1, "E100", "SELF", "Asha", "Rao"), m(2, "E100", "WIFE", "Meera", "Rao"),
            m(3, "E100", "SON", "Arjun", "Rao"), m(4, "E100", "DAUGHTER", "Diya", "Rao"), m(5, "E100", "SON_II", "Kabir", "Rao"),
            m(6, "007", "SELF", "Zed", "Z"));

    private static Map<Long, String> ids(Reconciler.Result r) {
        return r.assignments().stream().collect(Collectors.toMap(Reconciler.Assignment::rosterId, Reconciler.Assignment::tpaMemberId));
    }

    @Test
    void codesMatchTrimmedAndCaseInsensitivelyAndLeadingZerosOnlyWhenAsked() {
        var lives = List.of(new ActiveLife("  e100 ", "Self", "T1", "Asha Rao"), new ActiveLife("7", "Employee", "T2", "Zed Z"));
        var plain = Reconciler.reconcile(lives, ROSTER, Map.of(), false);
        assertThat(ids(plain)).containsExactly(Map.entry(1L, "T1"));
        assertThat(plain.misses()).singleElement().satisfies(x -> assertThat(x.reason()).contains("no such member"));
        assertThat(ids(Reconciler.reconcile(lives, ROSTER, Map.of(), true))).containsEntry(1L, "T1").containsEntry(6L, "T2");   // Care: 007 == 7
    }

    @Test
    void relationLabelsGoThroughTheMappingAndSpouseCoversHusbandAndWife() {
        var lives = List.of(new ActiveLife("E100", "WIFE", "T3", "Meera Rao"), new ActiveLife("E100", "Dependent Father??", "T4", "x"));
        var r = Reconciler.reconcile(lives, ROSTER, Map.of(), false);
        assertThat(ids(r)).containsExactly(Map.entry(2L, "T3"));
        assertThat(r.misses()).singleElement().satisfies(x -> assertThat(x.reason()).contains("not mapped"));
        var custom = Reconciler.reconcile(List.of(new ActiveLife("E100", "Dependent Father??", "T4", "x")), ROSTER, Map.of("dependent father??", "SELF"), false);
        assertThat(ids(custom)).containsEntry(1L, "T4");
    }

    @Test
    void childrenAreToldApartByNameNotPositionAndNeverClaimedTwice() {
        var lives = List.of(new ActiveLife("E100", "Son", "T5", "KABIR RAO"), new ActiveLife("E100", "Son", "T6", "Arjun Rao"),
                new ActiveLife("E100", "Daughter", "T7", "Diya"));        // the name starts the string: the legacy strpos test failed exactly here
        var r = Reconciler.reconcile(lives, ROSTER, Map.of(), false);
        assertThat(ids(r)).containsEntry(5L, "T5").containsEntry(3L, "T6").containsEntry(4L, "T7");
        assertThat(r.misses()).isEmpty();
        var twice = Reconciler.reconcile(List.of(new ActiveLife("E100", "Son", "T5", "Arjun Rao"), new ActiveLife("E100", "Son", "T8", "Arjun Rao")), ROSTER, Map.of(), false);
        assertThat(ids(twice)).containsExactly(Map.entry(3L, "T5"));
        assertThat(twice.misses()).singleElement().satisfies(x -> assertThat(x.memberId()).isEqualTo("T8"));
    }

    @Test
    void anUnknownChildNameOrAnAmbiguousOneIsReportedNotGuessed() {
        var twins = List.of(m(1, "E1", "SELF", "A", "X"), m(2, "E1", "SON", "Rohan", "X"), m(3, "E1", "SON_II", "Rohan", "X"));
        var amb = Reconciler.reconcile(List.of(new ActiveLife("E1", "Son", "T1", "Rohan X")), twins, Map.of(), false);
        assertThat(amb.assignments()).isEmpty();
        assertThat(amb.misses().getFirst().reason()).contains("more than one");
        var none = Reconciler.reconcile(List.of(new ActiveLife("E100", "Son", "T1", "Someone Else")), ROSTER, Map.of(), false);
        assertThat(none.misses().getFirst().reason()).contains("matches the name");
    }

    @Test
    void nestedDependentsInheritTheEmployeeCodeAndRecordsWithoutAnIdAreMisses() {
        var life = new ActiveLife("E100", "Self", "T1", "Asha Rao", List.of(new ActiveLife(null, "Wife", "T2", "Meera Rao"), new ActiveLife(null, "Son", "", "Arjun Rao")));
        var r = Reconciler.reconcile(List.of(life), ROSTER, Map.of(), false);
        assertThat(ids(r)).containsEntry(1L, "T1").containsEntry(2L, "T2").hasSize(2);
        assertThat(r.misses()).singleElement().satisfies(x -> assertThat(x.reason()).contains("no member id"));
        assertThat(r.rosterUnmatched()).isEqualTo(4);                                  // sons 3 and 5, daughter 4 and employee 007
    }
}
