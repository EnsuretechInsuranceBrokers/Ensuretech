package com.broker.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.broker.policy.grpc.GrpcServerLifecycle;
import com.broker.policy.grpc.gen.*;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
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

    private PolicyValidationGrpc.PolicyValidationBlockingStub stub(String bearerHeader) {
        channel = ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
        Metadata md = new Metadata();
        if (bearerHeader != null) md.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), bearerHeader);
        return PolicyValidationGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(md)).withDeadlineAfter(2, TimeUnit.SECONDS);
    }

    private long livePolicy(long broker, long employer, String number) throws Exception {
        long id = createPolicy(broker, employer, number, "FLAT", "GPA");
        mvc.perform(json(put("/api/v1/broker/policies/" + id + "/rate-card"), admin(broker),
                "{\"effectiveFrom\":\"2030-04-01\",\"rateTable\":{\"flat\":1000}}")).andExpect(status().isOk());
        return id;
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
    void rejectsMissingBadAndWrongRoleTokens() throws Exception {
        long id = livePolicy(BROKER_A, EMP_A, "P-1");
        var req = PolicyConfigRequest.newBuilder().setPolicyId(id).build();
        assertThat(code(() -> stub(null).validatePolicyConfig(req))).isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(code(() -> stub("Bearer garbage").validatePolicyConfig(req))).isEqualTo(Status.Code.UNAUTHENTICATED);
        String refresh = TestTokens.build(TestTokens.KEYS, TestTokens.ISSUER, "refresh", "BROKER_ADMIN", BROKER_A, null, 600);
        assertThat(code(() -> stub("Bearer " + refresh).validatePolicyConfig(req))).isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(code(() -> stub(bearer("EMPLOYER_ADMIN", BROKER_A, EMP_A)).validatePolicyConfig(req))).isEqualTo(Status.Code.PERMISSION_DENIED);
        assertThat(code(() -> stub(bearer("TPA_USER", null, null)).validatePolicyConfig(req))).isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    void validatePolicyConfigReportsReadiness() throws Exception {
        long ready = livePolicy(BROKER_A, EMP_A, "P-1");
        long bare = createPolicy(BROKER_A, EMP_A, "P-2", "FLAT", "GMC");
        var s = stub(admin(BROKER_A));
        assertThat(s.validatePolicyConfig(PolicyConfigRequest.newBuilder().setPolicyId(ready).build()).getValid()).isTrue();
        var r = s.validatePolicyConfig(PolicyConfigRequest.newBuilder().setPolicyId(bare).build());
        assertThat(r.getValid()).isFalse();
        assertThat(r.getIssuesList()).extracting(ValidationIssue::getCode).contains("NO_ACTIVE_RATE_CARD", "NO_FAMILY_CONSTRUCT");
    }

    @Test
    void brokerTokenIsScopedToItsOwnTenantAndIgnoresRequestBrokerId() throws Exception {
        long a = livePolicy(BROKER_A, EMP_A, "P-1");
        var asB = stub(admin(BROKER_B));
        // B asks for A's policy, even claiming to be broker A in the request body
        var req = RateCardRequest.newBuilder().setPolicyId(a).setBrokerId(BROKER_A).build();
        assertThat(code(() -> asB.getRateCard(req))).isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    void systemTokenMustNameTheTenant() throws Exception {
        long a = livePolicy(BROKER_A, EMP_A, "P-1");
        var sys = stub(bearer("SYSTEM", null, null));
        assertThat(code(() -> sys.getRateCard(RateCardRequest.newBuilder().setPolicyId(a).build()))).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(code(() -> sys.getRateCard(RateCardRequest.newBuilder().setPolicyId(a).setBrokerId(BROKER_B).build()))).isEqualTo(Status.Code.NOT_FOUND);
        RateCardResponse ok = sys.getRateCard(RateCardRequest.newBuilder().setPolicyId(a).setBrokerId(BROKER_A).build());
        assertThat(ok.getRaterClass()).isEqualTo("FlatRater");
        assertThat(ok.getTaxRate()).isEqualTo("18.00");
        assertThat(ok.getRateTableJson()).contains("\"flat\"");
    }

    @Test
    void calculatePremiumMatchesTheRestQuote() throws Exception {
        long id = livePolicy(BROKER_A, EMP_A, "P-1");
        PremiumResponse r = stub(admin(BROKER_A)).calculatePremium(PremiumRequest.newBuilder().setPolicyId(id)
                .addMembers(MemberInput.newBuilder().setMemberRef("e").setRelationCode("SELF").setAge(30))
                .addMembers(MemberInput.newBuilder().setMemberRef("s").setRelationCode("SPOUSE").setAge(29)).build());
        assertThat(r.getSubtotal()).isEqualTo("2000.00");
        assertThat(r.getTax()).isEqualTo("360.00");
        assertThat(r.getTotal()).isEqualTo("2360.00");
        assertThat(r.getMembersList()).extracting(MemberPremium::getMemberRef).containsExactly("e", "s");
    }

    @Test
    void errorsMapToSensibleStatusCodes() throws Exception {
        long id = livePolicy(BROKER_A, EMP_A, "P-1");
        var s = stub(admin(BROKER_A));
        assertThat(code(() -> s.calculatePremium(PremiumRequest.newBuilder().setPolicyId(id)
                .addMembers(MemberInput.newBuilder().setMemberRef("x").setRelationCode("NOPE").setAge(30)).build())))
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(code(() -> s.calculatePremium(PremiumRequest.newBuilder().setPolicyId(id)
                .addMembers(MemberInput.newBuilder().setMemberRef("x").setRelationCode("SELF").setAge(30).setSumInsured("abc")).build())))
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
        long noCard = createPolicy(BROKER_A, EMP_A, "P-2", "FLAT", "GPA");
        assertThat(code(() -> s.getRateCard(RateCardRequest.newBuilder().setPolicyId(noCard).build()))).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(code(() -> s.validatePolicyConfig(PolicyConfigRequest.newBuilder().setPolicyId(99999).build()))).isEqualTo(Status.Code.NOT_FOUND);
    }
}
