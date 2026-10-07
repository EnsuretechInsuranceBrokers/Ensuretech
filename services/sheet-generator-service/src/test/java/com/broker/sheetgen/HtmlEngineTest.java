package com.broker.sheetgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.broker.sheetgen.gen.*;
import com.broker.sheetgen.spec.SheetSpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * Drift guard for the standalone page: its engine (the {@code <script id="engine">} block) is run under Node and its output is judged by the
 * service's own Java validator, and its validator is compared with the Java one on deliberately broken sheets. If the two implementations of a
 * rule ever disagree, this fails.
 */
class HtmlEngineTest {
    static final java.time.LocalDate TODAY = GeneratorTest.TODAY;
    static Path dir;

    private static final String DRIVER = """
            const fs = require('fs'), vm = require('vm');
            const ctx = { TextEncoder, console };
            vm.createContext(ctx);
            vm.runInContext(fs.readFileSync(process.argv[2] + '/engine.js', 'utf8'), ctx);
            const G = ctx.EnsureSheetGen;
            const req = JSON.parse(fs.readFileSync(0, 'utf8'));
            const spec = G.parseSpec(req.spec);
            let out;
            if (req.mode === 'validate') out = { issues: G.validate(spec, req.op, req.rows, req.today) };
            else {
              let sheet;
              if (req.op === 'ADD' && req.stream) {
                const st = G.createAddStream(spec, req.options, req.ids, req.today); const rows = []; let f;
                while ((f = st.next())) for (const r of f) rows.push(r);
                sheet = { columns: st.columns, rows, warnings: st.warnings() };
              } else if (req.op === 'ADD') sheet = G.generateAdd(spec, req.options, req.ids, req.today);
              else if (req.op === 'REMOVE') sheet = G.deriveRemove(spec, req.source, req.count, req.removeMode, req.seed, req.today);
              else sheet = G.deriveUpdate(spec, req.source, req.count, req.seed, req.today);
              out = { rows: G.byHeader(sheet), warnings: sheet.warnings, issues: G.validate(spec, req.op, G.byHeader(sheet), req.today),
                      csvOk: G.toCsv(sheet).split('\\r\\n').length === sheet.rows.length + 2, xlsxBytes: G.toXlsx(sheet).length };
            }
            process.stdout.write(JSON.stringify(out));
            """;

    @BeforeAll
    static void extract(@TempDir Path tmp) throws Exception {
        dir = Files.createTempDirectory("engine");
        String html = new String(HtmlEngineTest.class.getResourceAsStream("/static/index.html").readAllBytes(), StandardCharsets.UTF_8);
        int a = html.indexOf("<script id=\"engine\">"), b = html.indexOf("</script>", a);
        assertThat(a).isPositive();
        Files.writeString(dir.resolve("engine.js"), html.substring(a + "<script id=\"engine\">".length(), b));
        Files.writeString(dir.resolve("driver.js"), DRIVER);
        assumeTrue(node("--version") != null, "node is not installed; the standalone page's engine cannot be exercised");
    }

