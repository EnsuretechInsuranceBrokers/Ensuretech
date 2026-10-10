package com.broker.sheetgen;

import static org.assertj.core.api.Assertions.assertThat;

import com.broker.sheetgen.gen.*;
import com.broker.sheetgen.io.SheetWriter;
import com.broker.sheetgen.spec.SheetSpec;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

class DeriveAndWriterTest {
    static final LocalDate TODAY = GeneratorTest.TODAY;

    static Sheet source(SheetSpec spec, int families) {
        return SheetGenerator.add(spec, new Options(families, 90, 70, 20, 40, 50, false, false, null, 21L, null, null, null, null), new SheetGenerator.Ids("GEN", 1, 5), TODAY);
    }

    private static String key(Map<String, String> r) { return r.get("Employee Code") + "|" + r.get("Relationship with Employee"); }

    @Test
    void removeSheetsNameExistingMembersAndPassTheValidator() {
        for (SheetSpec spec : List.of(Specs.flat(), Specs.grade(), Specs.strict())) {
            Sheet src = source(spec, 300);
            Set<String> existing = src.byHeader().stream().map(DeriveAndWriterTest::key).collect(Collectors.toSet());
            for (SheetDerive.RemoveMode mode : SheetDerive.RemoveMode.values()) {
                Sheet rm = SheetDerive.remove(spec, src.byHeader(), 60, mode, 3L, TODAY);
                assertThat(SheetValidator.validate(spec, "REMOVE", rm.byHeader(), TODAY)).isEmpty();
                assertThat(rm.byHeader().stream().map(DeriveAndWriterTest::key)).allMatch(existing::contains);
                if (mode == SheetDerive.RemoveMode.MEMBER) assertThat(rm.rows()).allSatisfy(r -> assertThat(r.get(Column.RELATION)).isNotEqualTo("SELF"));
                if (mode == SheetDerive.RemoveMode.FAMILY) {
                    Map<String, Long> srcSize = src.rows().stream().collect(Collectors.groupingBy(r -> r.get(Column.EMP_CODE), Collectors.counting()));
                    Map<String, Long> rmSize = rm.rows().stream().collect(Collectors.groupingBy(r -> r.get(Column.EMP_CODE), Collectors.counting()));
                    rmSize.forEach((code, n) -> assertThat(n).isEqualTo(srcSize.get(code)));
                }
            }
        }
    }

    @Test
    void aLeavingDateIsNeverBeforeTheMemberJoined() {
        SheetSpec spec = Specs.flat();
        Sheet src = source(spec, 400);
        Map<String, LocalDate> joined = new HashMap<>();
        src.rows().forEach(r -> joined.put(r.get(Column.EMP_CODE), Rules.parseDate(r.get(Column.EFFECTIVE))));
        Sheet rm = SheetDerive.remove(spec, src.byHeader(), 200, SheetDerive.RemoveMode.FAMILY, 4L, TODAY);
        for (var r : rm.rows()) assertThat(Rules.parseDate(r.get(Column.EFFECTIVE))).isAfterOrEqualTo(joined.get(r.get(Column.EMP_CODE)));
    }

    @Test
    void updateSheetsChangeOnlyWhatThePolicyAllowsAndPassTheValidator() {
        SheetSpec spec = Specs.grade();
        Sheet src = source(spec, 400);
        Set<String> existing = src.byHeader().stream().map(DeriveAndWriterTest::key).collect(Collectors.toSet());
        Sheet up = SheetDerive.update(spec, src.byHeader(), 150, 6L, TODAY);
        assertThat(up.rows()).isNotEmpty();
        assertThat(SheetValidator.validate(spec, "UPDATE", up.byHeader(), TODAY)).isEmpty();
        assertThat(up.byHeader().stream().map(DeriveAndWriterTest::key)).allMatch(existing::contains);
        Set<String> kinds = up.columns().stream().map(Column::id).collect(Collectors.toSet());
        assertThat(kinds).contains(Column.SUM_INSURED, Column.GRADE, Column.EMP_MOBILE);
        assertThat(kinds).doesNotContain(Column.EMP_DOB, Column.MEM_DOB);       // a birth date is never "updated" blindly
    }

    @Test
    void anUpdateRowWithNothingToChangeIsReported() {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("Employee Code", "E1"); r.put("Relationship with Employee", "SELF"); r.put("Effective Date", "01-05-2026");
        assertThat(SheetValidator.validate(Specs.flat(), "UPDATE", List.of(r), TODAY).stream().map(Issue::code)).contains("NOTHING_TO_UPDATE");
    }

    @Test
    void xlsxRoundTripsAsTextWithTheLegacyHeaders() throws Exception {
        Sheet s = source(Specs.grade(), 5);
        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(SheetWriter.xlsx(s)))) {
            var ws = wb.getSheetAt(0);
            assertThat(ws.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Employee Code");
            assertThat(ws.getLastRowNum()).isEqualTo(s.rows().size());
            int dob = s.columns().indexOf(Column.ALL.stream().filter(c -> c.id().equals(Column.EMP_DOB)).findFirst().get());
            assertThat(ws.getRow(1).getCell(dob).getStringCellValue()).matches("\\d{2}-\\d{2}-\\d{4}");
        }
    }

    @Test
    void csvQuotesAndNeutralisesFormulas() {
        List<Column> cols = Column.ALL.subList(0, 2);
        Sheet s = new Sheet("ADD", cols, List.of(Map.of(Column.EMP_CODE, "=HYPERLINK(\"x\")", Column.EMP_FIRST, "A,B")), List.of());
        String csv = new String(SheetWriter.csv(s), StandardCharsets.UTF_8);
        assertThat(csv).isEqualTo("Employee Code,Employee First Name\r\n\"'=HYPERLINK(\"\"x\"\")\",\"A,B\"\r\n");
    }
}
