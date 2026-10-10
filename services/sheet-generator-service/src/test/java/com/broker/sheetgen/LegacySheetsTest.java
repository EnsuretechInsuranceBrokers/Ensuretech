package com.broker.sheetgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The browser page's legacy rate-sheet readers ({@code RaterModel.legacy}) and the legacy-rule checker ({@code LegacyCheck}), run under Node.
 * The fixtures are the 44 real legacy sample sheets embedded in the page.
 */
class LegacySheetsTest {
    private static final String DRIVER = """
            const fs = require('fs'), vm = require('vm');
            const html = fs.readFileSync(process.argv[2], 'utf8');
            const block = (id) => { const open = '<script id="' + id + '">', a = html.indexOf(open); return html.slice(a + open.length, html.indexOf('</script>', a)); };
            // a permissive DOM stub: the UI block wires itself to elements this test does not have
            const stub = () => new Proxy(function () {}, { get: (t, k) => k === Symbol.toPrimitive ? () => '' : k === 'length' ? 0 : stub(), apply: () => stub(), set: () => true });
            const ctx = { console, Math, Date, Uint8Array, TextDecoder, TextEncoder, setTimeout, navigator: {}, document: stub(), location: {} };
            ctx.window = ctx; vm.createContext(ctx);
            const a0 = html.indexOf('<script>\\n'); vm.runInContext(html.slice(a0 + 9, html.indexOf('</script>', a0)), ctx);
            vm.runInContext(block('engine'), ctx);
            vm.runInContext(block('rater-engine'), ctx);
            try { vm.runInContext(block('rater-ui'), ctx); } catch (e) { /* DOM wiring only; LegacyCheck is defined before it */ }
            const R = ctx.RaterModel, L = R.legacy, S = R.LEGACY_SAMPLES, out = {};
            out.slug = [L.slug('Sum Insured (Rs.)'), L.slug(' Total Premium ')];
            out.ageBand = ['>60', '<18', '35', '18-45'].map(L.ageBand);
            out.construct = ['2A + 2C', '1A', '1A + 1C'].map(L.construct);
            out.tokens = ['E,S,2C', 'E,S,C,P'].map(L.familyTokens);
            out.layouts = [L.layoutOf('PREM', 13), L.layoutOf('PREM', 2), L.layoutOf('SI', 1), L.layoutOf('SI', 17)];
            out.samples = []; out.empty = [];
            for (const side of ['SI', 'PREM']) for (const [id, v] of Object.entries(S[side])) {
              const r = L.parse(side, +id, v.aoa, { tax: 18 });
              out.samples.push([side, +id]); if (!r.rows.length) out.empty.push([side, +id]);
            }
            out.gdacRaw = L.parse('PREM', 22, S.PREM['22'].aoa, { tax: 18 }).rows[0];
            out.taxed = L.parse('PREM', 24, S.PREM['24'].aoa, { tax: 18 }).rows[0];
            out.bands = L.parse('SI', 2, [['Age', 'Sum Insured'], ['>60', 5], ['<18', 1]], {}).rows;
            out.unknown = L.parse('SI', 3, [['Bogus', 'x'], ['A', 1]], {}).unmapped;
            try { L.parse('SI', 17, [['a'], ['b']], {}); out.matrixError = null; } catch (e) { out.matrixError = e.message; }

            // the checker, on the page's own column labels
            const H = ['Employee Code', 'Relationship with Employee', 'Employee First Name', 'Employee Last Name', 'Employee Gender', 'Employee DOB', 'Employee Date of Joining',
                       'Employee Email', 'Employee Mobile Number', 'Insured Member First Name', 'Insured Member Last Name', 'Insured Member Gender', 'Insured Member DOB'];
            const good = [['E1', 'Self', 'Asha', 'Rao', 'Female', '15-01-1990', '01-04-2015', 'asha@x.test', '9876543210', '', '', '', ''],
                          ['E1', 'Son', '', '', '', '', '', '', '', 'Ravi', 'Rao', 'Male', '10-05-2018']];
            const bad = [['E2', 'Self', 'As1ha', 'Rao', 'F', '15-01-1990', '15-01-1990', 'bad', '12345', '', '', '', ''],
                         ['E2', 'Son', '', '', '', '', '', '', '', 'Ravi', 'Rao', 'Male', '10-05-1980']];
            out.checkGood = ctx.LegacyCheck.run(H, good, 100).total;
            out.checkBad = ctx.LegacyCheck.run(H, bad, 100).problems.map(p => p.rule);
            process.stdout.write(JSON.stringify(out));
            """;

