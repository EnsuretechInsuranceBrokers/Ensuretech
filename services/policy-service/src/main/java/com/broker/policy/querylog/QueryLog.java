package com.broker.policy.querylog;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * A development X-ray: for each request the service handled, who made it (user, role, tenant), which controller method ran and every SQL statement it caused, with timings, plus any
 * statement repeated enough times to look like an N+1. Off unless ops.querylog.enabled=true (QUERYLOG_ENABLED), because it keeps SQL text in memory. It never records bind values, and string
 * literals written inline in a statement are masked, so no personal data lands here. Read through GET /api/v1/ops/queries (SYSTEM tokens only) and the console's Queries page.
 */
public final class QueryLog {
    public static final QueryLog INSTANCE = new QueryLog();
    static final int MAX_REQUESTS = 500, MAX_QUERIES_PER_REQUEST = 200, REPEAT_AT = 3;
    private static final Pattern STRING = Pattern.compile("'(?:[^']|'')*'"), SPACE = Pattern.compile("\\s+");

    public record Query(String sql, double ms, boolean ok) {}
    public record Repeat(String sql, int times, double totalMs) {}
    /** seq grows by one per finished request, so a reader can ask for "everything after seq N". */
    public record Req(long seq, String at, String user, String role, Long brokerId, Long employerId, String breakGlass, String error, String correlationId, String kind, String method, String uri,
                      String handler, int status, double ms, int queryCount, double dbMs, List<Query> queries, List<Repeat> repeats) {}
    /** Who a request is from, taken from the already verified token. */
    public record Who(String user, String role, Long brokerId, Long employerId, String breakGlass) {}

    private static final class Open {
        final Instant at = Instant.now();
        final long start = System.nanoTime();
        final String cid, method, uri, handler;
        final Who who;
        final List<Query> queries = new ArrayList<>();
        final Map<String, double[]> perSql = new LinkedHashMap<>();   // sql -> {count, totalMs}
        int count;
        double dbMs;
        Open(String cid, String method, String uri, String handler, Who who) { this.cid = cid; this.method = method; this.uri = uri; this.handler = handler; this.who = who; }
        void add(Query q) {
            count++; dbMs += q.ms();
            if (queries.size() < MAX_QUERIES_PER_REQUEST) queries.add(q);
            double[] a = perSql.computeIfAbsent(q.sql(), k -> new double[2]);
            a[0]++; a[1] += q.ms();
        }
        Req finish(long seq, String kind, int status, String error) {
            List<Repeat> repeats = new ArrayList<>();
            perSql.forEach((sql, a) -> { if (a[0] >= REPEAT_AT) repeats.add(new Repeat(sql, (int) a[0], round(a[1]))); });
            repeats.sort((x, y) -> Integer.compare(y.times(), x.times()));
            Who w = who == null ? new Who(null, null, null, null, null) : who;
            return new Req(seq, at.toString(), w.user(), w.role(), w.brokerId(), w.employerId(), w.breakGlass(), error, cid, kind, method, uri, handler, status, round((System.nanoTime() - start) / 1e6),
                    count, round(dbMs), List.copyOf(queries), repeats);
        }
    }

    private final ThreadLocal<Open> current = new ThreadLocal<>();
    private final ArrayDeque<Req> done = new ArrayDeque<>();
    private final AtomicLong seq = new AtomicLong();

    private static double round(double v) { return Math.round(v * 100) / 100.0; }

    static String clean(String sql) {
        String s = SPACE.matcher(STRING.matcher(sql == null ? "" : sql).replaceAll("?")).replaceAll(" ").trim();
        return s.length() > 1200 ? s.substring(0, 1200) + " …" : s;
    }

    public void begin(String correlationId, String method, String uri, String handler, Who who) { current.set(new Open(correlationId, method, uri, handler, who)); }

    public void query(String sql, double ms, boolean ok, String correlationIdIfNoRequest) {
        Query q = new Query(clean(sql), round(ms), ok);
        Open o = current.get();
        if (o != null) { o.add(q); return; }
        Open lone = new Open(correlationIdIfNoRequest, null, null, "(background: no request on this thread)", null);
        lone.add(q);
        add(lone, "background", 0, null);
    }

    public void end(int status, String error) {
        Open o = current.get();
        current.remove();
        if (o != null) add(o, "request", status, error);
    }

    private synchronized void add(Open o, String kind, int status, String error) {
        done.addFirst(o.finish(seq.incrementAndGet(), kind, status, error));
        while (done.size() > MAX_REQUESTS) done.removeLast();
    }

    /** Newest first. */
    public synchronized List<Req> recent(int limit) { return done.stream().limit(Math.max(1, Math.min(limit, MAX_REQUESTS))).toList(); }

    /** Everything that finished after `afterSeq`, oldest first, at most `limit` of them. */
    public synchronized List<Req> after(long afterSeq, int limit) {
        List<Req> out = new ArrayList<>();
        var it = done.descendingIterator();
        while (it.hasNext() && out.size() < Math.max(1, Math.min(limit, MAX_REQUESTS))) { Req r = it.next(); if (r.seq() > afterSeq) out.add(r); }
        return out;
    }

    public long latest() { return seq.get(); }

    public synchronized void clear() { done.clear(); }
}
