package com.broker.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.broker.policy.service.RateCardService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Runs against a real Redis (REDIS_PORT). Verifies TTL, never-stale replacement and tenant safety of the card cache. */
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
    void cardBodyIsServedFromRedisWithOneHourTtl() throws Exception {
        long id = policyWithCard(BROKER_A, EMP_A, "P-1", 1000);
        assertThat(rateCards.getActive(BROKER_A, id).table().get("flat")).isEqualTo(1000);
        assertThat(keys()).hasSize(1);
        String key = keys().iterator().next();
        assertThat(key).startsWith("policy-svc:rateCards::");
        assertThat(redis.getExpire(key)).isBetween(3500L, 3600L);

        // change the row behind the service's back: the cached body (keyed by immutable card id) is served
        jdbc.sql("UPDATE policy_svc.rate_cards SET rate_table='{\"flat\": 5}'::jsonb WHERE policy_id=:p").param("p", id).update();
        assertThat(rateCards.getActive(BROKER_A, id).table().get("flat")).isEqualTo(1000);
    }

    @Test
    void replacingTheCardIsVisibleImmediatelyWithoutAnyEviction() throws Exception {
        long id = policyWithCard(BROKER_A, EMP_A, "P-1", 1000);
        rateCards.getActive(BROKER_A, id);                      // cache card #1
        for (int i = 0; i < 25; i++) {                          // hammer it: a replacement must never read stale
            putCard(BROKER_A, id, 2000 + i);
            assertThat(rateCards.getActive(BROKER_A, id).table().get("flat")).isEqualTo(2000 + i);
            mvc.perform(get("/api/v1/broker/policies/" + id + "/rate-card").header("Authorization", admin(BROKER_A)))
                    .andExpect(jsonPath("$.rateTable.flat", is(2000 + i)));
        }
    }

    @Test
    void aBrokerCanNeverReachAnotherBrokersCard() throws Exception {
        long a = policyWithCard(BROKER_A, EMP_A, "P-1", 1000);
        long b = policyWithCard(BROKER_B, EMP_B, "P-1", 9000);
        assertThat(rateCards.getActive(BROKER_A, a).table().get("flat")).isEqualTo(1000);
        assertThat(rateCards.getActive(BROKER_B, b).table().get("flat")).isEqualTo(9000);
        // even though A's card body is hot in Redis, B asking for A's policy fails at the tenant-checked pointer lookup
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
