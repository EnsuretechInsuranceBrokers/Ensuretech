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

/** The browser page's legacy rater model (the {@code <script id="rater-engine">} block), run under Node against hand-computed legacy cases. */
class RaterModelTest {
    static Path dir;

    private static final String DRIVER = """
            const fs = require('fs'), vm = require('vm');
            const ctx = { console };
            vm.createContext(ctx);
            vm.runInContext(fs.readFileSync(process.argv[2] + '/rater.js', 'utf8'), ctx);
            const R = ctx.RaterModel;
            const out = {};
            const asOf = new Date(Date.UTC(2026, 9, 7));
            const dobFor = (age) => new Date(Date.UTC(2026 - age, 0, 15));
            const fam = (...rels) => rels.map(([rel, age]) => ({ rel, age, gender: 'Male' }));
            const si = (id, rows, f, emp, sheetSi) => R.computeSI(R.SI_TYPES.find(t => t.id === id), rows, f, emp || {}, sheetSi == null ? null : sheetSi, {});
            const pr = (id, rows, f, emp, sis, total) => R.computePremium(R.PREMIUM_TYPES.find(t => t.id === id), rows, f, emp || {}, sis, total, {});

            // 5 NUMBER_OF_TIME_SALARY: round(salary x multiple), clamp to min/max, then roundTo(type, unit)
            const sal = [{ no_of_times_of_salary: 2, min_si_limit: 200000, max_si_limit: 5000000, rounding_type: 2, rounding_unit: 1000 }];
            out.salaryRound = si(5, sal, fam(['SELF', 40]), { salary: 582922, multiple: 2 }).total;
            out.salaryFloor = si(5, [{ ...sal[0], rounding_type: 0 }], fam(['SELF', 40]), { salary: 582922, multiple: 2 }).total;
            out.salaryCeil = si(5, [{ ...sal[0], rounding_type: 1 }], fam(['SELF', 40]), { salary: 582922, multiple: 2 }).total;
            out.salaryMin = si(5, [{ ...sal[0], no_of_times_of_salary: 1 }], fam(['SELF', 40]), { salary: 50000, multiple: 1 }).total;      // clamped up to min 200000 (and the multiple row must exist)
            out.salaryNoRow = si(5, sal, fam(['SELF', 40]), { salary: 500000, multiple: 3 }).error;
            out.salaryMax = si(5, sal, fam(['SELF', 40]), { salary: 9000000, multiple: 2 }).total;
            out.roundTo = [R.roundTo(1165844, 2, 1000), R.roundTo(1165500, 2, 1000), R.roundTo(1165499, 2, 1000), R.roundTo(1234, 2, 0)];   // unit 0 -> 10
            // 1 FLAT / 3 GRADE / 2 AGE / 6 MEMBER
            out.flat = si(1, [{ flat_suminsured: 300000 }], fam(['SELF', 30], ['SPOUSE', 28]), {}, 300000).si;
            out.grade = si(3, [{ grade: 'A', grade_suminsured: 300000 }, { grade: 'B', grade_suminsured: 500000 }], fam(['SELF', 30], ['SON', 3]), { grade: 'b' }).si;
            out.ageBands = si(2, [{ age: '0-17', age_suminsured: 100000 }, { age: '18-45', age_suminsured: 300000 }], fam(['SELF', 45], ['SON', 17]), {}).si;
            out.ageGap = si(2, [{ age: '18-45', age_suminsured: 300000 }], fam(['SELF', 46]), {}).error;
            out.member = si(6, [{ relation: 'Self', relation_sum_insured: 500000 }, { relation: 'Son', relation_sum_insured: 200000 }], fam(['SELF', 40], ['SON', 5]), {}).si;
            // 9 FAMILY_CONSTRUCT_WISE: exact (adults, children) row, else first row with >= on both; a pair of parents is carried on the first parent only
            const cons = [
              { no_of_adult: 2, no_of_children: 1, total_cover: 700000, self_suminsured: 300000, spouse_suminsured: 250000, kids_suminsured: 150000 },
              { no_of_adult: 3, no_of_children: 2, total_cover: 900000, self_suminsured: 400000, spouse_suminsured: 300000, kids_suminsured: 100000, single_parent_suminsured: 100000 },
              { no_of_adult: 4, no_of_children: 0, total_cover: 1000000, self_suminsured: 400000, spouse_suminsured: 300000, double_parent_suminsured: 300000 }];
            const c1 = si(9, cons, fam(['SELF', 35], ['SPOUSE', 33], ['SON', 5]), {}, 700000);
            out.constructExact = { si: c1.si, total: c1.total };
            const c2 = si(9, cons, fam(['SELF', 35], ['SPOUSE', 33], ['SON', 5], ['DAUGHTER', 3]), {}, null);        // 2 adults, 2 kids -> falls back to the first >= row (3 adults, 2 kids)
            out.constructFallback = { si: c2.si, total: c2.total };
            const c3 = si(9, cons, fam(['SELF', 35], ['SPOUSE', 33], ['FATHER', 60], ['MOTHER', 58]), {}, 1000000);
            out.constructParents = c3.si;                                                                          // double_parent on the FIRST parent, 0 on the second
            // premiums
            out.flatPrem = pr(1, [{ flat_suminsured: 500000, total_premium: 4720 }], fam(['SELF', 30]), {}, [500000], 500000).premium;
            out.permilly = pr(5, [{ age: '18-45', age_permilly: 3.5, tax: 18 }], fam(['SELF', 30]), { age: 30 }, [1000000], 1000000).premium[0];
            out.employeeAge = pr(2, [{ age: '18-30', total_premium: 1000 }, { age: '31-45', total_premium: 2000 }], fam(['SELF', 35], ['SON', 5]), { age: 35 }, [1, 1], 1).premium;   // every member uses the EMPLOYEE's age
            out.memberAge = pr(8, [{ age: '0-17', total_premium: 500 }, { age: '31-45', total_premium: 2000 }], fam(['SELF', 35], ['SON', 5]), { age: 35 }, [1, 1], 1).premium;
            const cp = [{ no_of_adult: 2, no_of_children: 1, total_cover: 700000, self_premium: 4000, spouse_premium: 3000, kids_premium: 1500, self_employee_contribution: 50, spouse_employee_contribution: 100, per_kid_employee_contribution: 0 }];
            const p13 = pr(13, cp, fam(['SELF', 35], ['SPOUSE', 33], ['SON', 5]), {}, [1, 1, 1], 700000);
            out.constructPrem = { premium: p13.premium, contribution: p13.contribution };
            out.noRule = pr(18, [], fam(['SELF', 30]), {}, [1], 1).noRule;
            // the model: contribution split, rounding of money
            const m = R.createModel({ siId: 1, premiumId: 1, siRows: [{ flat_suminsured: 500000, total_premium: 1000 }], premRows: [{ flat_suminsured: 500000, total_premium: 1000 }], calcPremium: true, contributionPct: 30,
              rnd: () => 0.5, salary: { min: 1, max: 2 }, noRule: {} });
            const f = [{ values: {}, rel: 'SELF', dob: dobFor(30), gender: 'Male' }, { values: {}, rel: 'SPOUSE', dob: dobFor(28), gender: 'Female' }];
            m.apply(f, m.scenario(), asOf, 'Male');
            out.split = f.map(x => [x.values.suminsured, x.values.employee_premium, x.values.employer_premium]);
            // every SI type resolves with its own sample rows; every premium type resolves with some natural SI partner
            const pairs = [[1,1],[2,2],[2,8],[3,4],[4,3],[5,5],[6,9],[7,10],[9,13],[13,15],[14,16],[15,17],[16,18],[17,9],[18,6],[20,20],[22,22],[23,25],[24,25],[25,26],[26,23],[27,7],[28,1],[29,5],[30,29],[31,5],[8,1],[1,19],[2,21],[3,31],[1,30],[9,27],[9,32],[1,28],[13,24]];
            let seed = 1; const rnd = () => { seed = (seed * 1664525 + 1013904223) >>> 0; return seed / 4294967296; };
            out.pairs = pairs.map(([s, p]) => {
              const model = R.createModel({ siId: s, premiumId: p, siRows: R.sampleSi(s), premRows: R.samplePremium(p, s), calcPremium: true, contributionPct: 0, rnd, salary: { min: 400000, max: 2000000 }, noRule: { emp: 1, er: 1 }, sheetSi: 500000 });
              for (let i = 0; i < 80; i++) {
                const sc = model.scenario(), shape = sc.shape || { adults: 1 + (rnd() < .7 ? 1 : 0), children: Math.floor(rnd() * 3) };
                const members = [{ values: {}, rel: 'SELF', dob: dobFor(30 + Math.floor(rnd() * 25)), gender: 'Male' }];
                let a = 1; if (a < shape.adults) { members.push({ values: {}, rel: 'SPOUSE', dob: dobFor(30), gender: 'Female' }); a++; }
                if (a < shape.adults) { members.push({ values: {}, rel: 'FATHER', dob: dobFor(62), gender: 'Male' }); a++; }
                if (a < shape.adults) { members.push({ values: {}, rel: 'MOTHER', dob: dobFor(60), gender: 'Female' }); a++; }
                for (let k = 0; k < shape.children; k++) members.push({ values: {}, rel: k % 2 ? 'DAUGHTER' : 'SON', dob: dobFor(3 + k), gender: 'Male' });
                model.apply(members, sc, asOf, 'Male');
              }
              return [s, p, model.stats.siErrors, model.stats.premiumErrors];
            });
            out.catalog = [R.SI_TYPES.length, R.PREMIUM_TYPES.length];
            process.stdout.write(JSON.stringify(out));
            """;

