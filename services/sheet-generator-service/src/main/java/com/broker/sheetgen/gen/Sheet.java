package com.broker.sheetgen.gen;

import java.util.List;
import java.util.Map;

/** A generated or derived sheet: ordered columns, rows keyed by column id, and anything the generator had to leave out and why. */
public record Sheet(String operation, List<Column> columns, List<Map<String, String>> rows, List<String> warnings) {

    /** Rows keyed by header text, the shape the API returns and accepts back. */
    public List<Map<String, String>> byHeader() {
        return rows.stream().map(r -> {
            Map<String, String> m = new java.util.LinkedHashMap<>();
            for (Column c : columns) m.put(c.header(), r.getOrDefault(c.id(), ""));
            return m;
        }).toList();
    }
}
