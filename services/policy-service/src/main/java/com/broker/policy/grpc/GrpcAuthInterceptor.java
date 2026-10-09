package com.broker.policy.grpc;

import com.broker.policy.security.TenantContext;
import io.grpc.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Validates the same RS256 access tokens as the REST side (same JwtDecoder bean: issuer + access-only).
 * Allowed callers: SYSTEM service accounts (tenant named per request). Nothing a person holds gets in: a broker user's token works on the REST API behind the BFF,
 * not here (app.grpc.system-only, default true; only the test suite turns it off to exercise per-broker tenancy).
 * Employer and TPA tokens are rejected: this API is internal.
 */
@Component
public class GrpcAuthInterceptor implements ServerInterceptor {
    public static final Context.Key<TenantContext> TENANT = Context.key("tenant");
    private static final Metadata.Key<String> AUTH = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final JwtDecoder decoder;
    private final boolean systemOnly;

    GrpcAuthInterceptor(JwtDecoder decoder, @org.springframework.beans.factory.annotation.Value("${app.grpc.system-only:true}") boolean systemOnly) {
        this.decoder = decoder;
        this.systemOnly = systemOnly;
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                       ServerCallHandler<Q, R> next) {
        String h = headers.get(AUTH);
        if (h == null || !h.regionMatches(true, 0, "Bearer ", 0, 7)) return reject(call, Status.UNAUTHENTICATED, "missing bearer token");
        Jwt jwt;
        try {
            jwt = decoder.decode(h.substring(7).trim());
        } catch (JwtException e) {
            return reject(call, Status.UNAUTHENTICATED, "invalid token");
        }
        String role = jwt.getClaimAsString("role");
        if (!"SYSTEM".equals(role) && (systemOnly || role == null || !role.startsWith("BROKER_"))) {
            return reject(call, Status.PERMISSION_DENIED, "internal API: service accounts only");
        }
        TenantContext t = new TenantContext(String.valueOf((Object) jwt.getClaim("user_id")), role,
                asLong(jwt.getClaim("broker_id")), asLong(jwt.getClaim("employer_id")), asLong(jwt.getClaim("tpa_id")));
        return Contexts.interceptCall(Context.current().withValue(TENANT, t), call, headers, next);
    }

    private static <Q, R> ServerCall.Listener<Q> reject(ServerCall<Q, R> call, Status s, String msg) {
        call.close(s.withDescription(msg), new Metadata());
        return new ServerCall.Listener<>() {};
    }

    private static Long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }
}
