package com.broker.integration.tpa;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.openxml4j.util.ZipSecureFile;

/**
 * Reads the sheet a TPA sends back: member ids (and e-cards) for people we asked it to enrol. TPAs each have their own column names and their own
 * Excel habits, so this finds the header row itself, understands the usual spellings, keeps ids as the text they were typed as (1234.0 is 1234, a
 * leading zero stays), turns every kind of date into ISO, and never trusts a row it cannot read: that one is reported with its line number.
 */
public final class OfflineSheet {
    private OfflineSheet() {}

    public static final int MAX_ROWS = 50_000, MAX_BYTES = 10 * 1024 * 1024, HEADER_SEARCH_ROWS = 15;

    public enum Col { EMPLOYEE_CODE, RELATION, TPA_MEMBER_ID, TPA_MEMBER_NAME, ECARD_URL, COVER_START, ENDORSEMENT_REF }

    /** What each column may be called, normalised (lower case, letters and digits only). The first four are what the legacy sheet used. */
    static final Map<Col, List<String>> SYNONYMS = Map.of(
            Col.EMPLOYEE_CODE, List.of("employeecode", "empcode", "employeeid", "empid", "employeeno", "empno", "employeenumber", "staffid", "code"),
            Col.RELATION, List.of("relationshipwithemployee", "relationship", "relation", "relationwithemployee", "relationtoemployee", "memberrelation", "rel"),
            Col.TPA_MEMBER_ID, List.of("tpamemberid", "tpaid", "memberid", "membershipno", "membershipnumber", "uhid", "healthid", "tpamemberno", "cardno", "cardnumber", "tpauniqueid"),
            Col.TPA_MEMBER_NAME, List.of("tpamembername", "membername", "nameasspertpa", "nameintpa", "tpaname", "patientname", "name"),
            Col.ECARD_URL, List.of("ecardurl", "ecard", "ecardlink", "ecardpath", "cardurl", "cardlink", "healthcardurl", "ecarddownloadlink"),
            Col.COVER_START, List.of("coverstartdate", "coverstart", "startdate", "dateofcoverstart", "policystartdate", "memberstartdate", "inceptiondate", "dateofjoining"),
            Col.ENDORSEMENT_REF, List.of("endorsementref", "endorsementid", "endorsementnumber", "endorsementno", "endorsementreference", "endorsementref."));

    public record Line(int line, Map<Col, String> cells) {
        public String get(Col c) { String v = cells.get(c); return v == null || v.isBlank() ? null : v.trim(); }
    }

    public record Parsed(List<Line> lines, Map<Col, String> headers, int headerLine, List<String> warnings) {}

    public static class SheetException extends RuntimeException {
        public SheetException(String message) { super(message); }
    }

    static String norm(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }

    static Col recognise(String header) {
        String n = norm(header);
        if (n.isEmpty()) return null;
        for (Col c : Col.values()) if (SYNONYMS.get(c).contains(n)) return c;
        return null;
    }

