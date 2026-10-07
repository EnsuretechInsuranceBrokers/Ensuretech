package com.broker.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Three tiny HTTP servers standing in for a TPA, the endorsement engine and identity-service. Real sockets, real HTTP. */
final class FakeWorld {
    static final FakeWorld I = new FakeWorld();
    static final String TOKEN = "svc-token-1";

    record Call(String path, Map<String, String> headers, String body) {}

    final HttpServer tpa, engine, identity;
    final String tpaUrl, engineUrl, identityUrl;

    // TPA behaviour
    final List<Call> tpaCalls = Collections.synchronizedList(new ArrayList<>());
    volatile int tpaStatus = 200;                       // forced status for every call (200 = behave)
    volatile int failFromCall = Integer.MAX_VALUE;      // calls numbered >= this answer 503
    final Set<Long> rejectRefs = Collections.synchronizedSet(new HashSet<>());
    // engine behaviour
    final Map<UUID, List<Long>> rosters = new ConcurrentHashMap<>();
    final Map<Long, String> tpaIdsRecorded = new ConcurrentHashMap<>();
    volatile int engineStatus = 200;
    final AtomicInteger engineMemberCalls = new AtomicInteger();
    // identity
    final AtomicInteger tokenCalls = new AtomicInteger();
    volatile int identityStatus = 200;

    private FakeWorld() {
        try {
            tpa = start(this::tpaHandle);
            engine = start(this::engineHandle);
            identity = start(this::identityHandle);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        tpaUrl = "http://127.0.0.1:" + tpa.getAddress().getPort();
        engineUrl = "http://127.0.0.1:" + engine.getAddress().getPort();
        identityUrl = "http://127.0.0.1:" + identity.getAddress().getPort() + "/token";
    }

    void reset() {
        tpaCalls.clear(); tpaStatus = 200; failFromCall = Integer.MAX_VALUE; rejectRefs.clear();
        rosters.clear(); tpaIdsRecorded.clear(); engineStatus = 200; engineMemberCalls.set(0);
        tokenCalls.set(0); identityStatus = 200;
    }

    List<Long> roster(UUID job, int n, long firstId) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) ids.add(firstId + i);
        rosters.put(job, ids);
        return ids;
    }

    private static HttpServer start(com.sun.net.httpserver.HttpHandler h) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", h);
        s.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        s.start();
        return s;
    }

    private static String read(HttpExchange x) throws IOException { return new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); }

    private static void reply(HttpExchange x, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) x.getResponseBody().write(b);
        x.close();
    }

    private void tpaHandle(HttpExchange x) throws IOException {
        String body = read(x);
        Map<String, String> h = new ConcurrentHashMap<>();
        x.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.getFirst()));
        tpaCalls.add(new Call(x.getRequestURI().getPath(), h, body));
        int n = tpaCalls.size();
        if (tpaStatus != 200) { reply(x, tpaStatus, "{}"); return; }
        if (n >= failFromCall) { reply(x, 503, "{}"); return; }
        Matcher m = Pattern.compile("\"ref\":(\\d+)").matcher(body);
        StringBuilder sb = new StringBuilder("{\"results\":[");
        boolean first = true;
        while (m.find()) {
            long ref = Long.parseLong(m.group(1));
            if (!first) sb.append(',');
            first = false;
            sb.append(rejectRefs.contains(ref) ? "{\"ref\":" + ref + ",\"status\":\"REJECTED\",\"reason\":\"invalid DOB\"}"
                    : "{\"ref\":" + ref + ",\"status\":\"ACCEPTED\",\"tpa_member_id\":\"T-" + ref + "\"}");
        }
        reply(x, 200, sb.append("]}").toString());
    }

    private void engineHandle(HttpExchange x) throws IOException {
        if (!("Bearer " + TOKEN).equals(x.getRequestHeaders().getFirst("Authorization"))) { reply(x, 401, "{}"); return; }
        if (engineStatus != 200) { reply(x, engineStatus, "{}"); return; }
        String path = x.getRequestURI().getPath();
        Matcher m = Pattern.compile("/api/v1/internal/endorsements/([0-9a-f-]+)/(members|tpa-member-ids)/").matcher(path);
        if (!m.matches()) { reply(x, 404, "{}"); return; }
        UUID job = UUID.fromString(m.group(1));
        if (m.group(2).equals("tpa-member-ids")) {
            Matcher ids = Pattern.compile("\"id\":(\\d+),\"tpa_member_id\":\"([^\"]+)\"").matcher(read(x));
            while (ids.find()) tpaIdsRecorded.put(Long.parseLong(ids.group(1)), ids.group(2));
            reply(x, 200, "{\"updated\":1,\"ignored\":0}");
            return;
        }
        engineMemberCalls.incrementAndGet();
        List<Long> all = rosters.get(job);
        if (all == null) { reply(x, 404, "{}"); return; }
        Map<String, String> q = new ConcurrentHashMap<>();
        for (String kv : x.getRequestURI().getQuery().split("&")) q.put(kv.split("=")[0], kv.split("=")[1]);
        long after = Long.parseLong(q.get("after"));
        int limit = Integer.parseInt(q.get("limit"));
        List<Long> page = all.stream().filter(id -> id > after).limit(limit).toList();
        StringBuilder sb = new StringBuilder("{\"job_id\":\"" + job + "\",\"op_type\":\"ADD\",\"members\":[");
        for (int i = 0; i < page.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(page.get(i)).append(",\"employee_code\":\"E").append(page.get(i))
              .append("\",\"relation_code\":\"SELF\",\"first_name\":\"N\",\"last_name\":\"\",\"date_of_birth\":\"1990-01-01\",")
              .append("\"gender\":null,\"email\":null,\"mobile\":null,\"sum_insured\":\"500000.00\",\"effective_date\":\"2030-04-01\",")
              .append("\"end_date\":null,\"is_active\":true,\"tpa_member_id\":null}");
        }
        boolean more = page.size() == limit && page.getLast() < all.getLast();
        sb.append("],\"next_after\":").append(more ? page.getLast() : "null").append('}');
        reply(x, 200, sb.toString());
    }

    private void identityHandle(HttpExchange x) throws IOException {
        String body = read(x);
        tokenCalls.incrementAndGet();
        if (identityStatus != 200 || !body.contains("client_id=svc_test") || !body.contains("client_secret=s3cret")) { reply(x, 401, "{\"error\":\"invalid_client\"}"); return; }
        reply(x, 200, "{\"access_token\":\"" + TOKEN + "\",\"token_type\":\"Bearer\",\"expires_in\":600}");
    }
}
