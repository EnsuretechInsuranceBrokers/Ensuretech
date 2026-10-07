package com.broker.policy;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;

class RelationRulesTest extends IntegrationTestBase {
    static final String CONSTRUCT = """
        {"allowedRelations":[2,5,9],"maxMembers":6,"maxChildren":3,"includeParents":false,"includeParentsInLaw":false}""";

    private long policy(String number, String rateTable) throws Exception {
        long id = createPolicy(BROKER_A, EMP_A, number, "FLAT", "GMC");
        mvc.perform(json(put("/api/v1/broker/policies/" + id + "/rate-card"), admin(BROKER_A),
                "{\"effectiveFrom\":\"2030-04-01\",\"rateTable\":" + rateTable + "}")).andExpect(status().isOk());
        mvc.perform(json(put("/api/v1/broker/policies/" + id + "/family-construct"), admin(BROKER_A), CONSTRUCT)).andExpect(status().isOk());
        return id;
    }

    private String url(long id, String tail) { return "/api/v1/broker/policies/" + id + tail; }

    private void putRules(long id, String rulesJson, org.springframework.test.web.servlet.ResultMatcher expect) throws Exception {
        mvc.perform(json(put(url(id, "/relation-rules")), admin(BROKER_A), "{\"rules\":" + rulesJson + "}")).andExpect(expect);
    }

