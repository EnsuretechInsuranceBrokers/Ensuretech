package com.broker.sheetgen.io;

import com.broker.sheetgen.gen.Column;
import com.broker.sheetgen.gen.Sheet;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/** Writes a sheet as .xlsx (streamed) or .csv. Every cell is text, so Excel never reformats a date, a code or a mobile number. */
public final class SheetWriter {
    private SheetWriter() {}

    /** A cell that starts with one of these is a formula to Excel; neutralise it (OWASP "CSV injection"). */
    static String safe(String v) {
        if (v == null || v.isEmpty()) return "";
        char c = v.charAt(0);
        return (c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r') ? "'" + v : v;
    }

    public static byte[] xlsx(Sheet sheet) {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            SXSSFSheet ws = wb.createSheet(sheet.operation());
            CellStyle text = wb.createCellStyle();
            text.setDataFormat(wb.createDataFormat().getFormat("@"));
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            head.setFont(bold);
            List<Column> cols = sheet.columns();
            Row h = ws.createRow(0);
            for (int c = 0; c < cols.size(); c++) {
                var cell = h.createCell(c);
                cell.setCellValue(cols.get(c).header());
                cell.setCellStyle(head);
            }
            int r = 1;
            for (Map<String, String> row : sheet.rows()) {
                Row x = ws.createRow(r++);
                for (int c = 0; c < cols.size(); c++) {
                    var cell = x.createCell(c, CellType.STRING);
                    cell.setCellValue(safe(row.getOrDefault(cols.get(c).id(), "")));
                    cell.setCellStyle(text);
                }
            }
            ws.createFreezePane(0, 1);
            wb.write(out);
            wb.dispose();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("could not build the workbook", e);
        }
    }

    public static byte[] csv(Sheet sheet) {
        StringBuilder sb = new StringBuilder();
        List<Column> cols = sheet.columns();
        for (int c = 0; c < cols.size(); c++) sb.append(c == 0 ? "" : ",").append(quote(cols.get(c).header()));
        sb.append("\r\n");
        for (Map<String, String> row : sheet.rows()) {
            for (int c = 0; c < cols.size(); c++) sb.append(c == 0 ? "" : ",").append(quote(safe(row.getOrDefault(cols.get(c).id(), ""))));
            sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String quote(String v) {
        return v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }
}
