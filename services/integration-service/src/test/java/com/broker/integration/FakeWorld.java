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
    volatile int tpaDelayMs = 0;                        // how long the fake TPA takes to answer (to hold a worker thread on a slow call)
    volatile int tpaStatus = 200;                       // forced status for every call (200 = behave)
    volatile int failFromCall = Integer.MAX_VALUE;      // calls numbered >= this answer 503
    final Set<Long> rejectRefs = Collections.synchronizedSet(new HashSet<>());
    // engine behaviour
    final Map<UUID, List<Long>> rosters = new ConcurrentHashMap<>();
    final Map<Long, String> tpaIdsRecorded = new ConcurrentHashMap<>();
    volatile int engineStatus = 200;
    volatile String activeLivesJson = "{\"members\":[]}";          // what the fake TPA answers to GET /active-lives
    final Map<Long, List<String>> policyRoster = new ConcurrentHashMap<>();   // policy -> roster member JSON objects
    final Map<Long, String> policyNumbers = new ConcurrentHashMap<>();
    final List<String> assignCalls = Collections.synchronizedList(new ArrayList<>());      // "source|body" for every policy-level assignment
    final List<String> lifecycle = Collections.synchronizedList(new ArrayList<>());
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
        tpaCalls.clear(); tpaDelayMs = 0; tpaStatus = 200; failFromCall = Integer.MAX_VALUE; rejectRefs.clear();
        rosters.clear(); tpaIdsRecorded.clear(); lifecycle.clear(); policyRoster.clear(); policyNumbers.clear(); assignCalls.clear(); activeLivesJson = "{\"members\":[]}"; engineStatus = 200; engineMemberCalls.set(0);
        tokenCalls.set(0); identityStatus = 200;
    }

    static String member(long id, String code, String rel, String first, String last) {
        return "{\"id\":" + id + ",\"employee_code\":\"" + code + "\",\"relation_code\":\"" + rel + "\",\"first_name\":\"" + first + "\",\"last_name\":\"" + last
                + "\",\"date_of_birth\":\"1990-01-01\",\"gender\":null,\"email\":null,\"mobile\":null,\"sum_insured\":\"500000.00\",\"effective_date\":\"2030-04-01\",\"end_date\":null,\"is_active\":true,\"tpa_member_id\":null}";
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
        tpaCalls.add(new Call(x.getRequestURI().getPath() + (x.getRequestURI().getRawQuery() == null ? "" : "?" + x.getRequestURI().getRawQuery()), h, body));
        if (x.getRequestURI().getPath().endsWith("/active-lives") && tpaStatus == 200) { reply(x, 200, activeLivesJson); return; }
        if (tpaDelayMs > 0) { try { Thread.sleep(tpaDelayMs); } catch (InterruptedException e) { return; } }
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
        try { engineHandle0(x); } catch (RuntimeException e) { e.printStackTrace(); throw e; }
    }

    private void engineHandle0(HttpExchange x) throws IOException {
        if (!("Bearer " + TOKEN).equals(x.getRequestHeaders().getFirst("Authorization"))) { reply(x, 401, "{}"); return; }
        if (engineStatus != 200) { reply(x, engineStatus, "{}"); return; }
        String path = x.getRequestURI().getPath();
        if (path.equals("/api/v1/internal/members/pending-summary/")) { pendingSummary(x); return; }
        Matcher pm = Pattern.compile("/api/v1/internal/policies/(\\d+)/(members|tpa-member-ids)/").matcher(path);
        if (pm.matches()) {
            long policy = Long.parseLong(pm.group(1));
            if (pm.group(2).equals("tpa-member-ids")) { assign(x, policy); return; }
            String q = x.getRequestURI().getQuery() == null ? "" : x.getRequestURI().getQuery();
            Matcher pq = Pattern.compile("pending=(\\w+)").matcher(q);
            String pending = pq.find() ? pq.group(1) : null;
            List<String> members = new ArrayList<>();
            for (String raw : policyRoster.getOrDefault(policy, List.of())) {
                var n = JSON.readTree(raw);
                boolean noId = n.path("tpa_member_id").isNull() || n.path("tpa_member_id").isMissingNode();
                boolean noCard = n.path("ecard_url").isNull() || n.path("ecard_url").isMissingNode();
                if (pending == null || (pending.equals("ids") && noId) || (pending.equals("ecards") && !noId && noCard) || (pending.equals("any") && (noId || noCard))) members.add(raw);
            }
            String pn = policyNumbers.get(policy);
            reply(x, 200, "{\"policy_id\":" + policy + ",\"policy_number\":" + (pn == null ? "null" : "\"" + pn + "\"") + ",\"policy_start\":\"2030-04-01\",\"policy_end\":\"2031-03-31\","
                    + "\"policy_is_inception\":false,\"members\":[" + String.join(",", members) + "],\"next_after\":null}");
            return;
        }
        Matcher m = Pattern.compile("/api/v1/internal/endorsements/([0-9a-f-]+)/(members|tpa-member-ids|lifecycle)/").matcher(path);
        if (!m.matches()) { reply(x, 404, "{}"); return; }
        UUID job = UUID.fromString(m.group(1));
        if (m.group(2).equals("lifecycle")) {
            Matcher st = Pattern.compile("\"state\":\"([A-Z_]+)\"").matcher(read(x));
            if (st.find()) lifecycle.add(st.group(1));
            reply(x, 200, "{}");
            return;
        }
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

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    /** The engine's policy-level assignment: records the call and rewrites the stored roster so a second upload sees what the first one saved. */
    private void assign(HttpExchange x, long policy) throws IOException {
        String body = read(x);
        assignCalls.add(body);
        var req = JSON.readTree(body);
        String source = req.path("source").asString("");
        List<String> roster = policyRoster.get(policy) == null ? null : new ArrayList<>(policyRoster.get(policy));
        int updated = 0, ignored = 0;
        for (var item : req.path("members")) {
            boolean hit = false;
            for (int i = 0; roster != null && i < roster.size(); i++) {
                var n = (tools.jackson.databind.node.ObjectNode) JSON.readTree(roster.get(i));
                if (n.path("id").asLong() != item.path("id").asLong()) continue;
                hit = true;
                if (item.has("tpa_member_id")) { n.put("tpa_member_id", item.path("tpa_member_id").asString()); n.put("tpa_id_source", source); tpaIdsRecorded.put(n.path("id").asLong(), item.path("tpa_member_id").asString()); }
                if (item.has("tpa_member_name")) n.put("tpa_member_name", item.path("tpa_member_name").asString());
                if (item.has("ecard_url")) n.put("ecard_url", item.path("ecard_url").asString());
                roster.set(i, JSON.writeValueAsString(n));
            }
            if (hit) updated++; else ignored++;
        }
        if (roster != null) policyRoster.put(policy, roster);
        reply(x, 200, "{\"updated\":" + updated + ",\"ignored\":" + ignored + "}");
    }

    private void pendingSummary(HttpExchange x) throws IOException {
        Matcher m = Pattern.compile("policy_ids=([0-9,]+)").matcher(x.getRequestURI().getQuery());
        StringBuilder sb = new StringBuilder("{\"policies\":[");
        boolean first = true;
        if (m.find()) for (String id : m.group(1).split(",")) {
            int ids = 0, cards = 0;
            String oldest = null;
            for (String raw : policyRoster.getOrDefault(Long.parseLong(id), List.of())) {
                var n = JSON.readTree(raw);
                boolean noId = n.path("tpa_member_id").isNull() || n.path("tpa_member_id").isMissingNode();
                boolean noCard = n.path("ecard_url").isNull() || n.path("ecard_url").isMissingNode();
                if (noId) { ids++; String at = n.path("endorsed_at").asString(null); if (at != null && (oldest == null || at.compareTo(oldest) < 0)) oldest = at; }
                else if (noCard) cards++;
            }
            if (ids + cards == 0) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"policy_id\":").append(id).append(",\"pending_ids\":").append(ids).append(",\"pending_ecards\":").append(cards)
              .append(",\"oldest_id_waiting_since\":").append(oldest == null ? "null" : "\"" + oldest + "\"").append(",\"oldest_ecard_waiting_since\":null}");
        }
        reply(x, 200, sb.append("]}").toString());
    }

    private void identityHandle(HttpExchange x) throws IOException {
        String body = read(x);
        tokenCalls.incrementAndGet();
        if (identityStatus != 200 || !body.contains("client_id=svc_test") || !body.contains("client_secret=s3cret")) { reply(x, 401, "{\"error\":\"invalid_client\"}"); return; }
        reply(x, 200, "{\"access_token\":\"" + TOKEN + "\",\"token_type\":\"Bearer\",\"expires_in\":600}");
    }
}
