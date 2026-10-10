package com.broker.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.broker.billing.grpc.GrpcServerLifecycle;
import com.broker.billing.grpc.gen.*;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class GrpcTest extends IntegrationTestBase {
    @Autowired GrpcServerLifecycle server;
    private ManagedChannel channel;

    @AfterEach
    void close() throws InterruptedException {
        if (channel != null) channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
    }

    private CdBalanceGrpc.CdBalanceBlockingStub stub(String bearer) {
        channel = ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
        Metadata md = new Metadata();
        if (bearer != null) md.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), bearer);
        return CdBalanceGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(md)).withDeadlineAfter(2, TimeUnit.SECONDS);
    }

    private static CdBalanceRequest req(long policy, long broker, String amount, UUID job) {
        return CdBalanceRequest.newBuilder().setPolicyId(policy).setBrokerId(broker).setRequiredAmount(amount).setJobId(job.toString()).build();
    }

    private static Status.Code code(Runnable r) {
        try {
            r.run();
            return Status.Code.OK;
        } catch (StatusRuntimeException e) {
            return e.getStatus().getCode();
        }
    }

    @Test
    void onlyServiceAccountsMayCall() {
        funded(BROKER_A, EMP_A, 1, "1000.00");
        var r = req(1, BROKER_A, "10.00", UUID.randomUUID());
        assertThat(code(() -> stub(null).checkCdBalance(r))).isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(code(() -> stub("Bearer garbage").checkCdBalance(r))).isEqualTo(Status.Code.UNAUTHENTICATED);
        String refresh = TestTokens.build(TestTokens.KEYS, TestTokens.ISSUER, "refresh", "SYSTEM", null, null, 600);
        assertThat(code(() -> stub("Bearer " + refresh).checkCdBalance(r))).isEqualTo(Status.Code.UNAUTHENTICATED);
        for (String token : new String[] {admin(BROKER_A), bearer("EMPLOYER_ADMIN", BROKER_A, EMP_A), bearer("TPA_USER", null, null)}) {
            assertThat(code(() -> stub(token).checkCdBalance(r))).isEqualTo(Status.Code.PERMISSION_DENIED);
        }
        assertThat(one("SELECT reserved FROM billing.cd_accounts WHERE policy_id = 1")).isEqualByComparingTo("0.00");   // nobody reserved anything
    }

    @Test
    void checkReservesAndReportsExactAmounts() {
        funded(BROKER_A, EMP_A, 1, "1000.00");
        UUID j1 = UUID.randomUUID(), j2 = UUID.randomUUID();
        var s = stub(system());
        var a = s.checkCdBalance(req(1, BROKER_A, "600.00", j1));
        assertThat(a.getStatus()).isEqualTo("SUFFICIENT");
        assertThat(a.getReservedAmount()).isEqualTo("600.00");
        assertThat(a.getAvailableBalance()).isEqualTo("1000.00");
        var b = s.checkCdBalance(req(1, BROKER_A, "600.00", j2));
        assertThat(b.getStatus()).isEqualTo("PARTIAL_SUFFICIENT");
        assertThat(b.getAvailableBalance()).isEqualTo("400.00");
        assertThat(b.getDeficitAmount()).isEqualTo("200.00");
        assertThat(b.getReservedAmount()).isEqualTo("0.00");
        assertThat(s.checkCdBalance(req(1, BROKER_A, "600.00", j1)).getReservedAmount()).isEqualTo("600.00");      // idempotent per job
        assertInvariants(1);
    }

    @Test
    void serviceTokensMustNameTheBrokerAndInputIsValidated() {
        funded(BROKER_A, EMP_A, 1, "1000.00");
        var s = stub(system());
        assertThat(code(() -> s.checkCdBalance(req(1, 0, "1.00", UUID.randomUUID())))).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(code(() -> s.checkCdBalance(req(1, BROKER_A, "abc", UUID.randomUUID())))).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(code(() -> s.checkCdBalance(req(1, BROKER_A, "1e99", UUID.randomUUID())))).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(code(() -> s.checkCdBalance(CdBalanceRequest.newBuilder().setPolicyId(1).setBrokerId(BROKER_A).setRequiredAmount("1").setJobId("not-a-uuid").build())))
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(s.checkCdBalance(req(1, BROKER_B, "1.00", UUID.randomUUID())).getStatus()).isEqualTo("INSUFFICIENT");   // another broker's policy
        assertThat(s.checkCdBalance(req(1, BROKER_A, "-10.00", UUID.randomUUID())).getStatus()).isEqualTo("SUFFICIENT");     // a refund
    }
}