    static String node(String... args) {
        try {
            List<String> cmd = new ArrayList<>(List.of("node"));
            cmd.addAll(List.of(args));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0 ? out : null;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    static JsonNode run(Map<String, Object> req) throws Exception {
        Process p = new ProcessBuilder("node", dir.resolve("driver.js").toString(), dir.toString()).redirectErrorStream(false).start();
        p.getOutputStream().write(Specs.JSON.writeValueAsBytes(req));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8), err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(err).isZero();
        return Specs.JSON.readTree(out);
    }

    static JsonNode specJson(String name) {
        return Specs.JSON.readTree(switch (name) {
            case "flat" -> Specs.spec("2026-04-01", "2027-03-31", "\"premiumType\":\"FLAT\",\"raterClass\":\"FlatRater\",\"dimensions\":[],\"sumInsured\":{\"mode\":\"FREE\",\"values\":[]}",
                    Specs.family(Specs.ALL_RELATIONS, 8, 3, 2, true, true, null, null), "[]", Specs.NO_NEEDS, "[]");
            case "grade" -> Specs.spec("2026-04-01", "2027-03-31",
                    "\"premiumType\":\"GRADE\",\"raterClass\":\"GradeRater\",\"dimensions\":[\"GRADE\",\"DESIGNATION\"],\"grades\":[\"A\",\"B\",\"C\"],\"designations\":[\"Manager\",\"Executive\"],\"sumInsured\":{\"mode\":\"ONE_OF\",\"values\":[300000,500000,1000000.0]}",
                    Specs.family(Specs.ALL_RELATIONS, 6, 2, 2, true, false, 0, 80), "[]", "{\"needsGrade\":true,\"needsDesignation\":true,\"needsFlexPlan\":false}", "[]");
            case "norule" -> Specs.spec("2026-04-01", "2027-03-31", "\"premiumType\":\"NO_RULE\",\"raterClass\":\"FlatRater\",\"dimensions\":[],\"sumInsured\":{\"mode\":\"FREE\",\"values\":[]}",
                    Specs.family(Specs.ALL_RELATIONS, 6, 2, 2, true, false, null, null), "[]", Specs.NO_NEEDS, "[]");
            case "salary" -> Specs.spec("2026-04-01", "2027-03-31", "\"premiumType\":\"FLAT\",\"suminsuredSubtype\":\"SALARY_MULTIPLE\",\"raterClass\":\"FlatRater\",\"dimensions\":[],\"sumInsured\":{\"mode\":\"FREE\",\"values\":[]}",
                    Specs.family(Specs.ALL_RELATIONS, 6, 2, 2, false, false, null, null), "[]", Specs.NO_NEEDS, "[]");
            case "past" -> Specs.spec("2024-04-01", "2025-03-31", "\"premiumType\":\"FLAT\",\"raterClass\":\"FlatRater\",\"dimensions\":[],\"sumInsured\":{\"mode\":\"FREE\",\"values\":[]}",
                    Specs.family(Specs.ALL_RELATIONS, 6, 2, 2, true, false, null, null), "[]", Specs.NO_NEEDS, "[]");
            default -> throw new IllegalArgumentException(name);
        });
    }

    static final Map<String, Object> IDS = Map.of("prefix", "JS", "first", 1, "brokerId", 5, "tag", "ab12cd");

    static Map<String, Object> addRequest(JsonNode spec, int families, long seed) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("families", families); o.put("spousePct", 90); o.put("childPct", 80); o.put("twinPct", 30); o.put("parentPct", 50); o.put("midTermPct", 40);
        o.put("fillOptional", true); o.put("includePremiums", false); o.put("seed", seed);
        return new LinkedHashMap<>(Map.of("spec", spec, "op", "ADD", "options", o, "ids", IDS, "today", "2026-10-07"));
    }

    static List<Map<String, String>> rows(JsonNode n) {
        List<Map<String, String>> out = new ArrayList<>();
        n.forEach(r -> { Map<String, String> m = new LinkedHashMap<>(); r.propertyNames().forEach(k -> m.put(k, r.path(k).asString(""))); out.add(m); });
        return out;
    }

    @Test
    void theBrowserEngineProducesSheetsTheJavaValidatorAccepts() throws Exception {
        for (String name : List.of("flat", "grade", "norule", "salary", "past")) {
            JsonNode spec = specJson(name);
            SheetSpec parsed = SheetSpec.parse(spec);
            for (long seed = 1; seed <= 3; seed++) {
                JsonNode out = run(addRequest(spec, 400, seed));
                List<Map<String, String>> rows = rows(out.path("rows"));
                assertThat(rows).as(name).isNotEmpty();
                List<Issue> java = SheetValidator.validate(parsed, "ADD", rows, TODAY);
                assertThat(java).as(name + " seed " + seed + ": %s", java.stream().limit(5).toList()).isEmpty();
                assertThat(out.path("issues")).as(name + " (its own validator)").isEmpty();
                assertThat(out.path("csvOk").asBoolean()).isTrue();
                assertThat(out.path("xlsxBytes").asInt()).isGreaterThan(500);
            }
        }
    }

    @Test
    void streamingAndOneShotGenerationProduceTheSameSheet() throws Exception {
        JsonNode spec = specJson("grade");
        Map<String, Object> a = addRequest(spec, 150, 9), b = addRequest(spec, 150, 9);
        b.put("stream", true);
        assertThat(run(b).path("rows")).isEqualTo(run(a).path("rows"));
    }

