package com.broker.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.broker.policy.service.RateCardService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Runs against a real Redis (REDIS_PORT). Verifies cache-aside, TTL, post-commit eviction and tenant-keyed entries. */
class RateCardCacheTest extends IntegrationTestBase {
    @Autowired RateCardService rateCards;

    private long policyWithCard(long broker, long employer, String number, int flat) throws Exception {
        long id = createPolicy(broker, employer, number, "FLAT", "GPA");
        putCard(broker, id, flat);
        return id;
    }

    private void putCard(long broker, long id, int flat) throws Exception {
        mvc.perform(json(put("/api/v1/broker/policies/" + id + "/rate-card"), admin(broker),
                "{\"effectiveFrom\":\"2030-04-01\",\"rateTable\":{\"flat\":" + flat + "}}")).andExpect(status().isOk());
    }

    private Set<String> keys() { return redis.keys("policy-svc:*"); }

    @Test
    void secondReadIsServedFromRedisWithOneHourTtl() throws Exception {
        long id = policyWithCard(BROKER_A, EMP_A, "P-1", 1000);
        assertThat(rateCards.getActive(BROKER_A, id).table().get("flat")).isEqualTo(1000);
        assertThat(keys()).hasSize(1).first().asString().contains("1:" + id);
        Long ttl = redis.getExpire(keys().iterator().next());
        assertThat(ttl).isBetween(3500L, 3600L);

        // change the row behind the service's back: a cache hit must still return the old card
        jdbc.sql("UPDATE policy_svc.rate_cards SET rate_table='{\"flat\": 5}'::jsonb WHERE policy_id=:p").param("p", id).update();
        assertThat(rateCards.getActive(BROKER_A, id).table().get("flat")).isEqualTo(1000);
    }

    @Test
    void replacingTheCardEvictsAfterCommitAndReadsFresh() throws Exception {
        long id = policyWithCard(BROKER_A, EMP_A, "P-1", 1000);
        rateCards.getActive(BROKER_A, id);
        assertThat(keys()).hasSize(1);
        putCard(BROKER_A, id, 2222);
        assertThat(keys()).isEmpty();
        mvc.perform(get("/api/v1/broker/policies/" + id + "/rate-card").header("Authorization", admin(BROKER_A)))
                .andExpect(jsonPath("$.rateTable.flat", is(2222)));
    }

    @Test
    void cacheEntriesAreKeyedPerBroker() throws Exception {
        long a = policyWithCard(BROKER_A, EMP_A, "P-1", 1000);
        long b = policyWithCard(BROKER_B, EMP_B, "P-1", 9000);
        assertThat(rateCards.getActive(BROKER_A, a).table().get("flat")).isEqualTo(1000);
        assertThat(rateCards.getActive(BROKER_B, b).table().get("flat")).isEqualTo(9000);
        // broker B asking for broker A's policy id must miss, never be served A's cached card
        assertThat(keys()).anyMatch(k -> k.endsWith("1:" + a)).anyMatch(k -> k.endsWith("2:" + b));
        org.junit.jupiter.api.Assertions.assertThrows(com.broker.policy.api.NotFoundException.class,
                () -> rateCards.getActive(BROKER_B, a));
        mvc.perform(get("/api/v1/broker/policies/" + a + "/rate-card").header("Authorization", admin(BROKER_B)))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingCardIsNotCached() throws Exception {
        long id = createPolicy(BROKER_A, EMP_A, "P-1", "FLAT", "GPA");
        org.junit.jupiter.api.Assertions.assertThrows(com.broker.policy.api.NotFoundException.class,
                () -> rateCards.getActive(BROKER_A, id));
        assertThat(keys()).isEmpty();
        putCard(BROKER_A, id, 700);
        assertThat(rateCards.getActive(BROKER_A, id).table().get("flat")).isEqualTo(700);
    }
}
