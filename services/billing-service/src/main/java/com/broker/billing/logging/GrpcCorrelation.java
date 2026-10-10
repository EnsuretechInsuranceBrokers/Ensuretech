package com.broker.billing.logging;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ForwardingServerCall;
import io.grpc.ServerCall;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** gRPC calls carry the id in the x-correlation-id metadata key. gRPC runs a call's callbacks on different threads, so the logging context is set around each one. */
public final class GrpcCorrelation {
    public static final Metadata.Key<String> KEY = Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);

    private static final Logger access = LoggerFactory.getLogger("access");

    private GrpcCorrelation() {}

    /** One access line per gRPC call, like the HTTP ones: method, status, time. Logged when the call closes, inside the call's correlation context. */
    public static <Q, R> ServerCall<Q, R> logged(ServerCall<Q, R> call) {
        long t0 = System.nanoTime();
        return new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override public void close(Status status, Metadata trailers) {
                access.info("gRPC " + call.getMethodDescriptor().getFullMethodName() + " -> " + status.getCode() + " " + (System.nanoTime() - t0) / 1_000_000 + " ms");
                super.close(status, trailers);
            }
        };
    }

    public static <Q> ServerCall.Listener<Q> wrap(ServerCall.Listener<Q> delegate, Metadata headers) {
        String given = headers.get(KEY);
        String id = CorrelationFilter.valid(given) ? given : java.util.UUID.randomUUID().toString();
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
            @Override public void onMessage(Q m) { CorrelationFilter.runWith(id, () -> super.onMessage(m)); }
            @Override public void onHalfClose() { CorrelationFilter.runWith(id, super::onHalfClose); }
            @Override public void onCancel() { CorrelationFilter.runWith(id, super::onCancel); }
            @Override public void onComplete() { CorrelationFilter.runWith(id, super::onComplete); }
            @Override public void onReady() { CorrelationFilter.runWith(id, super::onReady); }
        };
    }
}
