package com.broker.policy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "gateway.secret=unit-test-secret")
class GatewaySecretTest extends IntegrationTestBase {

    private static String hmac(String sid) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec("unit-test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder sb = new StringBuilder();
        for (byte b : m.doFinal(sid.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Test
    void aValidTokenAloneIsNotEnoughOnlyTheBffGetsThrough() throws Exception {
        String url = "/api/v1/broker/policies";
        mvc.perform(get(url).header("Authorization", admin(BROKER_A))).andExpect(status().isForbidden());
        mvc.perform(get(url).header("Authorization", admin(BROKER_A)).header("X-Gateway-Secret", "wrong")
                .header("X-Session-Id", "s1").header("X-Session-Key", hmac("s1"))).andExpect(status().isForbidden());
        mvc.perform(get(url).header("Authorization", admin(BROKER_A)).header("X-Gateway-Secret", "unit-test-secret")
                .header("X-Session-Id", "s1").header("X-Session-Key", hmac("other"))).andExpect(status().isForbidden());
        mvc.perform(get(url).header("Authorization", admin(BROKER_A)).header("X-Gateway-Secret", "unit-test-secret")
                .header("X-Session-Id", "s1").header("X-Session-Key", hmac("s1"))).andExpect(status().isOk());
    }
}
