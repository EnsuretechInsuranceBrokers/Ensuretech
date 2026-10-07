package com.broker.sheetgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.broker.sheetgen.counter.CodeCounter;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
class ApiTest {
    /** Stands in for policy-service: policy 7 is fine, 8 has spec gaps, 9 belongs to someone else (404). Records the Authorization it was called with. */
    static final AtomicReference<String> SEEN_AUTH = new AtomicReference<>();
    static final HttpServer POLICY = startPolicy();

    static HttpServer startPolicy() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/api/v1/broker/policies/", ex -> {
                SEEN_AUTH.set(ex.getRequestHeaders().getFirst("Authorization"));
                String path = ex.getRequestURI().getPath();
                String body = null;
                int status = 200;
                if (path.startsWith("/api/v1/broker/policies/7/")) body = Specs.JSON.writeValueAsString(Specs.JSON.readTree(grade7()));
                else if (path.startsWith("/api/v1/broker/policies/8/")) body = gapped();
                else { status = 404; body = "{}"; }
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(status, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            });
            s.start();
            return s;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String grade7() {
        return Specs.spec("2026-04-01", "2027-03-31",
                "\"premiumType\":\"GRADE\",\"raterClass\":\"GradeRater\",\"dimensions\":[\"GRADE\"],\"grades\":[\"A\",\"B\"],\"designations\":[],\"sumInsured\":{\"mode\":\"ONE_OF\",\"values\":[300000,500000]}",
                Specs.family(Specs.ALL_RELATIONS, 6, 2, 2, true, false, 0, 80), "[]", "{\"needsGrade\":true,\"needsDesignation\":false,\"needsFlexPlan\":false}", "[]");
    }

    static String gapped() {
        return Specs.spec("2026-04-01", "2027-03-31", "\"premiumType\":\"GRADE\",\"raterClass\":\"GradeRater\",\"dimensions\":[\"GRADE\"],\"sumInsured\":{\"mode\":\"FREE\",\"values\":[]}",
                "null", "[]", "{\"needsGrade\":true,\"needsDesignation\":false,\"needsFlexPlan\":false}", "[\"NO_ACTIVE_RATE_CARD: grades are unknown\"]");
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.jwt.public-key-location", () -> "file:" + TestTokens.PUBLIC_PEM);
        r.add("app.jwt.issuer", () -> TestTokens.ISSUER);
        r.add("app.policy-service.base-url", () -> "http://127.0.0.1:" + POLICY.getAddress().getPort());
    }

    @AfterAll
    static void stop() { POLICY.stop(0); }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired CodeCounter counter;

    @BeforeEach
    void reset() { jdbc.sql("TRUNCATE sheetgen.code_counters").update(); SEEN_AUTH.set(null); }

    static String broker(long id) { return "Bearer " + TestTokens.token("BROKER_ADMIN", id, null); }

    String send(String url, String auth, String body, int status) throws Exception {
        MvcResult r = mvc.perform(post(url).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(status)).andReturn();
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void authenticationAndRolesAreEnforced() throws Exception {
        mvc.perform(post("/api/v1/broker/sheets/generate").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        String body = "{\"policyId\":7}";
        send("/api/v1/broker/sheets/generate", "Bearer " + TestTokens.token("EMPLOYER_ADMIN", 1L, 10L), body, 403);
        send("/api/v1/broker/sheets/generate", "Bearer " + TestTokens.token("SYSTEM", null, null), body, 403);
        mvc.perform(get("/api/v1/employer/sheets/spec?policyId=7").header("Authorization", "Bearer " + TestTokens.token("EMPLOYER_ADMIN", 1L, 10L))).andExpect(status().isNotFound());
    }

    @Test
    void generatesFromThePolicyAndPassesTheCallersTokenToPolicyService() throws Exception {
        String auth = broker(1);
        JsonNode out = Specs.JSON.readTree(send("/api/v1/broker/sheets/generate", auth, "{\"policyId\":7,\"options\":{\"families\":25},\"seed\":5}", 200));
        assertThat(SEEN_AUTH.get()).isEqualTo(auth);
        assertThat(out.path("operation").asString()).isEqualTo("ADD");
        assertThat(out.path("issues")).isEmpty();                       // the generated sheet passes the validator
        assertThat(out.path("codes").path("first").asLong()).isEqualTo(1);
        assertThat(out.path("columns").toString()).contains("Employee Grade");
        assertThat(out.path("rows").size()).isGreaterThanOrEqualTo(25);
        assertThat(out.path("rows").get(0).path("Employee Code").asString()).isEqualTo("GEN000001");
    }

    @Test
    void codesNeverRepeatAcrossRunsAndAreIndependentPerBroker() throws Exception {
        String a = broker(1), b = broker(2);
        String req = "{\"policyId\":7,\"options\":{\"families\":10}}";
        JsonNode r1 = Specs.JSON.readTree(send("/api/v1/broker/sheets/generate", a, req, 200));
        JsonNode r2 = Specs.JSON.readTree(send("/api/v1/broker/sheets/generate", a, req, 200));
        JsonNode r3 = Specs.JSON.readTree(send("/api/v1/broker/sheets/generate", b, req, 200));
        assertThat(r1.path("codes").path("first").asLong()).isEqualTo(1);
        assertThat(r2.path("codes").path("first").asLong()).isEqualTo(11);
        assertThat(r3.path("codes").path("first").asLong()).isEqualTo(1);
        Set<String> c1 = new HashSet<>(), c2 = new HashSet<>();
        r1.path("rows").forEach(n -> c1.add(n.path("Employee Code").asString()));
        r2.path("rows").forEach(n -> c2.add(n.path("Employee Code").asString()));
        assertThat(c1).doesNotContainAnyElementsOf(c2);
    }

    @Test
    void concurrentRunsGetDisjointCodeBlocks() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Long>> f = new ArrayList<>();
        for (int i = 0; i < 40; i++) f.add(pool.submit(() -> counter.reserve(3, "GEN", 5)));
        Set<Long> starts = new TreeSet<>();
        for (Future<Long> x : f) starts.add(x.get());
        pool.shutdown();
        assertThat(starts).hasSize(40);
        assertThat(starts.stream().mapToLong(Long::longValue).max().getAsLong()).isEqualTo(1 + 39 * 5);
        long prev = -5;
        for (long s : starts) { assertThat(s - prev).isGreaterThanOrEqualTo(5); prev = s; }
    }

    @Test
    void aSpecWithGapsIs422AndSpendsNoCodes() throws Exception {
        String out = send("/api/v1/broker/sheets/generate", broker(1), "{\"policyId\":8}", 422);
        assertThat(out).contains("NO_ACTIVE_RATE_CARD");
        assertThat(jdbc.sql("SELECT count(*) FROM sheetgen.code_counters").query(Long.class).single()).isZero();
    }

    @Test
    void anotherBrokersPolicyIs404AndBadRequestsAre400() throws Exception {
        send("/api/v1/broker/sheets/generate", broker(1), "{\"policyId\":9}", 404);
        send("/api/v1/broker/sheets/generate", broker(1), "{}", 400);
        send("/api/v1/broker/sheets/generate", broker(1), "{\"policyId\":7,\"spec\":{}}", 400);
        send("/api/v1/broker/sheets/generate", broker(1), "{\"policyId\":7,\"options\":{\"families\":999999}}", 400);
        send("/api/v1/broker/sheets/generate", broker(1), "{\"policyId\":7,\"options\":{\"spousePct\":150}}", 400);
        send("/api/v1/broker/sheets/generate", broker(1), "{\"policyId\":7,\"codePrefix\":\"bad prefix!\"}", 400);
        send("/api/v1/broker/sheets/generate", broker(1), "{\"policyId\":7,\"operation\":\"REMOVE\",\"count\":3}", 400);
        send("/api/v1/broker/sheets/generate", broker(1), "{\"policyId\":7,\"options\":{\"effectiveDate\":\"2030-01-01\"}}", 400);
    }

    @Test
    void addThenUpdateAndRemoveFromThePreviousRows() throws Exception {
        String auth = broker(1);
        JsonNode add = Specs.JSON.readTree(send("/api/v1/broker/sheets/generate", auth, "{\"policyId\":7,\"options\":{\"families\":30},\"seed\":2}", 200));
        String rows = add.path("rows").toString();
        for (String op : List.of("REMOVE", "UPDATE")) {
            JsonNode d = Specs.JSON.readTree(send("/api/v1/broker/sheets/generate", auth, "{\"policyId\":7,\"operation\":\"" + op + "\",\"count\":10,\"source\":" + rows + "}", 200));
            assertThat(d.path("operation").asString()).isEqualTo(op);
            assertThat(d.path("rows").size()).isGreaterThan(0);
            assertThat(d.path("codes").isNull() || d.path("codes").isMissingNode()).isTrue();
            String chk = send("/api/v1/broker/sheets/validate", auth, "{\"policyId\":7,\"operation\":\"" + op + "\",\"rows\":" + d.path("rows") + "}", 200);
            assertThat(Specs.JSON.readTree(chk).path("valid").asBoolean()).as(chk).isTrue();
        }
    }

    @Test
    void validateReportsProblemsInAnUploadedSheet() throws Exception {
        String rows = "[{\"Employee Code\":\"E1\",\"Relationship with Employee\":\"SELF\",\"Employee First Name\":\"A\",\"Employee DOB\":\"01-01-2015\",\"Effective Date\":\"01-01-2020\",\"Sum Insured\":\"1\"}]";
        JsonNode r = Specs.JSON.readTree(send("/api/v1/broker/sheets/validate", broker(1), "{\"policyId\":7,\"rows\":" + rows + "}", 200));
        assertThat(r.path("valid").asBoolean()).isFalse();
        assertThat(r.path("issues").toString()).contains("ADULT_UNDER_18", "EFFECTIVE_DATE_OUTSIDE_POLICY", "SUM_INSURED_NOT_OFFERED", "GRADE_MISSING");
    }

    @Test
    void downloadsXlsxAndCsv() throws Exception {
        String auth = broker(1);
        MvcResult x = mvc.perform(post("/api/v1/broker/sheets/generate/file?format=xlsx").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"policyId\":7,\"options\":{\"families\":5}}")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("add-POL-7-"))).andReturn();
        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(x.getResponse().getContentAsByteArray()))) {
            assertThat(wb.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("Employee Code");
            assertThat(wb.getSheetAt(0).getLastRowNum()).isGreaterThanOrEqualTo(5);
        }
        String csv = mvc.perform(post("/api/v1/broker/sheets/generate/file?format=csv").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"policyId\":7,\"options\":{\"families\":5}}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("Employee Code,Employee First Name");
        send("/api/v1/broker/sheets/generate/file?format=pdf", auth, "{\"policyId\":7}", 400);
    }

    @Test
    void theStandalonePageIsServedWithoutLoginButNothingElseIs() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk());              // the welcome page forwards to index.html
        mvc.perform(get("/index.html")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("<script id=\"engine\"")));
        mvc.perform(post("/")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }
}
