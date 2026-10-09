package com.broker.policy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.broker.policy.security.GatewaySeal;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@TestPropertySource(properties = "gateway.secret=unit-test-secret-unit-test-secret-12345")
class GatewaySecretTest extends IntegrationTestBase {
    private static final GatewaySeal BFF = new GatewaySeal("unit-test-secret-unit-test-secret-12345");
    private static final String URL = "/api/v1/broker/policies";

    private static long now() { return System.currentTimeMillis() / 1000; }

    private static String nonce() { return java.util.UUID.randomUUID().toString().replace("-", ""); }

    /** What the BFF would send for this call. */
    private static MockHttpServletRequestBuilder signed(MockHttpServletRequestBuilder b, String method, String path, String query, String auth, long ts, String nonce) {
        return b.header("Authorization", auth).header("X-Gateway-Timestamp", String.valueOf(ts)).header("X-Gateway-Nonce", nonce)
                .header("X-Gateway-Signature", BFF.sign(String.valueOf(ts), nonce, method, path, query, auth));
    }

    @Test
    void aValidTokenAloneIsNotEnoughOnlyASignedRequestGetsThrough() throws Exception {
        String auth = admin(BROKER_A);
        mvc.perform(get(URL).header("Authorization", auth)).andExpect(status().isForbidden());                                    // curl / Postman with a stolen token
        mvc.perform(signed(get(URL), "GET", URL, null, auth, now(), nonce())).andExpect(status().isOk());                         // the BFF
        mvc.perform(get(URL).header("Authorization", auth).header("X-Gateway-Secret", "unit-test-secret-unit-test-secret-12345")).andExpect(status().isForbidden());   // the old scheme
    }

    @Test
    void aSignatureIsGoodForOneMethodPathQueryTokenAndOneUse() throws Exception {
        String auth = admin(BROKER_A), other = admin(BROKER_B);
        String n = nonce();
        long ts = now();
        mvc.perform(signed(get(URL), "GET", URL, null, auth, ts, n)).andExpect(status().isOk());
        mvc.perform(signed(get(URL), "GET", URL, null, auth, ts, n)).andExpect(status().isForbidden());                           // replay of a captured request
        String sig = BFF.sign(String.valueOf(ts), nonce(), "GET", URL, null, auth);
        mvc.perform(get(URL).header("Authorization", other).header("X-Gateway-Timestamp", String.valueOf(ts)).header("X-Gateway-Nonce", nonce()).header("X-Gateway-Signature", sig))
                .andExpect(status().isForbidden());                                                                                // somebody else's token under a captured signature
        mvc.perform(signed(get(URL + "/1"), "GET", URL, null, auth, ts, nonce())).andExpect(status().isForbidden());              // signed for another path
        mvc.perform(signed(get(URL).queryParam("page", "9"), "GET", URL, "page=1", auth, ts, nonce())).andExpect(status().isForbidden());   // query changed after signing
        mvc.perform(signed(post(URL), "GET", URL, null, auth, ts, nonce())).andExpect(status().isForbidden());                    // method changed
    }

    @Test
    void oldAndFutureRequestsAreRefused() throws Exception {
        String auth = admin(BROKER_A);
        mvc.perform(signed(get(URL), "GET", URL, null, auth, now() - 120, nonce())).andExpect(status().isForbidden());
        mvc.perform(signed(get(URL), "GET", URL, null, auth, now() + 120, nonce())).andExpect(status().isForbidden());
        mvc.perform(signed(get(URL), "GET", URL, null, auth, now() - 30, nonce())).andExpect(status().isOk());
    }

    @Test
    void aSuperCredentialTokenPassesTheGuardWithoutTheBffButAnOrdinaryUserDoesNot() throws Exception {
        // SYSTEM is the role identity-service gives a service account (its secret is stored hashed there); it is how an operator or another service calls in
        var decoder = (org.springframework.security.oauth2.jwt.JwtDecoder) token -> {
            if (token.startsWith("bad")) throw new org.springframework.security.oauth2.jwt.BadJwtException("no");
            return org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token).header("alg", "none").claim("role", token.startsWith("sys") ? "SYSTEM" : "BROKER_ADMIN").build();
        };
        var filter = new com.broker.policy.security.GatewaySecretFilter("unit-test-secret-unit-test-secret-12345", false, decoder);
        for (String[] c : new String[][] {{"Bearer sys-token", "200"}, {"Bearer user-token", "403"}, {"Bearer bad-token", "403"}, {null, "403"}}) {
            var req = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/v1/broker/policies");
            if (c[0] != null) req.addHeader("Authorization", c[0]);
            var res = new org.springframework.mock.web.MockHttpServletResponse();
            filter.doFilter(req, res, new org.springframework.mock.web.MockFilterChain());
            org.junit.jupiter.api.Assertions.assertEquals(Integer.parseInt(c[1]), res.getStatus(), String.valueOf(c[0]));
        }
    }

    @Test
    void theServiceRefusesToStartWithoutItsSecret() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> new com.broker.policy.security.GatewaySecretFilter("", false, null));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> new com.broker.policy.security.GatewaySecretFilter("short", false, null));
        new com.broker.policy.security.GatewaySecretFilter("", true, null);                                                       // explicit local-development opt-out
    }

    @Test
    void healthChecksStayOpenForTheLoadBalancer() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