    public static Parsed parse(byte[] data, String filename) {
        if (data == null || data.length == 0) throw new SheetException("The file is empty.");
        if (data.length > MAX_BYTES) throw new SheetException("The file is larger than " + MAX_BYTES / (1024 * 1024) + " MB. Split it by policy.");
        List<List<String>> table = filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".csv") ? csv(data) : workbook(data);
        // the header row is the first, within the first few, that names at least the employee code and either the relation or the member id
        int hdr = -1;
        Map<Integer, Col> cols = new LinkedHashMap<>();
        for (int r = 0; r < Math.min(table.size(), HEADER_SEARCH_ROWS) && hdr < 0; r++) {
            Map<Integer, Col> found = new LinkedHashMap<>();
            List<String> row = table.get(r);
            for (int c = 0; c < row.size(); c++) { Col col = recognise(row.get(c)); if (col != null && !found.containsValue(col)) found.put(c, col); }
            if (found.containsValue(Col.EMPLOYEE_CODE) && (found.containsValue(Col.TPA_MEMBER_ID) || found.containsValue(Col.ECARD_URL))) { hdr = r; cols = found; }
        }
        if (hdr < 0) {
            throw new SheetException("I could not find the header row. The sheet needs at least an Employee code column and a TPA member id (or E-card URL) column "
                    + "in its first " + HEADER_SEARCH_ROWS + " rows. Download the sheet for this policy to see the expected columns.");
        }
        List<String> warnings = new ArrayList<>();
        if (!cols.containsValue(Col.RELATION)) warnings.add("There is no relationship column: only employees (SELF) can be matched.");
        List<Line> lines = new ArrayList<>();
        for (int r = hdr + 1; r < table.size(); r++) {
            List<String> row = table.get(r);
            Map<Col, String> cells = new LinkedHashMap<>();
            boolean any = false;
            for (var e : cols.entrySet()) {
                String v = e.getKey() < row.size() ? row.get(e.getKey()) : "";
                if (v != null && !v.isBlank()) any = true;
                cells.put(e.getValue(), v == null ? "" : v.trim());
            }
            if (!any) continue;                                   // a blank line is not a row
            if (lines.size() >= MAX_ROWS) throw new SheetException("More than " + MAX_ROWS + " rows. Split the sheet by policy.");
            lines.add(new Line(r + 1, cells));
        }
        if (lines.isEmpty()) throw new SheetException("The sheet has a header but no rows.");
        Map<Col, String> names = new LinkedHashMap<>();
        for (var e : cols.entrySet()) names.put(e.getValue(), table.get(hdr).get(e.getKey()).trim());
        return new Parsed(lines, names, hdr + 1, warnings);
    }

    // ── Excel ────────────────────────────────────────────────────────────────────────────────────────────────────────────────
    private static List<List<String>> workbook(byte[] data) {
        ZipSecureFile.setMinInflateRatio(0.01);                 // a "zip bomb" xlsx is refused instead of unpacked
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            Sheet sheet = null;
            for (int i = 0; i < wb.getNumberOfSheets() && sheet == null; i++) if (!wb.isSheetHidden(i) && wb.getSheetAt(i).getPhysicalNumberOfRows() > 0) sheet = wb.getSheetAt(i);
            if (sheet == null) throw new SheetException("The workbook has no sheet with any rows in it.");
            DataFormatter fmt = new DataFormatter(Locale.ENGLISH);
            List<List<String>> out = new ArrayList<>();
            int last = Math.min(sheet.getLastRowNum(), MAX_ROWS + HEADER_SEARCH_ROWS + 10);
            for (int r = 0; r <= last; r++) {
                Row row = sheet.getRow(r);
                List<String> cells = new ArrayList<>();
                if (row != null) for (int c = 0; c < Math.min(row.getLastCellNum() < 0 ? 0 : row.getLastCellNum(), 60); c++) cells.add(text(row.getCell(c), fmt));
                out.add(cells);
            }
            return out;
        } catch (SheetException e) {
            throw e;
        } catch (org.apache.poi.EncryptedDocumentException e) {
            throw new SheetException("That file is password-protected. Remove the password in Excel (File, Info, Protect Workbook) and upload it again.");
        } catch (IOException | RuntimeException e) {
            throw new SheetException("That does not look like an Excel or CSV file I can read (" + e.getClass().getSimpleName() + "). Save it as .xlsx or .csv and try again.");
        }
    }

    /** A cell as the text a person typed: whole numbers lose Excel's trailing .0, dates become ISO, formulas use their last result. */
    static String text(Cell cell, DataFormatter fmt) {
        if (cell == null) return "";
        CellType t = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        switch (t) {
            case STRING: return cell.getStringCellValue().trim();
            case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) { var d = cell.getLocalDateTimeCellValue(); return d == null ? "" : d.toLocalDate().toString(); }
                double v = cell.getNumericCellValue();
                if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
                return java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
            default: return fmt.formatCellValue(cell).trim();
        }
    }

    // ── CSV ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────
    private static List<List<String>> csv(byte[] data) {
        Charset cs = StandardCharsets.UTF_8;
        String s = new String(data, cs);
        if (s.indexOf('�') >= 0) s = new String(data, Charset.forName("windows-1252"));
        if (s.startsWith("﻿")) s = s.substring(1);
        String first = String.join("\n", s.lines().limit(HEADER_SEARCH_ROWS).toList());      // a title row above the header must not decide the delimiter
        char sep = count(first, ';') > count(first, ',') && count(first, ';') >= count(first, '\t') ? ';' : count(first, '\t') > count(first, ',') ? '\t' : ',';
        List<List<String>> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (q) { if (ch == '"') { if (i + 1 < s.length() && s.charAt(i + 1) == '"') { cell.append('"'); i++; } else q = false; } else cell.append(ch); }
            else if (ch == '"') q = true;
            else if (ch == sep) { row.add(cell.toString().trim()); cell.setLength(0); }
            else if (ch == '\n' || ch == '\r') { if (ch == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') i++; row.add(cell.toString().trim()); cell.setLength(0); out.add(row); row = new ArrayList<>();
                if (out.size() > MAX_ROWS + HEADER_SEARCH_ROWS + 10) break; }
            else cell.append(ch);
        }
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString().trim()); out.add(row); }
        return out;
    }

    private static int count(String s, char c) { int n = 0; for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++; return n; }

    // ── dates ────────────────────────────────────────────────────────────────────────────────────────────────────────────────
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("d/M/uuuu"), DateTimeFormatter.ofPattern("d-M-uuuu"), DateTimeFormatter.ofPattern("d.M.uuuu"),
            DateTimeFormatter.ofPattern("d-MMM-uuuu", Locale.ENGLISH), DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH), DateTimeFormatter.ofPattern("d-MMMM-uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.ENGLISH), DateTimeFormatter.ofPattern("uuuu/M/d"));

    /** Any date a TPA or Excel might hand us, as ISO; null when it is not a date. Day first, as everywhere in India. */
    public static String isoDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String t = raw.trim();
        if (t.matches("\\d{5}(\\.0+)?")) { long n = Long.parseLong(t.replaceAll("\\..*", "")); if (n > 1 && n < 80000) return LocalDate.of(1899, 12, 30).plusDays(n).toString(); }
        if (t.length() > 10 && t.matches("\\d{4}-\\d{2}-\\d{2}[T ].*")) t = t.substring(0, 10);
        for (DateTimeFormatter f : DATE_FORMATS) { try { return LocalDate.parse(t, f).toString(); } catch (DateTimeParseException ignored) { /* next */ } }
        return null;
    }
}
