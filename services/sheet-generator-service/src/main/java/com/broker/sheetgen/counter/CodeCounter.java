package com.broker.sheetgen.counter;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hands out blocks of sequence numbers that are never reused. One atomic upsert per block, so concurrent generators (two testers,
 * two instances) get disjoint ranges.
 */
@Component
public class CodeCounter {
    private final JdbcClient jdbc;

    CodeCounter(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Reserves {@code count} numbers for (broker, prefix) and returns the first one (>= 1). */
    @Transactional
    public long reserve(long brokerId, String prefix, int count) {
        if (count < 1) throw new IllegalArgumentException("count must be positive");
        Long next = jdbc.sql("""
                INSERT INTO sheetgen.code_counters (broker_id, prefix, next_value) VALUES (:b, :p, :n + 1)
                ON CONFLICT (broker_id, prefix) DO UPDATE SET next_value = sheetgen.code_counters.next_value + :n, updated_at = now()
                RETURNING next_value""")
                .param("b", brokerId).param("p", prefix).param("n", (long) count).query(Long.class).single();
        return next - count;
    }
}
