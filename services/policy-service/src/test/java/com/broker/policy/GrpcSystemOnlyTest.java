package com.broker.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.broker.policy.grpc.GrpcServerLifecycle;
import com.broker.policy.grpc.gen.PolicyRequest;
import com.broker.policy.grpc.gen.PolicyValidationGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** In production the gRPC door opens to service accounts only: a person's token, even a valid broker's, must not get past it. */
@TestPropertySource(properties = "app.grpc.system-only=true")
class GrpcSystemOnlyTest extends IntegrationTestBase {
    @Autowired GrpcServerLifecycle server;
    private ManagedChannel channel;

    @AfterEach
    void close() throws InterruptedException {
        if (channel != null) channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
    }

    private Status.Code call(String bearerHeader) {
        channel = ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
        Metadata md = new Metadata();
        if (bearerHeader != null) md.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), bearerHeader);
        try {
            PolicyValidationGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(md)).withDeadlineAfter(2, TimeUnit.SECONDS)
                    .getPolicy(PolicyRequest.newBuilder().setPolicyId(1).setBrokerId(BROKER_A).build());
            return Status.Code.OK;
        } catch (StatusRuntimeException e) {
            return e.getStatus().getCode();
        }
    }

    @Test
    void onlyAServiceAccountGetsPastTheDoor() {
        assertThat(call(admin(BROKER_A))).isEqualTo(Status.Code.PERMISSION_DENIED);
        assertThat(call(null)).isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(call(bearer("SYSTEM", null, null))).isEqualTo(Status.Code.NOT_FOUND);       // past the door: no such policy
    }
}
