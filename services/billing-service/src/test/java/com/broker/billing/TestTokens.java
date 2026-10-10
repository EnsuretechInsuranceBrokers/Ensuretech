package com.broker.billing;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import java.util.Date;

/** Mints tokens shaped exactly like identity-service (SimpleJWT) output. */
final class TestTokens {
    static final String ISSUER = "ensuretech-identity";
    static final KeyPair KEYS = generate();
    static final KeyPair OTHER_KEYS = generate();
    static final Path PUBLIC_PEM = writePublicPem();

    private TestTokens() {}

    static String token(String role, Long brokerId, Long employerId) {
        return build(KEYS, ISSUER, "access", role, brokerId, employerId, 600);
    }

    static String build(KeyPair kp, String issuer, String tokenType, String role, Long brokerId,
                        Long employerId, long ttlSeconds) {
        try {
            JWTClaimsSet.Builder c = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .claim("token_type", tokenType)
                    .claim("user_id", "00000000-0000-0000-0000-000000000001")
                    .claim("role", role)
                    .issueTime(new Date())
                    .expirationTime(new Date(System.currentTimeMillis() + ttlSeconds * 1000));
            if (brokerId != null) c.claim("broker_id", brokerId);
            if (employerId != null) c.claim("employer_id", employerId);
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), c.build());
            jwt.sign(new RSASSASigner((RSAPrivateKey) kp.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path writePublicPem() {
        try {
            String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(KEYS.getPublic().getEncoded());
            Path p = Files.createTempFile("jwt-public", ".pem");
            Files.writeString(p, "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----\n");
            p.toFile().deleteOnExit();
            return p;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
