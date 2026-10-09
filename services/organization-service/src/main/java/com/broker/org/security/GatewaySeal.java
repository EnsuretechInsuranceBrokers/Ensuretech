package com.broker.org.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Proof that a call came through the BFF (or from a core service that holds the same secret). Every request carries
 *   X-Gateway-Timestamp  unix seconds
 *   X-Gateway-Nonce      one-time value
 *   X-Gateway-Signature  hex HMAC-SHA256(secret, "v1\n" ts "\n" nonce "\n" METHOD "\n" path "\n" query "\n" sha256hex(Authorization header))
 * so a signature is good for one method, path, query and user token, for 60 seconds, once. The secret itself never travels.
 * The same function exists in the BFF (GatewaySeal.php) and the Django services (config/gateway.py): change them together.
 */
public final class GatewaySeal {
    public static final String VERSION = "v1";
    public static final long WINDOW_SECONDS = 60;
    private static final int MAX_NONCES = 200_000;

    private final byte[] secret;
    private final Map<String, Long> seen = new ConcurrentHashMap<>();

    public GatewaySeal(String secret) { this.secret = secret.getBytes(StandardCharsets.UTF_8); }

    public static String canonical(String ts, String nonce, String method, String path, String query, String authorization) {
        return VERSION + "\n" + ts + "\n" + nonce + "\n" + method.toUpperCase() + "\n" + path + "\n" + (query == null ? "" : query) + "\n" + sha256Hex(authorization == null ? "" : authorization);
    }

    public String sign(String ts, String nonce, String method, String path, String query, String authorization) {
        return hmacHex(canonical(ts, nonce, method, path, query, authorization));
    }

    /** True only for a fresh, never-seen, correctly signed request. The nonce is remembered only after the signature checks out, so strangers cannot fill the cache. */
    public boolean verify(String ts, String nonce, String signature, String method, String path, String query, String authorization, long nowSeconds) {
        if (ts == null || nonce == null || signature == null || nonce.length() < 16 || nonce.length() > 64 || !ts.matches("\\d{1,12}")) return false;
        if (Math.abs(nowSeconds - Long.parseLong(ts)) > WINDOW_SECONDS) return false;
        byte[] expected = sign(ts, nonce, method, path, query, authorization).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8))) return false;
        return remember(nonce, nowSeconds);
    }

    private boolean remember(String nonce, long now) {
        if (seen.size() >= MAX_NONCES) {
            for (Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator(); it.hasNext();) if (now - it.next().getValue() > WINDOW_SECONDS * 2) it.remove();
            if (seen.size() >= MAX_NONCES) return false;                        // refuse rather than forget: a replay must never get through
        }
        return seen.putIfAbsent(nonce, now) == null;
    }

    private String hmacHex(String data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(secret, "HmacSHA256"));
            return hex(m.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static String sha256Hex(String s) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