    @Test
    void theBrowserEngineDerivesRemoveAndUpdateSheetsTheJavaValidatorAccepts() throws Exception {
        JsonNode spec = specJson("grade");
        SheetSpec parsed = SheetSpec.parse(spec);
        JsonNode add = run(addRequest(spec, 200, 5));
        for (String op : List.of("REMOVE", "UPDATE")) {
            Map<String, Object> req = new LinkedHashMap<>(Map.of("spec", spec, "op", op, "source", add.path("rows"), "count", 60, "seed", 3, "removeMode", "MIX", "today", "2026-10-07"));
            JsonNode out = run(req);
            List<Map<String, String>> rows = rows(out.path("rows"));
            assertThat(rows).as(op).isNotEmpty();
            assertThat(SheetValidator.validate(parsed, op, rows, TODAY)).as(op).isEmpty();
            assertThat(out.path("issues")).as(op + " (its own validator)").isEmpty();
        }
    }

    @Test
    void theBrowserEngineRefusesASpecWithGaps() throws Exception {
        JsonNode spec = Specs.JSON.readTree(ApiTest.gapped());
        Map<String, Object> req = addRequest(spec, 5, 1);
        Process p = new ProcessBuilder("node", dir.resolve("driver.js").toString(), dir.toString()).start();
        p.getOutputStream().write(Specs.JSON.writeValueAsBytes(req));
        p.getOutputStream().close();
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).isNotZero();
        assertThat(err).contains("cannot produce a valid sheet");
    }

    /** Both validators on the same broken sheets: they must flag the same (row, code) pairs. */
    @Test
    void theTwoValidatorsAgreeOnBrokenSheets() throws Exception {
        for (String name : List.of("flat", "grade", "norule")) {
            JsonNode specJson = specJson(name);
            SheetSpec spec = SheetSpec.parse(specJson);
            Sheet s = SheetGenerator.add(spec, new Options(250, 90, 80, 30, 50, 40, true, false, null, 17L, null, null, null, null), new SheetGenerator.Ids("GEN", 1, 5), TODAY);
            List<Map<String, String>> rows = new ArrayList<>(s.byHeader());
            Random rnd = new Random(99);
            for (int i = 0; i < 400; i++) mutate(rows, rnd);
            Set<String> java = SheetValidator.validate(spec, "ADD", rows, TODAY).stream().map(i -> i.row() + "|" + i.code()).collect(Collectors.toCollection(TreeSet::new));
            Map<String, Object> req = new LinkedHashMap<>(Map.of("spec", specJson, "mode", "validate", "op", "ADD", "rows", rows, "today", "2026-10-07"));
            Set<String> js = new TreeSet<>();
            run(req).path("issues").forEach(n -> js.add(n.path("row").asInt() + "|" + n.path("code").asString()));
            assertThat(java).as(name + ": the mutations should break many rules").hasSizeGreaterThan(100);
            assertThat(js).as(name).isEqualTo(java);
        }
    }

    private static void mutate(List<Map<String, String>> rows, Random rnd) {
        Map<String, String> r = rows.get(rnd.nextInt(rows.size()));
        boolean self = "SELF".equals(r.get("Relationship with Employee"));
        String dobCol = self ? "Employee DOB" : "Insured Member DOB";
        switch (rnd.nextInt(14)) {
            case 0 -> r.put(dobCol, "01-01-" + (2000 + rnd.nextInt(26)));
            case 1 -> r.put(dobCol, "01-01-" + (1930 + rnd.nextInt(30)));
            case 2 -> r.put(dobCol, "31/02/1990");
            case 3 -> r.put("Effective Date", rnd.nextBoolean() ? "01-01-2020" : "01-01-2030");
            case 4 -> r.put("Sum Insured", rnd.nextBoolean() ? "" : rnd.nextBoolean() ? "-5" : "123456");
            case 5 -> r.put("Relationship with Employee", List.of("UNCLE", "SON", "WIFE", "FATHER", "FATHER_IN_LAW", "SELF").get(rnd.nextInt(6)));
            case 6 -> r.put("Date of Marriage", rnd.nextBoolean() ? "01-01-2030" : "01-06-" + (2000 + rnd.nextInt(25)));
            case 7 -> r.put("Employee Date of Joining", "01-01-" + (1960 + rnd.nextInt(60)));
            case 8 -> rows.add(new LinkedHashMap<>(r));
            case 9 -> r.put("Employee Code", "GEN" + String.format("%06d", 1 + rnd.nextInt(250)));
            case 10 -> r.put("Employee Email", "dup@x.test");
            case 11 -> { r.remove("Employee Grade"); r.put("Employee Grade", rnd.nextBoolean() ? "ZZ" : ""); }
            case 12 -> r.put("Employee Premium", rnd.nextBoolean() ? "" : "-1");
            default -> r.put(self ? "Employee Gender" : "Insured Member Gender", "Other");
        }
    }
}
