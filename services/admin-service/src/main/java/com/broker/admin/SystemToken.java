package com.broker.admin;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * This console's super credentials: a service account's client id and secret (stored hashed in identity-service), traded for a short-lived SYSTEM token.
 * That token is the only thing the services' lock lets through to their /actuator controls and to /api/v1/ops, so the console holds no standing access beyond it.
 */
@Component
public class SystemToken {
    private final RestClient http = RestClient.create();
    private final String url, id, secret;
    private volatile String token;
    private volatile Instant expires = Instant.EPOCH;

    SystemToken(@Value("${admin.identity.token-url}") String url, @Value("${admin.identity.client-id:}") String id, @Value("${admin.identity.client-secret:}") String secret) {
        this.url = url; this.id = id; this.secret = secret;
    }

    public synchronized String get() {
        if (token != null && Instant.now().isBefore(expires.minusSeconds(60))) return token;
        if (id.isBlank() || secret.isBlank()) throw new IllegalStateException("SERVICE_CLIENT_ID and SERVICE_CLIENT_SECRET are not set: the console cannot get a SYSTEM token");
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials"); form.add("client_id", id); form.add("client_secret", secret);
        JsonNode r = http.post().uri(url).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JsonNode.class);
        token = r.path("access_token").asString();
        expires = Instant.now().plusSeconds(r.path("expires_in").asLong(600));
        return token;
    }
}
