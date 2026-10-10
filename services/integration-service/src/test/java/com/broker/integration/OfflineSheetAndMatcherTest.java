package com.broker.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.broker.integration.tpa.OfflineMatcher;
import com.broker.integration.tpa.OfflineMatcher.Input;
import com.broker.integration.tpa.OfflineMatcher.Member;
import com.broker.integration.tpa.OfflineMatcher.Options;
import com.broker.integration.tpa.OfflineSheet;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class OfflineSheetAndMatcherTest {
    private static final Options OPT = new Options(false, Map.of(), false);

    private static Member m(long id, String code, String rel, String first, String start, String tpaId) {
        return new Member(id, code, rel, first, "R", start, tpaId, null, null, "abcd1234", null);
    }

    private static Input in(int line, String code, String rel, String id, String name, String start) { return new Input(line, code, rel, id, name, null, start, null); }

    @Test
    void headersAreRecognisedWhateverTheTpaCallsThemAndTitleRowsAreSkipped() {
        String csv = "Acme Health TPA - Member report\n\nEmp ID;Relation;Member ID;Member Name;Start Date\n007;Self;M1;Asha R;01/04/2030\n";
        var p = OfflineSheet.parse(csv.getBytes(StandardCharsets.UTF_8), "r.csv");
        assertThat(p.headerLine()).isEqualTo(3);
        assertThat(p.lines()).hasSize(1);
        assertThat(p.lines().getFirst().line()).isEqualTo(4);
        assertThat(p.lines().getFirst().get(OfflineSheet.Col.TPA_MEMBER_ID)).isEqualTo("M1");
        assertThat(OfflineSheet.isoDate("01/04/2030")).isEqualTo("2030-04-01");
        assertThat(OfflineSheet.isoDate("1-Apr-2030")).isEqualTo("2030-04-01");
    }

    @Test
    void numericCellsInAnXlsxKeepTheirDigitsAndDatesBecomeIso() throws Exception {
        try (var wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var s = wb.createSheet("Members");
            var h = s.createRow(0);
            String[] heads = {"Employee Code", "Relationship", "TPA Member Id", "Cover Start Date"};
            for (int i = 0; i < heads.length; i++) h.createCell(i).setCellValue(heads[i]);
            var r = s.createRow(1);
            r.createCell(0).setCellValue(1234);
            r.createCell(1).setCellValue("Self");
            r.createCell(2).setCellValue(9876543210.0);
            var c = r.createCell(3);
            c.setCellValue(java.time.LocalDate.of(2030, 4, 1));
            var style = wb.createCellStyle();
            style.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("dd-mmm-yyyy"));
            c.setCellStyle(style);
            wb.write(out);
            var line = OfflineSheet.parse(out.toByteArray(), "m.xlsx").lines().getFirst();
            assertThat(line.get(OfflineSheet.Col.EMPLOYEE_CODE)).isEqualTo("1234");
            assertThat(line.get(OfflineSheet.Col.TPA_MEMBER_ID)).isEqualTo("9876543210");
            assertThat(line.get(OfflineSheet.Col.COVER_START)).isEqualTo("2030-04-01");
        }
    }

    @Test
    void aFileThatIsNotASheetOrHasNoRecognisableHeaderIsRefusedPlainly() {
        assertThatThrownBy(() -> OfflineSheet.parse("a,b\n1,2\n".getBytes(StandardCharsets.UTF_8), "x.csv")).isInstanceOf(OfflineSheet.SheetException.class);
        assertThatThrownBy(() -> OfflineSheet.parse(new byte[] {1, 2, 3}, "x.xlsx")).isInstanceOf(OfflineSheet.SheetException.class);
    }

    @Test
    void aPasswordProtectedWorkbookSaysSo() throws Exception {
        var fs = new org.apache.poi.poifs.filesystem.POIFSFileSystem();
        var info = new org.apache.poi.poifs.crypt.EncryptionInfo(org.apache.poi.poifs.crypt.EncryptionMode.agile);
        var enc = info.getEncryptor();
        enc.confirmPassword("secret");
        try (var wb = new XSSFWorkbook(); var os = enc.getDataStream(fs); var out = new ByteArrayOutputStream()) {
            wb.createSheet("a").createRow(0).createCell(0).setCellValue("x");
            wb.write(os);
            os.close();
            fs.writeFilesystem(out);
            assertThatThrownBy(() -> OfflineSheet.parse(out.toByteArray(), "p.xlsx")).isInstanceOf(OfflineSheet.SheetException.class).hasMessageContaining("password");
        }
    }

    @Test
    void theLegacyChecksAreAllThere() {
        List<Member> roster = List.of(m(1, "E1", "SELF", "Asha", "2030-04-01", null), m(2, "E1", "SON", "Arjun", "2030-04-01", null), m(3, "E1", "SON_II", "Ishan", "2030-04-01", null),
                m(4, "E2", "SELF", "Ravi", "2030-04-01", "OLD"), m(5, "E3", "SELF", "Neha", "2030-04-01", "T-X"), m(6, "E1", "DAUGHTER", "Diya", "2030-04-01", null));
        var v = OfflineMatcher.match(List.of(
                in(2, "E1", "Self", "T1", "Asha R", "2030-04-01"),
                in(3, "E1", "Son", "T2", "Arjun R", "2030-05-01"),                          // cover start differs
                in(4, "E1", "Son II", "T3", "Ishan R", "2030-04-01"),
                in(5, "E9", "Self", "T4", "x", null),                                       // not on the policy
                in(6, "E1", "Wife", "T5", "x", null),                                       // no wife covered
                in(7, "E2", "Self", "T6", "Ravi R", null),                                  // replaces OLD
                in(8, "E3", "Self", "T-X", "Neha R", null),                                 // same id again
                in(9, "E1", "Daughter", "OLD", "x", null),                                  // OLD belongs to Ravi
                in(10, "", "Self", "T8", "x", null)),
                roster, OPT);
        assertThat(v.stream().map(x -> x.outcome().name() + ":" + (x.reasonCode() == null ? "" : x.reasonCode())).toList()).containsExactly(
                "ASSIGNED:", "REJECTED:COVER_START_MISMATCH", "ASSIGNED:", "REJECTED:EMPLOYEE_NOT_ON_POLICY", "REJECTED:RELATION_NOT_ON_ROSTER", "REASSIGNED:", "UPDATED:",
                "REJECTED:ID_IN_USE", "REJECTED:EMPLOYEE_CODE_MISSING");
        assertThat(v.get(5).previousTpaMemberId()).isEqualTo("OLD");
    }

    @Test
    void theCoverDateCheckCanBeSwitchedOffAndTheEndorsementReferenceIsChecked() {
        List<Member> roster = List.of(m(1, "E1", "SELF", "Asha", "2030-04-01", null));
        assertThat(OfflineMatcher.match(List.of(in(2, "E1", "Self", "T1", "Asha", "2030-06-01")), roster, new Options(false, Map.of(), true)).getFirst().outcome()).isEqualTo(OfflineMatcher.Outcome.ASSIGNED);
        var bad = new Input(2, "E1", "Self", "T1", "Asha", null, null, "ffff0000");
        assertThat(OfflineMatcher.match(List.of(bad), roster, OPT).getFirst().reasonCode()).isEqualTo("ENDORSEMENT_REF_MISMATCH");
        var good = new Input(2, "E1", "Self", "T1", "Asha", null, null, "ABCD1234");
        assertThat(OfflineMatcher.match(List.of(good), roster, OPT).getFirst().accepted()).isTrue();
    }

    @Test
    void twoDaughtersAreToldApartByNameAndAnEcardAloneNeedsAnExistingId() {
        List<Member> roster = List.of(m(1, "E1", "DAUGHTER", "Diya", "2030-04-01", null), m(2, "E1", "DAUGHTER_II", "Disha", "2030-04-01", "T9"));
        var v = OfflineMatcher.match(List.of(new Input(2, "E1", "Daughter", "T1", "Diya R", null, null, null),
                new Input(3, "E1", "Daughter II", "", "", "https://tpa.example/c/2", null, null)), roster, OPT);
        assertThat(v.get(0).outcome()).isEqualTo(OfflineMatcher.Outcome.ASSIGNED);
        assertThat(v.get(1).outcome()).isEqualTo(OfflineMatcher.Outcome.UPDATED);
        var cardOnly = OfflineMatcher.match(List.of(new Input(2, "E1", "Daughter", "", "", "https://tpa.example/c/1", null, null)), roster, OPT);
        assertThat(cardOnly.getFirst().reasonCode()).isEqualTo("ID_MISSING");
    }
}
