package com.broker.policy.querylog;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class QueryLogTest {
    @Test
    void aRequestKeepsItsHandlerItsQueriesAndFlagsRepeats() {
        var log = new QueryLog();
        log.begin("cid-1234", "GET", "/api/v1/x", "XController#list", new QueryLog.Who("u-1", "BROKER_ADMIN", 7L, null, null));
        log.query("select * from a where id = 1", 2.0, true, null);
        for (int i = 0; i < 5; i++) log.query("select * from b where a_id = ?", 1.5, true, null);
        log.end(200, null);
        var r = log.recent(10).get(0);
        assertEquals("XController#list", r.handler());
        assertEquals("u-1", r.user());
        assertEquals(7L, r.brokerId());
        assertEquals(6, r.queryCount());
        assertEquals(9.5, r.dbMs());
        assertEquals(1, r.repeats().size());
        assertEquals(5, r.repeats().get(0).times());
    }

    @Test
    void inlineLiteralsAreMaskedSoNoPersonalDataIsKept() {
        assertEquals("select * from m where pan = ? and name = ?", QueryLog.clean("select *   from m where pan = 'ABCDE1234F'\n and name = 'O''Neil'"));
    }

    @Test
    void aQueryWithNoRequestIsRecordedAsBackgroundWithItsId() {
        var log = new QueryLog();
        log.query("select 1", 0.5, true, "sync-42");
        var r = log.recent(10).get(0);
        assertEquals("background", r.kind());
        assertEquals("sync-42", r.correlationId());
    }

    @Test
    void theBufferIsBounded() {
        var log = new QueryLog();
        for (int i = 0; i < 600; i++) { log.begin("c", "GET", "/", "H#h", null); log.end(200, null); }
        assertEquals(QueryLog.MAX_REQUESTS, log.recent(1000).size());
    }

    @Test
    void readersCanAskForEverythingAfterASequenceNumber() {
        var log = new QueryLog();
        for (int i = 0; i < 5; i++) { log.begin("c" + i, "GET", "/" + i, "H#h", null); log.end(200, null); }
        long mid = log.recent(10).get(2).seq();
        var later = log.after(mid, 100);
        assertEquals(2, later.size());
        assertTrue(later.get(0).seq() < later.get(1).seq());
        assertEquals(log.latest(), later.get(1).seq());
    }
}
