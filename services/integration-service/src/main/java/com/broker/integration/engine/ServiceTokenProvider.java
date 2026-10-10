package com.broker.integration.engine;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** OAuth2 client-credentials against identity-service. Cached; refreshed 60 s before expiry; single-flight. */
@Component
public class ServiceTokenProvider {
    private final String url, clientId, secret;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private String token;
    private Instant expiresAt = Instant.EPOCH;

    ServiceTokenProvider(@Value("${app.identity.token-url}") String url, @Value("${app.identity.client-id:}") String clientId,
                         @Value("${app.identity.client-secret:}") String secret, ObjectMapper mapper) {
        this.url = url; this.clientId = clientId; this.secret = secret; this.mapper = mapper;
    }

    public synchronized String get() {
        if (token != null && Instant.now().isBefore(expiresAt.minusSeconds(60))) return token;
        if (clientId.isBlank() || secret.isBlank()) throw new EngineException("service credentials (SERVICE_CLIENT_ID/SECRET) are not configured", false);
        String form = "grant_type=client_credentials&client_id=" + enc(clientId) + "&client_secret=" + enc(secret);
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 401) throw new EngineException("identity-service refused the service credentials", false);
            if (r.statusCode() != 200) throw new EngineException("identity-service answered HTTP " + r.statusCode(), true);
            var n = mapper.readTree(r.body());
            token = n.path("access_token").asString();
            expiresAt = Instant.now().plusSeconds(n.path("expires_in").asLong(0));
            return token;
        } catch (IOException e) {
            throw new EngineException("identity-service unreachable: " + e.getClass().getSimpleName(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EngineException("interrupted", true);
        }
    }

    public synchronized void invalidate(String failed) { if (failed != null && failed.equals(token)) { token = null; expiresAt = Instant.EPOCH; } }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