    private String validate(long id, String membersJson) throws Exception {
        return mvc.perform(json(post(url(id, "/family/validate")), admin(BROKER_A), "{\"members\":" + membersJson + "}"))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void policyProRataKindsAreDailyAndMonthly() throws Exception {
        long id = createPolicy(BROKER_A, EMP_A, "PR-1", "FLAT", "GMC");
        for (String k : new String[]{"DAILY", "MONTHLY", "NONE"}) {
            mvc.perform(json(post("/api/v1/broker/policies"), admin(BROKER_A),
                    policyJson(EMP_A, "PR-" + k, premiumTypeId("FLAT"), "GMC").replace("}", ",\"proRataType\":\"" + k + "\"}")))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.proRataType", is(k)));
        }
        mvc.perform(json(post("/api/v1/broker/policies"), admin(BROKER_A),
                policyJson(EMP_A, "PR-OLD", premiumTypeId("FLAT"), "GMC").replace("}", ",\"proRataType\":\"EXACT_DAYS\"}")))
                .andExpect(status().is4xxClientError());                                  // the old names are gone, not silently accepted
    }

    @Test
    void rulesAreReplacedAtomicallyValidatedAndTenantScoped() throws Exception {
        long id = policy("R-1", "{\"flat\":1000}");
        putRules(id, """
            [{"relationCode":"SON","minAge":0,"maxAge":25,"ageDifference":18,"differenceFromRelation":"SELF","proRataType":"MONTHLY","maxTwins":1},
             {"relationCode":"PARENT_X","minAge":40}]""", status().isUnprocessableEntity());
        mvc.perform(get(url(id, "/relation-rules")).header("Authorization", admin(BROKER_A))).andExpect(jsonPath("$", hasSize(0)));   // nothing half-saved
        putRules(id, """
            [{"relationCode":"SON","minAge":0,"maxAge":25,"ageDifference":18,"differenceFromRelation":"SELF","proRataType":"MONTHLY","maxTwins":1},
             {"relationCode":"SPOUSE","minAge":21,"maxAge":70}]""", status().isOk());
        putRules(id, "[{\"relationCode\":\"SPOUSE\",\"minAge\":21}]", status().isOk());                                              // replaces, does not append
        mvc.perform(get(url(id, "/relation-rules")).header("Authorization", admin(BROKER_A))).andExpect(jsonPath("$", hasSize(1)));
        for (String bad : new String[]{
                "[{\"relationCode\":\"SPOUSE\",\"minAge\":50,\"maxAge\":20}]", "[{\"relationCode\":\"SPOUSE\",\"ageDifference\":5}]",
                "[{\"relationCode\":\"SPOUSE\",\"ageDifference\":5,\"differenceFromRelation\":\"FATHER\"}]",
                "[{\"relationCode\":\"SPOUSE\",\"maxTwins\":1}]", "[{\"relationCode\":\"SON\"},{\"relationCode\":\"SON\"}]"}) {
            putRules(id, bad, status().is4xxClientError());
        }
        mvc.perform(get(url(id, "/relation-rules")).header("Authorization", admin(BROKER_B))).andExpect(status().isNotFound());
        mvc.perform(json(put(url(id, "/relation-rules")), admin(BROKER_B), "{\"rules\":[]}")).andExpect(status().isNotFound());
        mvc.perform(json(put(url(id, "/relation-rules")), bearer("BROKER_NON_ADMIN", BROKER_A, null), "{\"rules\":[]}")).andExpect(status().isForbidden());
    }

    @Test
    void familyValidationEnforcesAgeWindowsGapsAndTheAdultDefault() throws Exception {
        long id = policy("R-2", "{\"flat\":1000}");
        putRules(id, """
            [{"relationCode":"SON","minAge":0,"maxAge":25,"ageDifference":18,"differenceFromRelation":"SELF"},
             {"relationCode":"SPOUSE","minAge":21,"maxAge":60}]""", status().isOk());
        String ok = validate(id, "[{\"relationCode\":\"SELF\",\"age\":40},{\"relationCode\":\"SPOUSE\",\"age\":38},{\"relationCode\":\"SON\",\"age\":10}]");
        org.assertj.core.api.Assertions.assertThat(ok).contains("\"valid\":true");
        String bad = validate(id, "[{\"relationCode\":\"SELF\",\"age\":40},{\"relationCode\":\"SPOUSE\",\"age\":19},{\"relationCode\":\"SON\",\"age\":30}]");
        org.assertj.core.api.Assertions.assertThat(bad).contains("RELATION_AGE_BELOW_MIN", "RELATION_AGE_ABOVE_MAX", "AGE_GAP_TOO_SMALL");
        String gap = validate(id, "[{\"relationCode\":\"SELF\",\"age\":30},{\"relationCode\":\"SON\",\"age\":15}]");                // 15 years apart, needs 18
        org.assertj.core.api.Assertions.assertThat(gap).contains("AGE_GAP_TOO_SMALL").doesNotContain("RELATION_AGE");
        String young = validate(id, "[{\"relationCode\":\"SELF\",\"age\":17}]");                                                    // no rule for SELF: adults are 18+
        org.assertj.core.api.Assertions.assertThat(young).contains("ADULT_UNDER_18");
        String edge = validate(id, "[{\"relationCode\":\"SELF\",\"age\":18},{\"relationCode\":\"SON\",\"age\":0}]");
        org.assertj.core.api.Assertions.assertThat(edge).doesNotContain("ADULT_UNDER_18");                                          // 18 is fine; a newborn is fine
    }

    @Test
    void sheetSpecDescribesWhatTheRateCardAndConstructAllow() throws Exception {
        long id = createPolicy(BROKER_A, EMP_A, "S-1", "GRADE", "GMC");
        mvc.perform(get(url(id, "/sheet-spec")).header("Authorization", admin(BROKER_A))).andExpect(status().isOk())
                .andExpect(jsonPath("$.schema", is("sheet-spec/v1"))).andExpect(jsonPath("$.gaps[*]", hasItems(startsWith("NO_ACTIVE_RATE_CARD"), startsWith("NO_FAMILY_CONSTRUCT"))));
        mvc.perform(json(put(url(id, "/rate-card")), admin(BROKER_A), "{\"effectiveFrom\":\"2030-04-01\",\"rateTable\":{\"grades\":{\"G1\":5000,\"G2\":4000}}}")).andExpect(status().isOk());
        mvc.perform(json(put(url(id, "/family-construct")), admin(BROKER_A), CONSTRUCT)).andExpect(status().isOk());
        putRules(id, "[{\"relationCode\":\"SON\",\"maxAge\":25,\"proRataType\":\"MONTHLY\",\"maxTwins\":2}]", status().isOk());
        mvc.perform(get(url(id, "/sheet-spec")).header("Authorization", admin(BROKER_A))).andExpect(status().isOk())
                .andExpect(jsonPath("$.policy.startDate", is("2030-04-01"))).andExpect(jsonPath("$.policy.proRata", is("NONE")))
                .andExpect(jsonPath("$.rating.premiumType", is("GRADE"))).andExpect(jsonPath("$.rating.grades", contains("G1", "G2")))
                .andExpect(jsonPath("$.rating.sumInsured.mode", is("FREE"))).andExpect(jsonPath("$.sheet.needsGrade", is(true)))
                .andExpect(jsonPath("$.family.allowedRelations[*].code", hasItems("SPOUSE")))
                .andExpect(jsonPath("$.family.maxChildren", is(3)))
                .andExpect(jsonPath("$.family.allowedRelations[?(@.code=='SON')].maxAge", contains(25)))     // the relation's own ceiling, for the sheet generator
                .andExpect(jsonPath("$.relationRules[0].relationCode", is("SON"))).andExpect(jsonPath("$.relationRules[0].maxTwins", is(2)))
                .andExpect(jsonPath("$.relationRules[0].proRata", is("NONE")))      // the policy forbids pro-rata: the relation cannot opt back in
                .andExpect(jsonPath("$.gaps", hasSize(0)));
        mvc.perform(get(url(id, "/sheet-spec")).header("Authorization", admin(BROKER_B))).andExpect(status().isNotFound());
        mvc.perform(get(url(id, "/sheet-spec")).header("Authorization", bearer("EMPLOYER_ADMIN", BROKER_A, EMP_A))).andExpect(status().isForbidden());
    }

    @Test
    void sheetSpecListsLolLimitsAndBandsFromNestedTables() throws Exception {
        long lol = createPolicy(BROKER_A, EMP_A, "S-2", "LOL", "GMC");
        mvc.perform(json(put(url(lol, "/rate-card")), admin(BROKER_A), "{\"effectiveFrom\":\"2030-04-01\",\"rateTable\":{\"lol\":[{\"limit\":500000,\"premium\":900},{\"limit\":1000000,\"premium\":1500}]}}")).andExpect(status().isOk());
        mvc.perform(get(url(lol, "/sheet-spec")).header("Authorization", admin(BROKER_A)))
                .andExpect(jsonPath("$.rating.sumInsured.mode", is("ONE_OF"))).andExpect(jsonPath("$.rating.sumInsured.values", contains(500000, 1000000)));
        long age = createPolicy(BROKER_A, EMP_A, "S-3", "AGE_BAND", "GMC");
        mvc.perform(json(put(url(age, "/rate-card")), admin(BROKER_A), "{\"effectiveFrom\":\"2030-04-01\",\"rateTable\":{\"age_bands\":[{\"from\":0,\"to\":30,\"premium\":100},{\"from\":31,\"to\":60,\"premium\":200}]}}")).andExpect(status().isOk());
        mvc.perform(get(url(age, "/sheet-spec")).header("Authorization", admin(BROKER_A)))
                .andExpect(jsonPath("$.rating.ageBands", hasSize(2))).andExpect(jsonPath("$.rating.ageBands[1].to", is(60)));
    }
}