    static JsonNode out;

    @BeforeAll
    static void run() throws Exception {
        Path dir = Files.createTempDirectory("legacy");
        Path html = dir.resolve("index.html");
        Files.write(html, LegacySheetsTest.class.getResourceAsStream("/static/index.html").readAllBytes());
        Files.writeString(dir.resolve("driver.js"), DRIVER);
        try {
            Process probe = new ProcessBuilder("node", "--version").start();
            assumeTrue(probe.waitFor(30, TimeUnit.SECONDS) && probe.exitValue() == 0, "node is not installed");
        } catch (java.io.IOException e) {
            assumeTrue(false, "node is not installed");
        }
        Process p = new ProcessBuilder("node", dir.resolve("driver.js").toString(), html.toString()).start();
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8), stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(stderr).isZero();
        out = Specs.JSON.readTree(stdout);
    }

    @Test
    void headerSlugsAgeBandsAndConstructTokensFollowLaravelAndTheLegacyHelpers() {
        assertThat(out.path("slug").toString()).isEqualTo("[\"sum_insured_rs\",\"total_premium\"]");
        assertThat(out.path("ageBand").toString()).isEqualTo("[\"60-120\",\"0-18\",\"35-35\",\"18-45\"]");
        assertThat(out.path("construct").toString()).isEqualTo("[{\"adults\":2,\"children\":2},{\"adults\":1,\"children\":0},{\"adults\":1,\"children\":1}]");
        assertThat(out.path("tokens").toString()).isEqualTo("[{\"adults\":2,\"children\":2},{\"adults\":3,\"children\":1}]");
    }

    @Test
    void layoutsAreHeadingMatrixOrFormPerType() {
        assertThat(out.path("layouts").toString()).isEqualTo("[\"heading\",\"matrix\",\"form\",\"matrix\"]");
    }

    @Test
    void everyRealLegacySampleSheetParsesToRows() {
        assertThat(out.path("samples")).hasSize(44);
        assertThat(out.path("empty").toString()).isEqualTo("[]");
    }

    @Test
    void premiumCellsAreStoredTaxedExceptForGdac() {
        assertThat(out.path("gdacRaw").path("self_premium").asDouble()).isEqualTo(10000.0);        // GDAC (22): raw
        assertThat(out.path("taxed").path("self_premium").asDouble()).isEqualTo(11.8);              // type 24: cell x 1.18
        assertThat(out.path("bands").toString()).isEqualTo("[{\"age\":\"60-120\",\"age_suminsured\":5},{\"age\":\"0-18\",\"age_suminsured\":1}]");
    }

    @Test
    void unknownHeadersAreReportedAndAMatrixWithoutItsColumnsIsRejected() {
        assertThat(out.path("unknown").toString()).isEqualTo("[\"Bogus\",\"x\"]");
        assertThat(out.path("matrixError").asString()).contains("Relationship");
    }

    @Test
    void theLegacyCheckPassesAValidFamilyAndNamesEachBrokenRule() {
        assertThat(out.path("checkGood").asInt()).isZero();
        assertThat(out.path("checkBad").toString()).contains("NAME_FORMAT", "GENDER", "DOJ_EQ_DOB", "EMAIL_FORMAT", "MOBILE", "CHILD_OLDER_THAN_EMPLOYEE");
    }
}
