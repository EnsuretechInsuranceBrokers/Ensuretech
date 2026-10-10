package com.broker.policy.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Typed, defensive view over the rate_table JSON (numbers may arrive as Integer, Double, BigDecimal or String). */
public final class RateTable {
    private final Map<String, Object> raw;

    public RateTable(Map<String, Object> raw) {
        this.raw = raw == null ? Map.of() : raw;
    }

    public Map<String, Object> raw() { return raw; }

    public boolean has(String key) { return raw.get(key) != null; }

    /** @return the decimal at key, or null when absent. Throws NumberFormatException when malformed. */
    public BigDecimal decimal(String key) {
        Object v = raw.get(key);
        return v == null ? null : toDecimal(v);
    }

    public static BigDecimal toDecimal(Object v) {
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number || v instanceof String) return new BigDecimal(String.valueOf(v));
        throw new NumberFormatException("not a number: " + v);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String key) {
        return raw.get(key) instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> list(String key) {
        if (!(raw.get(key) instanceof List<?> l)) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> m)) return null;
            out.add((Map<String, Object>) m);
        }
        return out;
    }
}
