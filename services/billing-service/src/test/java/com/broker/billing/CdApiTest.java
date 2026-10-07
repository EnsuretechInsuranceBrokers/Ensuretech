package com.broker.billing;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CdApiTest extends IntegrationTestBase {
    static final String CD = "/api/v1/broker/cd/accounts/";
    static final String ECD = "/api/v1/employer/cd/accounts/";

    private static String credit(String amount, String ref) { return "{\"amount\":" + amount + ",\"reference\":\"" + ref + "\",\"type\":\"DEPOSIT\"}"; }

    @Test
    void accountsStatementsAndRunningBalances() throws Exception {
        funded(BROKER_A, EMP_A, 1, "0");
        mvc.perform(json(post(CD + "1/credits"), admin(BROKER_A), credit("1000.00", "UTR-1"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.balanceAfter", is(1000.00))).andExpect(jsonPath("$.entryType", is("CREDIT")));
        ledger.check(BROKER_A, 1, UUID.randomUUID(), d("300.00"));
        ledger.commitEndorsement(UUID.randomUUID(), BROKER_A, EMP_A, 1, UUID.randomUUID(), d("250.00"));
        mvc.perform(get(CD + "1").header("Authorization", admin(BROKER_A)))
                .andExpect(jsonPath("$.balance", is(750.00))).andExpect(jsonPath("$.reserved", is(300.00)))
                .andExpect(jsonPath("$.available", is(450.00))).andExpect(jsonPath("$.belowThreshold", is(false)));
        mvc.perform(get(CD + "1/statements").header("Authorization", bearer("BROKER_NON_ADMIN", BROKER_A, null)))
                .andExpect(jsonPath("$.content[0].entryType", is("DEBIT"))).andExpect(jsonPath("$.content[0].balanceAfter", is(750.00)))
                .andExpect(jsonPath("$.content[1].balanceAfter", is(1000.00))).andExpect(jsonPath("$.totalElements", is(2)));
        mvc.perform(get("/api/v1/broker/cd/accounts").header("Authorization", admin(BROKER_A))).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void creditReplayIs200WithTheSameEntryAndAMismatchIs409() throws Exception {
        funded(BROKER_A, EMP_A, 1, "0");
        mvc.perform(json(post(CD + "1/credits"), admin(BROKER_A), credit("100.00", "R"))).andExpect(status().isCreated());
        mvc.perform(json(post(CD + "1/credits"), admin(BROKER_A), credit("100.00", "R"))).andExpect(status().isOk());
        mvc.perform(json(post(CD + "1/credits"), admin(BROKER_A), credit("999.00", "R"))).andExpect(status().isConflict());
        mvc.perform(get(CD + "1").header("Authorization", admin(BROKER_A))).andExpect(jsonPath("$.balance", is(100.00)));
        assertInvariants(1);
    }

    @Test
    void moneyInputIsValidatedStrictly() throws Exception {
        funded(BROKER_A, EMP_A, 1, "0");
        for (String bad : new String[] {credit("0", "r"), credit("-5", "r"), credit("10.005", "r"), credit("99999999999999999", "r"),
                "{\"amount\":10,\"reference\":\"\",\"type\":\"DEPOSIT\"}", "{\"amount\":10,\"reference\":\"r\",\"type\":\"DEBIT\"}",
                "{\"reference\":\"r\",\"type\":\"DEPOSIT\"}"}) {
            mvc.perform(json(post(CD + "1/credits"), admin(BROKER_A), bad)).andExpect(status().isBadRequest());
        }
        mvc.perform(get(CD + "1").header("Authorization", admin(BROKER_A))).andExpect(jsonPath("$.balance", is(0.0)));
    }

    @Test
    void tenantsCannotSeeOrTouchEachOthersAccounts() throws Exception {
        funded(BROKER_A, EMP_A, 1, "500.00");
        String b = admin(BROKER_B);
        for (var req : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[] {
                get(CD + "1"), get(CD + "1/statements"), get(CD + "1/replenishments")}) {
            mvc.perform(req.header("Authorization", b)).andExpect(status().isNotFound());
        }
        mvc.perform(json(post(CD + "1/credits"), b, credit("1.00", "x"))).andExpect(status().isNotFound());
        mvc.perform(json(put(CD + "1/threshold"), b, "{\"thresholdAmount\":1}")).andExpect(status().isNotFound());
        mvc.perform(json(post(CD + "1/replenishments"), b, "{\"amount\":5,\"reference\":\"x\"}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/broker/cd/accounts").header("Authorization", b)).andExpect(jsonPath("$", hasSize(0)));
        assertInvariants(1);
    }

    @Test
    void anHrUserSeesOnlyTheirOwnEmployersAccounts() throws Exception {
        funded(BROKER_A, EMP_A, 1, "500.00");
        funded(BROKER_A, EMP_A + 1, 2, "700.00");                                              // same broker, a different employer
        String hr = bearer("EMPLOYER_ADMIN", BROKER_A, EMP_A);
        mvc.perform(get("/api/v1/employer/cd/accounts").header("Authorization", hr)).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].policyId", is(1)));
        mvc.perform(get(ECD + "1").header("Authorization", hr)).andExpect(status().isOk());
        mvc.perform(get(ECD + "2").header("Authorization", hr)).andExpect(status().isNotFound());
        mvc.perform(get(ECD + "2/statements").header("Authorization", hr)).andExpect(status().isNotFound());
        mvc.perform(json(post(ECD + "2/replenishments"), hr, "{\"amount\":5,\"reference\":\"x\"}")).andExpect(status().isNotFound());
        mvc.perform(json(post(ECD + "1/credits"), hr, credit("1", "x"))).andExpect(status().isNotFound());     // employers cannot credit
    }

    @Test
    void rolesAndGatewaysAreEnforced() throws Exception {
        funded(BROKER_A, EMP_A, 1, "0");
        mvc.perform(get(CD + "1")).andExpect(status().isUnauthorized());
        for (String role : new String[] {"BROKER_NON_ADMIN", "BROKER_SPECIALIZER"}) {
            mvc.perform(json(post(CD + "1/credits"), bearer(role, BROKER_A, null), credit("1", "x"))).andExpect(status().isForbidden());
            mvc.perform(json(put(CD + "1/threshold"), bearer(role, BROKER_A, null), "{\"thresholdAmount\":1}")).andExpect(status().isForbidden());
        }
        mvc.perform(get(CD + "1").header("Authorization", bearer("EMPLOYER_ADMIN", BROKER_A, EMP_A))).andExpect(status().isForbidden());
        mvc.perform(get(ECD + "1").header("Authorization", admin(BROKER_A))).andExpect(status().isForbidden());
        mvc.perform(json(post(ECD + "1/replenishments"), bearer("EMPLOYER_NON_ADMIN", BROKER_A, EMP_A), "{\"amount\":5,\"reference\":\"x\"}"))
                .andExpect(status().isForbidden());
        String refresh = TestTokens.build(TestTokens.KEYS, TestTokens.ISSUER, "refresh", "BROKER_ADMIN", BROKER_A, null, 600);
        mvc.perform(get(CD + "1").header("Authorization", "Bearer " + refresh)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/whatever").header("Authorization", system())).andExpect(status().isForbidden());
    }

    @Test
    void replenishmentFlowCreditsExactlyOnceOnApproval() throws Exception {
        funded(BROKER_A, EMP_A, 1, "100.00");
        String hr = bearer("EMPLOYER_ADMIN", BROKER_A, EMP_A);
        String created = mvc.perform(json(post(ECD + "1/replenishments"), hr, "{\"amount\":2000.50,\"reference\":\"UTR-77\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status", is("REQUESTED"))).andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));
        mvc.perform(json(post(ECD + "1/replenishments"), hr, "{\"amount\":5,\"reference\":\"UTR-77\"}")).andExpect(status().isConflict());   // same payment ref
        mvc.perform(get(CD + "1").header("Authorization", admin(BROKER_A))).andExpect(jsonPath("$.balance", is(100.00)));        // not credited yet
        mvc.perform(post(CD + "1/replenishments/" + id + "/approve").header("Authorization", bearer("BROKER_NON_ADMIN", BROKER_A, null))).andExpect(status().isForbidden());
        mvc.perform(post(ECD + "1/replenishments/" + id + "/approve").header("Authorization", hr)).andExpect(status().isNotFound());   // HR cannot approve their own
        mvc.perform(post(CD + "1/replenishments/" + id + "/approve").header("Authorization", admin(BROKER_B))).andExpect(status().isNotFound());
        mvc.perform(post(CD + "1/replenishments/" + id + "/approve").header("Authorization", admin(BROKER_A)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("APPROVED")));
        mvc.perform(post(CD + "1/replenishments/" + id + "/approve").header("Authorization", admin(BROKER_A))).andExpect(status().isConflict());
        mvc.perform(post(CD + "1/replenishments/" + id + "/reject").header("Authorization", admin(BROKER_A))).andExpect(status().isConflict());
        mvc.perform(get(CD + "1").header("Authorization", admin(BROKER_A))).andExpect(jsonPath("$.balance", is(2100.50)));
        mvc.perform(get(ECD + "1/replenishments").header("Authorization", hr)).andExpect(jsonPath("$[0].decidedBy", notNullValue()));
        assertInvariants(1);
    }

    @Test
    void aRejectedRequestMovesNoMoney() throws Exception {
        funded(BROKER_A, EMP_A, 1, "100.00");
        String created = mvc.perform(json(post(CD + "1/replenishments"), admin(BROKER_A), "{\"amount\":500,\"reference\":\"UTR-1\"}"))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));
        mvc.perform(json(post(CD + "1/replenishments/" + id + "/reject"), admin(BROKER_A), "{\"note\":\"payment not received\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("REJECTED"))).andExpect(jsonPath("$.decisionNote", is("payment not received")));
        mvc.perform(post(CD + "1/replenishments/" + id + "/approve").header("Authorization", admin(BROKER_A))).andExpect(status().isConflict());
        mvc.perform(get(CD + "1").header("Authorization", admin(BROKER_A))).andExpect(jsonPath("$.balance", is(100.00)));
    }

    @Test
    void concurrentApprovalsOfOneRequestCreditOnce() throws Exception {
        funded(BROKER_A, EMP_A, 1, "0");
        var req = new com.broker.billing.service.ReplenishmentService[1];
        String created = mvc.perform(json(post(CD + "1/replenishments"), admin(BROKER_A), "{\"amount\":300,\"reference\":\"UTR-9\"}"))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        var go = new java.util.concurrent.CountDownLatch(1);
        var codes = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
        for (int i = 0; i < 8; i++) codes.add(pool.submit(() -> { go.await(); return mvc.perform(post(CD + "1/replenishments/" + id + "/approve")
                .header("Authorization", admin(BROKER_A))).andReturn().getResponse().getStatus(); }));
        go.countDown();
        int ok = 0, conflict = 0;
        for (var f : codes) { int s = f.get(30, java.util.concurrent.TimeUnit.SECONDS); if (s == 200) ok++; else if (s == 409) conflict++; }
        pool.shutdown();
        org.assertj.core.api.Assertions.assertThat(new int[] {ok, conflict}).containsExactly(1, 7);
        mvc.perform(get(CD + "1").header("Authorization", admin(BROKER_A))).andExpect(jsonPath("$.balance", is(300.00)));
        assertInvariants(1);
    }

    @Test
    void thresholdDrivesTheBelowThresholdFlag() throws Exception {
        funded(BROKER_A, EMP_A, 1, "400.00");
        mvc.perform(json(put(CD + "1/threshold"), admin(BROKER_A), "{\"thresholdAmount\":500}")).andExpect(jsonPath("$.belowThreshold", is(true)));
        mvc.perform(json(put(CD + "1/threshold"), admin(BROKER_A), "{\"thresholdAmount\":-1}")).andExpect(status().isBadRequest());
    }
}
