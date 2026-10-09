package com.broker.billing.grpc;

import com.broker.billing.security.TenantContext;
import io.grpc.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Same access-token validation as REST, but only SYSTEM service accounts may call: reserving a customer's deposit is a
 * service action. Broker, employer and TPA tokens are refused.
 */
@Component
public class GrpcAuthInterceptor implements ServerInterceptor {
    public static final Context.Key<TenantContext> CALLER = Context.key("caller");
    private static final Metadata.Key<String> AUTH = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final JwtDecoder decoder;
    private final boolean systemOnly;

    GrpcAuthInterceptor(JwtDecoder decoder, @org.springframework.beans.factory.annotation.Value("${app.grpc.system-only:true}") boolean systemOnly) {
        this.decoder = decoder;
        this.systemOnly = systemOnly;
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
        String h = headers.get(AUTH);
        if (h == null || !h.regionMatches(true, 0, "Bearer ", 0, 7)) return reject(call, Status.UNAUTHENTICATED, "missing bearer token");
        Jwt jwt;
        try {
            jwt = decoder.decode(h.substring(7).trim());
        } catch (JwtException e) {
            return reject(call, Status.UNAUTHENTICATED, "invalid token");
        }
        if (!"SYSTEM".equals(jwt.getClaimAsString("role"))) return reject(call, Status.PERMISSION_DENIED, "service accounts only");
        TenantContext c = new TenantContext(String.valueOf((Object) jwt.getClaim("user_id")), "SYSTEM", null, null, null);
        return Contexts.interceptCall(Context.current().withValue(CALLER, c), call, headers, next);
    }

    private static <Q, R> ServerCall.Listener<Q> reject(ServerCall<Q, R> call, Status s, String msg) {
        call.close(s.withDescription(msg), new Metadata());
        return new ServerCall.Listener<>() {};
    }
}
