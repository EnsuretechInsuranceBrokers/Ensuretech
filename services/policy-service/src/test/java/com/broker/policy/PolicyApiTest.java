package com.broker.policy;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PolicyApiTest extends IntegrationTestBase {

    static final String RATE_CARD = """
        {"effectiveFrom":"2030-04-01","rateTable":{"flat":1000}}""";
    static final String CONSTRUCT = """
        {"allowedRelations":[2,5,9],"maxMembers":5,"maxChildren":2,"includeParents":false,"includeParentsInLaw":false,"ageMin":0,"ageMax":65}""";

    private long readyPolicy(String number) throws Exception {
        long id = createPolicy(BROKER_A, EMP_A, number, "FLAT", "GMC");
        mvc.perform(json(put("/api/v1/broker/policies/" + id + "/rate-card"), admin(BROKER_A), RATE_CARD)).andExpect(status().isOk());
        mvc.perform(json(put("/api/v1/broker/policies/" + id + "/family-construct"), admin(BROKER_A), CONSTRUCT)).andExpect(status().isOk());
        return id;
    }

    @Test
    void lifecycleCreateConfigureApproveDeactivate() throws Exception {
        long id = createPolicy(BROKER_A, EMP_A, "P-1", "FLAT", "GMC");
        mvc.perform(get("/api/v1/broker/policies/" + id).header("Authorization", admin(BROKER_A)))
                .andExpect(jsonPath("$.status", is("NOT_APPROVED")));

        // not approvable until configured
        mvc.perform(post("/api/v1/broker/policies/" + id + "/approve").header("Authorization", admin(BROKER_A)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.issues[*].code", hasItems("NO_ACTIVE_RATE_CARD", "NO_FAMILY_CONSTRUCT")));

        mvc.perform(json(put("/api/v1/broker/policies/" + id + "/rate-card"), admin(BROKER_A), RATE_CARD)).andExpect(status().isOk());
        mvc.perform(json(put("/api/v1/broker/policies/" + id + "/family-construct"), admin(BROKER_A), CONSTRUCT)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/broker/policies/" + id + "/readiness").header("Authorization", admin(BROKER_A)))
                .andExpect(jsonPath("$.valid", is(true)));

        mvc.perform(post("/api/v1/broker/policies/" + id + "/approve").header("Authorization", admin(BROKER_A)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("APPROVED_AND_LIVE")));
        mvc.perform(post("/api/v1/broker/policies/" + id + "/approve").header("Authorization", admin(BROKER_A)))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/broker/policies/" + id + "/deactivate").header("Authorization", admin(BROKER_A)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("INACTIVE")));
    }

    @Test
    void unreadableBodyIsABadRequestNotAnEmptyForbidden() throws Exception {
        long id = readyPolicy("P-BAD");
        mvc.perform(json(put("/api/v1/broker/policies/" + id + "/contributions"), admin(BROKER_A),
                "{\"type\":\"PERCENT\",\"groups\":{\"SELF\":{\"nested\":1}}}")).andExpect(status().isBadRequest());
        mvc.perform(json(put("/api/v1/broker/policies/" + id + "/rounding"), admin(BROKER_A), "{not json")).andExpect(status().isBadRequest());
    }

    @Test
    void otherBrokerCannotSeeOrChangeAPolicy() throws Exception {
        long id = readyPolicy("P-1");
        String b = admin(BROKER_B);
        for (var req : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[] {
                get("/api/v1/broker/policies/" + id), get("/api/v1/broker/policies/" + id + "/rate-card"),
                get("/api/v1/broker/policies/" + id + "/family-construct"), get("/api/v1/broker/policies/" + id + "/readiness"),
                post("/api/v1/broker/policies/" + id + "/approve"), post("/api/v1/broker/policies/" + id + "/deactivate")}) {
            mvc.perform(req.header("Authorization", b)).andExpect(status().isNotFound());
        }
        mvc.perform(json(put("/api/v1/broker/policies/" + id + "/rate-card"), b, RATE_CARD)).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/broker/policies/" + id + "/premium/quote"), b,
                "{\"members\":[{\"ref\":\"1\",\"relationCode\":\"SELF\",\"age\":30}]}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/broker/policies").header("Authorization", b))
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void cannotAttachPolicyToAnotherBrokersEmployer() throws Exception {
        mvc.perform(json(post("/api/v1/broker/policies"), admin(BROKER_A), policyJson(EMP_B, "X", premiumTypeId("FLAT"), "GMC")))
                .andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/broker/policies"), admin(BROKER_A), policyJson(999, "X", premiumTypeId("FLAT"), "GMC")))
                .andExpect(status().isNotFound());
    }

    @Test
    void deactivatedEmployerBlocksNewPoliciesAndApproval() throws Exception {
        long id = readyPolicy("P-1");
        jdbc.sql("UPDATE policy_svc.employer_refs SET is_active=false WHERE employer_id=:e").param("e", EMP_A).update();
        mvc.perform(post("/api/v1/broker/policies/" + id + "/approve").header("Authorization", admin(BROKER_A)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.issues[*].code", hasItem("EMPLOYER_INACTIVE")));
        mvc.perform(json(post("/api/v1/broker/policies"), admin(BROKER_A), policyJson(EMP_A, "P-2", premiumTypeId("FLAT"), "GMC")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void gpaPolicyDoesNotNeedAFamilyConstruct() throws Exception {
        long id = createPolicy(BROKER_A, EMP_A, "GPA-1", "FLAT", "GPA");
        mvc.perform(json(put("/api/v1/broker/policies/" + id + "/rate-card"), admin(BROKER_A), RATE_CARD)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/broker/policies/" + id + "/approve").header("Authorization", admin(BROKER_A)))
                .andExpect(status().isOk());
    }

    @Test
    void duplicatePolicyNumberIs409ButSameNumberOkAcrossBrokers() throws Exception {
        createPolicy(BROKER_A, EMP_A, "P-1", "FLAT", "GMC");
        mvc.perform(json(post("/api/v1/broker/policies"), admin(BROKER_A), policyJson(EMP_A, "P-1", premiumTypeId("FLAT"), "GMC")))
                .andExpect(status().isConflict());
        mvc.perform(json(post("/api/v1/broker/policies"), admin(BROKER_B), policyJson(EMP_B, "P-1", premiumTypeId("FLAT"), "GMC")))
                .andExpect(status().isCreated());
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        mvc.perform(json(post("/api/v1/broker/policies"), admin(BROKER_A),
                policyJson(EMP_A, "P-1", premiumTypeId("FLAT"), "GMC").replace("2031-03-31", "2029-01-01")))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(json(post("/api/v1/broker/policies"), admin(BROKER_A),
                policyJson(EMP_A, "P-1", premiumTypeId("FLAT"), "BOGUS"))).andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/v1/broker/policies"), admin(BROKER_A),
                policyJson(EMP_A, "P-1", (short) 999, "GMC"))).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void rateCardMustSuitThePremiumTypeAndReplacementKeepsOneActive() throws Exception {
        long id = createPolicy(BROKER_A, EMP_A, "P-1", "AGE_BAND", "GMC");
        mvc.perform(json(put("/api/v1/broker/policies/" + id + "/rate-card"), admin(BROKER_A), RATE_CARD))   // flat table on age policy
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.issues[0].code", is("RATE_TABLE_INVALID")));
        String good = "{\"effectiveFrom\":\"2030-04-01\",\"taxRate\":12.5,\"rateTable\":{\"age_bands\":[{\"from\":0,\"to\":99,\"premium\":700}]}}";
        mvc.perform(json(put("/api/v1/broker/policies/" + id + "/rate-card"), admin(BROKER_A), good)).andExpect(status().isOk());
        mvc.perform(json(put("/api/v1/broker/policies/" + id + "/rate-card"), admin(BROKER_A),
                good.replace("700", "800").replace("2030-04-01", "2030-10-01"))).andExpect(status().isOk());
        int active = jdbc.sql("SELECT count(*) FROM policy_svc.rate_cards WHERE policy_id=:p AND is_active").param("p", id).query(Integer.class).single();
        int total = jdbc.sql("SELECT count(*) FROM policy_svc.rate_cards WHERE policy_id=:p").param("p", id).query(Integer.class).single();
        org.assertj.core.api.Assertions.assertThat(active).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(total).isEqualTo(2);   // history kept
        mvc.perform(get("/api/v1/broker/policies/" + id + "/rate-card").header("Authorization", admin(BROKER_A)))
                .andExpect(jsonPath("$.rateTable.age_bands[0].premium", is(800)));
    }

    @Test
    void familyValidationReportsViolations() throws Exception {
        long id = readyPolicy("P-1");
        String ok = "{\"members\":[{\"relationCode\":\"SELF\",\"age\":40},{\"relationCode\":\"SPOUSE\",\"age\":38},{\"relationCode\":\"SON\",\"age\":10}]}";
        mvc.perform(json(post("/api/v1/broker/policies/" + id + "/family/validate"), admin(BROKER_A), ok))
                .andExpect(jsonPath("$.valid", is(true)));
        String bad = "{\"members\":[{\"relationCode\":\"SELF\",\"age\":40},{\"relationCode\":\"FATHER\",\"age\":70},"
                + "{\"relationCode\":\"SON\",\"age\":30},{\"relationCode\":\"SON_II\",\"age\":5},{\"relationCode\":\"DAUGHTER\",\"age\":4},"
                + "{\"relationCode\":\"DAUGHTER_II\",\"age\":2}]}";
        mvc.perform(json(post("/api/v1/broker/policies/" + id + "/family/validate"), admin(BROKER_A), bad))
                .andExpect(jsonPath("$.valid", is(false)))
                .andExpect(jsonPath("$.issues[*].code", hasItems("RELATION_NOT_ALLOWED", "MAX_MEMBERS_EXCEEDED",
                        "MAX_CHILDREN_EXCEEDED", "DEPENDENT_OVER_MAX_AGE", "AGE_OUT_OF_RANGE")));
        mvc.perform(json(post("/api/v1/broker/policies/" + id + "/family/validate"), admin(BROKER_A),
                "{\"members\":[{\"relationCode\":\"SPOUSE\",\"age\":38}]}"))
                .andExpect(jsonPath("$.issues[*].code", hasItem("SELF_COUNT")));
    }

    @Test
    void familyConstructMustBeSelfConsistent() throws Exception {
        long id = createPolicy(BROKER_A, EMP_A, "P-1", "FLAT", "GMC");
        // relation 12 = FATHER but include_parents=false
        mvc.perform(json(put("/api/v1/broker/policies/" + id + "/family-construct"), admin(BROKER_A),
                "{\"allowedRelations\":[2,12],\"maxMembers\":4}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.issues[0].code", is("PARENTS_FLAG_MISMATCH")));
        mvc.perform(json(put("/api/v1/broker/policies/" + id + "/family-construct"), admin(BROKER_A),
                "{\"allowedRelations\":[2,12],\"maxMembers\":4,\"includeParents\":true,\"maxParents\":1}")).andExpect(status().isOk());
        mvc.perform(json(put("/api/v1/broker/policies/" + id + "/family-construct"), admin(BROKER_A),
                "{\"allowedRelations\":[999],\"maxMembers\":4}")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void quoteEndpointPricesWithTheActiveCard() throws Exception {
        long id = readyPolicy("P-1");   // flat 1000, tax defaults to 18
        mvc.perform(json(post("/api/v1/broker/policies/" + id + "/premium/quote"), bearer("BROKER_NON_ADMIN", BROKER_A, null),
                "{\"members\":[{\"ref\":\"a\",\"relationCode\":\"SELF\",\"age\":30},{\"ref\":\"b\",\"relationCode\":\"SPOUSE\",\"age\":29}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subtotal", is(2000.00)))
                .andExpect(jsonPath("$.tax", is(360.00))).andExpect(jsonPath("$.total", is(2360.00)));
        mvc.perform(json(post("/api/v1/broker/policies/" + id + "/premium/quote"), admin(BROKER_A),
                "{\"members\":[{\"ref\":\"a\",\"relationCode\":\"NOPE\",\"age\":30}]}")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void roleAndGatewayRules() throws Exception {
        for (String role : new String[] {"BROKER_NON_ADMIN", "BROKER_SPECIALIZER"}) {
            mvc.perform(json(post("/api/v1/broker/policies"), bearer(role, BROKER_A, null),
                    policyJson(EMP_A, "P-1", premiumTypeId("FLAT"), "GMC"))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/broker/policies")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/broker/policies").header("Authorization", bearer("EMPLOYER_ADMIN", BROKER_A, EMP_A)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/employer/policies").header("Authorization", admin(BROKER_A))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/other").header("Authorization", bearer("SYSTEM", null, null))).andExpect(status().isForbidden());
        // refresh token is not an access token
        String refresh = TestTokens.build(TestTokens.KEYS, TestTokens.ISSUER, "refresh", "BROKER_ADMIN", BROKER_A, null, 600);
        mvc.perform(get("/api/v1/broker/policies").header("Authorization", "Bearer " + refresh)).andExpect(status().isUnauthorized());
    }

    @Test
    void employerSeesOnlyLivePoliciesOfOwnEmployer() throws Exception {
        long live = readyPolicy("LIVE");
        mvc.perform(post("/api/v1/broker/policies/" + live + "/approve").header("Authorization", admin(BROKER_A))).andExpect(status().isOk());
        long draft = createPolicy(BROKER_A, EMP_A, "DRAFT", "FLAT", "GMC");
        createPolicy(BROKER_B, EMP_B, "OTHER", "FLAT", "GMC");
        String hr = bearer("EMPLOYER_NON_ADMIN", BROKER_A, EMP_A);
        mvc.perform(get("/api/v1/employer/policies").header("Authorization", hr))
                .andExpect(jsonPath("$.content", hasSize(1))).andExpect(jsonPath("$.content[0].policyNumber", is("LIVE")));
        mvc.perform(get("/api/v1/employer/policies/" + live).header("Authorization", hr)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/employer/policies/" + draft).header("Authorization", hr)).andExpect(status().isNotFound());
        // an HR user of another employer cannot read it
        ref(30, BROKER_A);
        mvc.perform(get("/api/v1/employer/policies/" + live).header("Authorization", bearer("EMPLOYER_ADMIN", BROKER_A, 30L)))
                .andExpect(status().isNotFound());
    }

    @Autowired com.broker.policy.service.PolicyService policyService;

    @Test
    void expirySweepMovesPastDueLivePoliciesToExpired() throws Exception {
        long due = readyPolicy("DUE");
        long notDue = readyPolicy("NOT-DUE");
        for (long id : new long[] {due, notDue}) {
            mvc.perform(post("/api/v1/broker/policies/" + id + "/approve").header("Authorization", admin(BROKER_A))).andExpect(status().isOk());
        }
        jdbc.sql("UPDATE policy_svc.policies SET start_date='2020-01-01', end_date='2020-12-31' WHERE id=:i").param("i", due).update();
        org.assertj.core.api.Assertions.assertThat(policyService.expireDue(java.time.LocalDate.now())).isEqualTo(1);
        mvc.perform(get("/api/v1/broker/policies/" + due).header("Authorization", admin(BROKER_A)))
                .andExpect(jsonPath("$.status", is("EXPIRED")));
        mvc.perform(get("/api/v1/broker/policies/" + notDue).header("Authorization", admin(BROKER_A)))
                .andExpect(jsonPath("$.status", is("APPROVED_AND_LIVE")));
        org.assertj.core.api.Assertions.assertThat(policyService.expireDue(java.time.LocalDate.now())).isZero();   // idempotent
    }

    @Test
    void lookupsAreServed() throws Exception {
        mvc.perform(get("/api/v1/broker/lookups/premium-types").header("Authorization", admin(BROKER_A)))
                .andExpect(jsonPath("$", hasSize(32)));
        mvc.perform(get("/api/v1/broker/lookups/suminsured-subtypes").header("Authorization", admin(BROKER_A)))
                .andExpect(jsonPath("$", hasSize(31)));
        mvc.perform(get("/api/v1/broker/lookups/relation-types").header("Authorization", admin(BROKER_A)))
                .andExpect(jsonPath("$", hasSize(25)));
    }

    @Test
    void legacyIdsTranslateAndEveryMappedIdExists() throws Exception {
        mvc.perform(get("/api/v1/broker/lookups/legacy-ids?kind=RELATION").header("Authorization", admin(BROKER_A)))
                .andExpect(jsonPath("$", hasSize(18)))
                .andExpect(jsonPath("$[?(@.legacyCode=='DAUGHTER')].newId", contains(9)))       // legacy 3 is a daughter; here 3 is a husband
                .andExpect(jsonPath("$[?(@.legacyId==3)].legacyCode", contains("DAUGHTER")));
        mvc.perform(get("/api/v1/broker/lookups/legacy-ids?kind=PREMIUM_TYPE").header("Authorization", admin(BROKER_A)))
                .andExpect(jsonPath("$", hasSize(29)))
                .andExpect(jsonPath("$[?(@.legacyId==31)].status", contains("UNMAPPED")));
        mvc.perform(get("/api/v1/broker/lookups/legacy-ids?kind=SUMINSURED_SUBTYPE").header("Authorization", admin(BROKER_A)))
                .andExpect(jsonPath("$", hasSize(26)));
        mvc.perform(get("/api/v1/broker/lookups/legacy-ids?kind=NOPE").header("Authorization", admin(BROKER_A))).andExpect(status().isBadRequest());
        // no dangling targets: every new_id names a real row of its lookup table
        var orphans = jdbc.sql("""
                SELECT count(*) FROM policy_svc.legacy_id_map m WHERE m.new_id IS NOT NULL AND NOT EXISTS (
                  SELECT 1 FROM policy_svc.relation_types r WHERE m.kind = 'RELATION' AND r.id = m.new_id
                  UNION ALL SELECT 1 FROM policy_svc.premium_types p WHERE m.kind = 'PREMIUM_TYPE' AND p.id = m.new_id
                  UNION ALL SELECT 1 FROM policy_svc.suminsured_subtypes s WHERE m.kind = 'SUMINSURED_SUBTYPE' AND s.id = m.new_id)""")
                .query(Long.class).single();
        org.assertj.core.api.Assertions.assertThat(orphans).isZero();
    }
}
