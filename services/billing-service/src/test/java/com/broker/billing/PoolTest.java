package com.broker.billing;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.broker.billing.domain.LedgerEntry.ReferenceType;
import java.math.BigDecimal;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/** Shared deposit accounts: GROUP / COMPANY / MULTIPLE (legacy CDAccountType). */
class PoolTest extends IntegrationTestBase {
    static final String POOLS = "/api/v1/broker/cd/pools";

    private long create(String body) throws Exception {
        String res = mvc.perform(json(post(POOLS), admin(BROKER_A), body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(res, "$.account.accountId")).longValue();
    }

    private void assertPoolInvariants(long id) {
        BigDecimal balance = one("SELECT balance FROM billing.cd_accounts WHERE id = ?", id);
        BigDecimal sum = one("SELECT COALESCE(SUM(CASE entry_type WHEN 'CREDIT' THEN amount ELSE -amount END), 0) FROM billing.cd_ledger WHERE account_id = ?", id);
        BigDecimal reserved = one("SELECT reserved FROM billing.cd_accounts WHERE id = ?", id);
        BigDecimal held = one("SELECT COALESCE(SUM(amount), 0) FROM billing.cd_reservations WHERE account_id = ? AND status = 'HELD'", id);
        Assertions.assertThat(balance).isEqualByComparingTo(sum);
        Assertions.assertThat(reserved).isEqualByComparingTo(held);
    }

    @Test
    void oneGroupBalanceFundsEveryMemberPolicyAndNeverMoreThanItHolds() throws Exception {
        long pool = create("{\"type\":\"GROUP\",\"name\":\"Plant A\",\"employerId\":10,\"policyIds\":[1,2,3]}");
        ledger.credit(BROKER_A, 1, d("1000.00"), ReferenceType.DEPOSIT, "UTR-1", "seed", "t");
        for (long p : new long[] {1, 2, 3}) {
            mvc.perform(get("/api/v1/broker/cd/accounts/" + p).header("Authorization", admin(BROKER_A)))
                    .andExpect(jsonPath("$.balance", is(1000.00))).andExpect(jsonPath("$.accountType", is("GROUP")))
                    .andExpect(jsonPath("$.policyId", is((int) p))).andExpect(jsonPath("$.accountId", is((int) pool)));
        }
        Assertions.assertThat(ledger.check(BROKER_A, 2, UUID.randomUUID(), d("700.00")).status()).isEqualTo("SUFFICIENT");
        var other = ledger.check(BROKER_A, 3, UUID.randomUUID(), d("700.00"));                 // 300 left for the whole pool
        Assertions.assertThat(other.status()).isEqualTo("PARTIAL_SUFFICIENT");
        Assertions.assertThat(other.deficit()).isEqualByComparingTo("400.00");
        ledger.commitEndorsement(UUID.randomUUID(), BROKER_A, 10, 1, UUID.randomUUID(), d("200.00"));
        mvc.perform(get("/api/v1/broker/cd/accounts/3").header("Authorization", admin(BROKER_A)))
                .andExpect(jsonPath("$.balance", is(800.00))).andExpect(jsonPath("$.reserved", is(700.00)));
        assertPoolInvariants(pool);
        Assertions.assertThat(one("SELECT count(*) FROM billing.cd_accounts")).isEqualByComparingTo("1");   // no per-policy accounts were made
    }

    @Test
    void policyApprovedForAnEmployerWithACompanyPoolJoinsItInsteadOfOpeningItsOwnAccount() throws Exception {
        long pool = create("{\"type\":\"COMPANY\",\"name\":\"Acme\",\"employerId\":10}");
        ledger.openAccount(BROKER_A, 10, 41);
        ledger.openAccount(BROKER_A, 10, 42);
        ledger.openAccount(BROKER_A, 20, 51);                                                  // another employer: own account
        Assertions.assertThat(one("SELECT count(*) FROM billing.cd_account_policies WHERE account_id = ?", pool)).isEqualByComparingTo("2");
        Assertions.assertThat(one("SELECT count(*) FROM billing.cd_accounts WHERE account_type = 'POLICY'")).isEqualByComparingTo("1");
        ledger.credit(BROKER_A, 41, d("500.00"), ReferenceType.DEPOSIT, "R", "n", "t");
        Assertions.assertThat(ledger.check(BROKER_A, 42, UUID.randomUUID(), d("500.00")).status()).isEqualTo("SUFFICIENT");
        mvc.perform(json(post(POOLS), admin(BROKER_A), "{\"type\":\"COMPANY\",\"name\":\"Again\",\"employerId\":10}")).andExpect(status().isConflict());
    }

    @Test
    void aFundedPolicyAccountCannotBeSilentlyMergedButAnEmptyOneCan() throws Exception {
        funded(BROKER_A, EMP_A, 1, "100.00");
        funded(BROKER_A, EMP_A, 2, "0");
        mvc.perform(json(post(POOLS), admin(BROKER_A), "{\"type\":\"GROUP\",\"name\":\"G\",\"employerId\":10,\"policyIds\":[1]}")).andExpect(status().isConflict());
        Assertions.assertThat(one("SELECT count(*) FROM billing.cd_accounts")).isEqualByComparingTo("2");   // the failed create left nothing behind
        long pool = create("{\"type\":\"GROUP\",\"name\":\"G\",\"employerId\":10,\"policyIds\":[2]}");
        Assertions.assertThat(one("SELECT count(*) FROM billing.cd_accounts WHERE policy_id = 2")).isEqualByComparingTo("0");
        mvc.perform(get(POOLS + "/" + pool).header("Authorization", admin(BROKER_A))).andExpect(jsonPath("$.policyIds", contains(2)));
    }

    @Test
    void poolsKeepTenantsAndEmployersApartAndValidateTheirShape() throws Exception {
        long multi = create("{\"type\":\"MULTIPLE\",\"name\":\"All\",\"policyIds\":[1,2]}");
        ledger.credit(BROKER_A, 1, d("50.00"), ReferenceType.DEPOSIT, "m", "n", "t");
        mvc.perform(get(POOLS + "/" + multi).header("Authorization", admin(BROKER_B))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/broker/cd/accounts/1").header("Authorization", admin(BROKER_B))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/employer/cd/accounts/1").header("Authorization", bearer("EMPLOYER_ADMIN", BROKER_A, EMP_A)))
                .andExpect(status().isNotFound());                                              // a cross-employer pool is not an HR user's to see
        mvc.perform(json(post(POOLS), admin(BROKER_A), "{\"type\":\"MULTIPLE\",\"name\":\"x\",\"employerId\":10}")).andExpect(status().isBadRequest());
        mvc.perform(json(post(POOLS), admin(BROKER_A), "{\"type\":\"GROUP\",\"name\":\"x\"}")).andExpect(status().isBadRequest());
        mvc.perform(json(post(POOLS), admin(BROKER_A), "{\"type\":\"NOPE\",\"name\":\"x\"}")).andExpect(status().isBadRequest());
        mvc.perform(json(post(POOLS), bearer("BROKER_NON_ADMIN", BROKER_A, null), "{\"type\":\"MULTIPLE\",\"name\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(json(post(POOLS + "/" + multi + "/policies"), admin(BROKER_B), "{\"policyIds\":[9]}")).andExpect(status().isNotFound());
        mvc.perform(json(post(POOLS), admin(BROKER_B), "{\"type\":\"MULTIPLE\",\"name\":\"b\",\"policyIds\":[1]}")).andExpect(status().isConflict());   // policy 1 is broker A's
    }

    @Test
    void membersCanBeAddedAndRemovedExceptFromCompanyPoolsOrWhileMoneyIsHeld() throws Exception {
        long pool = create("{\"type\":\"GROUP\",\"name\":\"G\",\"employerId\":10,\"policyIds\":[1]}");
        mvc.perform(json(post(POOLS + "/" + pool + "/policies"), admin(BROKER_A), "{\"policyIds\":[2,3]}")).andExpect(jsonPath("$.policyIds", contains(1, 2, 3)));
        ledger.credit(BROKER_A, 1, d("100.00"), ReferenceType.DEPOSIT, "r", "n", "t");
        ledger.check(BROKER_A, 2, UUID.randomUUID(), d("40.00"));
        mvc.perform(delete(POOLS + "/" + pool + "/policies/3").header("Authorization", admin(BROKER_A))).andExpect(status().isConflict());   // money held
        ledger.releaseExpired(java.time.Instant.now().plusSeconds(3600L * 48));
        mvc.perform(delete(POOLS + "/" + pool + "/policies/3").header("Authorization", admin(BROKER_A))).andExpect(jsonPath("$.policyIds", contains(1, 2)));
        mvc.perform(delete(POOLS + "/" + pool + "/policies/3").header("Authorization", admin(BROKER_A))).andExpect(status().isNotFound());
        long company = create("{\"type\":\"COMPANY\",\"name\":\"C\",\"employerId\":20}");
        ledger.openAccount(BROKER_A, 20, 90);
        mvc.perform(delete(POOLS + "/" + company + "/policies/90").header("Authorization", admin(BROKER_A))).andExpect(status().isConflict());
        assertPoolInvariants(pool);
    }

    @Test
    void poolEventsCarryTheAccountAndEveryMemberPolicy() throws Exception {
        create("{\"type\":\"GROUP\",\"name\":\"G\",\"employerId\":10,\"policyIds\":[5,6]}");
        ledger.credit(BROKER_A, 6, d("10.00"), ReferenceType.DEPOSIT, "e", "n", "t");
        String payload = jdbc.sql("SELECT payload::text FROM outbox.outbox_events WHERE event_type='CdBalanceUpdated'").query(String.class).single();
        Assertions.assertThat(payload).contains("\"account_type\": \"GROUP\"").contains("\"policy_ids\": [5, 6]");
    }
}