    static JsonNode out;

    @BeforeAll
    static void run() throws Exception {
        String html = new String(RaterModelTest.class.getResourceAsStream("/static/index.html").readAllBytes(), StandardCharsets.UTF_8);
        int a = html.indexOf("<script id=\"rater-engine\">"), b = html.indexOf("</script>", a);
        assertThat(a).isPositive();
        dir = Files.createTempDirectory("rater");
        Files.writeString(dir.resolve("rater.js"), html.substring(a + "<script id=\"rater-engine\">".length(), b));
        Files.writeString(dir.resolve("driver.js"), DRIVER);
        Process probe = null;
        try {
            probe = new ProcessBuilder("node", "--version").start();
            assumeTrue(probe.waitFor(30, TimeUnit.SECONDS) && probe.exitValue() == 0, "node is not installed; the rater model cannot be exercised");
        } catch (java.io.IOException e) {
            assumeTrue(false, "node is not installed; the rater model cannot be exercised");
        }
        Process p = new ProcessBuilder("node", dir.resolve("driver.js").toString(), dir.toString()).start();
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8), stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(stderr).isZero();
        out = Specs.JSON.readTree(stdout);
    }

    private static long l(String k) { return out.path(k).asLong(); }

    @Test
    void salaryMultipleSumInsuredIsRoundedClampedAndRoundedToTheRowsUnit() {
        assertThat(l("salaryRound")).isEqualTo(1_166_000);          // round(582922 x 2) = 1165844 -> nearest 1000
        assertThat(l("salaryFloor")).isEqualTo(1_165_000);
        assertThat(l("salaryCeil")).isEqualTo(1_166_000);
        assertThat(l("salaryMin")).isEqualTo(200_000);               // below the row's minimum
        assertThat(l("salaryMax")).isEqualTo(5_000_000);             // above the row's maximum
        assertThat(out.path("salaryNoRow").asString()).contains("no salary-multiple row");
        assertThat(out.path("roundTo")).hasSize(4);
        assertThat(out.path("roundTo").get(0).asLong()).isEqualTo(1_166_000);
        assertThat(out.path("roundTo").get(1).asLong()).isEqualTo(1_166_000);   // exactly half rounds up, PHP round()
        assertThat(out.path("roundTo").get(2).asLong()).isEqualTo(1_165_000);
        assertThat(out.path("roundTo").get(3).asLong()).isEqualTo(1230);        // unit 0 means 10
    }

    @Test
    void lookupSubtypesFindTheRightRow() {
        assertThat(out.path("flat").toString()).isEqualTo("[300000,300000]");
        assertThat(out.path("grade").toString()).isEqualTo("[500000,500000]");          // grade matched case-insensitively, same SI for the family
        assertThat(out.path("ageBands").toString()).isEqualTo("[300000,100000]");       // each member's own age
        assertThat(out.path("ageGap").asString()).contains("no age band");
        assertThat(out.path("member").toString()).isEqualTo("[500000,200000]");
    }

    @Test
    void constructRowsMatchExactlyThenFallBackToTheFirstRowWithAtLeastTheCountsAndPairsOfParentsAreCarriedOnTheFirst() {
        assertThat(out.path("constructExact").path("si").toString()).isEqualTo("[300000,250000,150000]");
        assertThat(out.path("constructExact").path("total").asLong()).isEqualTo(700_000);
        assertThat(out.path("constructFallback").path("si").toString()).isEqualTo("[400000,300000,100000,100000]");
        assertThat(out.path("constructFallback").path("total").asLong()).isEqualTo(900_000);
        assertThat(out.path("constructParents").toString()).isEqualTo("[400000,300000,300000,0]");
    }

    @Test
    void premiumsFollowTheirLegacyRules() {
        assertThat(out.path("flatPrem").toString()).isEqualTo("[4720]");
        assertThat(out.path("permilly").asDouble()).isEqualTo(4130.0);                   // 1,000,000 x 3.5 / 1000 = 3500, plus 18 % tax
        assertThat(out.path("employeeAge").toString()).isEqualTo("[2000,2000]");         // EMPLOYEE_AGE: the employee's age for every member
        assertThat(out.path("memberAge").toString()).isEqualTo("[2000,500]");            // MEMBER_AGE: each member's own age
        assertThat(out.path("constructPrem").path("premium").toString()).isEqualTo("[4000,3000,1500]");
        assertThat(out.path("constructPrem").path("contribution").toString()).isEqualTo("[50,100,0]");
        assertThat(out.path("noRule").asBoolean()).isTrue();
    }

    @Test
    void theEmployeeEmployerSplitUsesTheContributionPercent() {
        assertThat(out.path("split").toString()).isEqualTo("[[500000,300,700],[500000,300,700]]");
    }

    @Test
    void everySumInsuredSubtypeAndPremiumTypeResolvesWithItsOwnSampleRates() {
        assertThat(out.path("catalog").toString()).isEqualTo("[26,29]");
        java.util.Set<Integer> sis = new java.util.HashSet<>(), prs = new java.util.HashSet<>();
        for (JsonNode p : out.path("pairs")) {
            assertThat(p.get(2).asInt()).as("SI errors for SI %s / premium %s", p.get(0), p.get(1)).isZero();
            assertThat(p.get(3).asInt()).as("premium errors for SI %s / premium %s", p.get(0), p.get(1)).isZero();
            sis.add(p.get(0).asInt());
            prs.add(p.get(1).asInt());
        }
        assertThat(sis).hasSize(26);
        assertThat(prs).hasSize(29);
    }
}
