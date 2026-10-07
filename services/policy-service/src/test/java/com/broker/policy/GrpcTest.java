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

    @Test
    void getPolicyReturnsFactsAndIsTenantScoped() throws Exception {
        long id = livePolicy(BROKER_A, EMP_A, "P-1");
        PolicyInfo info = stub(admin(BROKER_A)).getPolicy(PolicyRequest.newBuilder().setPolicyId(id).build());
        assertThat(info.getEmployerId()).isEqualTo(EMP_A);
        assertThat(info.getPolicyNumber()).isEqualTo("P-1");
        assertThat(info.getStartDate()).isEqualTo("2030-04-01");
        assertThat(info.getProRataType()).isEqualTo("NONE");
        assertThat(info.getPremiumTypeCode()).isEqualTo("FLAT");
        assertThat(info.getRequiresFamilyConstruct()).isFalse();       // GPA
        assertThat(code(() -> stub(admin(BROKER_B)).getPolicy(PolicyRequest.newBuilder().setPolicyId(id).build())))
                .isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    void validateFamiliesBatchesAndPointsAtTheOffendingMember() throws Exception {
        long id = createPolicy(BROKER_A, EMP_A, "P-GMC", "FLAT", "GMC");
        mvc.perform(json(put("/api/v1/broker/policies/" + id + "/family-construct"), admin(BROKER_A),
                "{\"allowedRelations\":[2,5],\"maxMembers\":3,\"maxChildren\":1}")).andExpect(status().isOk());
        var good = Family.newBuilder().setFamilyRef("E1")
                .addPeople(Person.newBuilder().setRelationCode("SELF").setAge(40))
                .addPeople(Person.newBuilder().setRelationCode("SPOUSE").setAge(38)).build();
        var bad = Family.newBuilder().setFamilyRef("E2")
                .addPeople(Person.newBuilder().setRelationCode("SELF").setAge(40))
                .addPeople(Person.newBuilder().setRelationCode("FATHER").setAge(70))
                .addPeople(Person.newBuilder().setRelationCode("SON").setAge(30)).build();
        var r = stub(admin(BROKER_A)).validateFamilies(FamilyValidationRequest.newBuilder().setPolicyId(id)
                .addFamilies(good).addFamilies(bad).build());
        assertThat(r.getResultsList()).extracting(FamilyResult::getFamilyRef).containsExactly("E1", "E2");
        assertThat(r.getResults(0).getIssuesCount()).isZero();
        var issues = r.getResults(1).getIssuesList();
        assertThat(issues).anySatisfy(i -> {
            assertThat(i.getCode()).isEqualTo("RELATION_NOT_ALLOWED");
            assertThat(i.getMemberIndex()).isEqualTo(1);               // FATHER
        });
        assertThat(issues).anySatisfy(i -> {
            assertThat(i.getCode()).isEqualTo("DEPENDENT_OVER_MAX_AGE");
            assertThat(i.getMemberIndex()).isEqualTo(2);               // the 30-year-old SON
        });
        assertThat(code(() -> stub(admin(BROKER_B)).validateFamilies(FamilyValidationRequest.newBuilder()
                .setPolicyId(id).addFamilies(good).build()))).isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    void batchPricingMatchesSinglePricingAndReportsUnpriceableFamiliesWithoutFailingTheBatch() throws Exception {
        long id = createPolicy(BROKER_A, EMP_A, "P-AGE", "AGE_BAND", "GPA");
        mvc.perform(json(put("/api/v1/broker/policies/" + id + "/rate-card"), admin(BROKER_A),
                "{\"effectiveFrom\":\"2030-04-01\",\"rateTable\":{\"age_bands\":[{\"from\":0,\"to\":60,\"premium\":1000}]}}")).andExpect(status().isOk());
        var young = FamilyPremiumRequest.newBuilder().setFamilyRef("young")
                .addMembers(MemberInput.newBuilder().setMemberRef("a").setRelationCode("SELF").setAge(30))
                .addMembers(MemberInput.newBuilder().setMemberRef("b").setRelationCode("SPOUSE").setAge(28)).build();
        var tooOld = FamilyPremiumRequest.newBuilder().setFamilyRef("old")
                .addMembers(MemberInput.newBuilder().setMemberRef("c").setRelationCode("SELF").setAge(70)).build();
        var weird = FamilyPremiumRequest.newBuilder().setFamilyRef("weird")
                .addMembers(MemberInput.newBuilder().setMemberRef("d").setRelationCode("COUSIN").setAge(30)).build();
        var s = stub(admin(BROKER_A));
        var r = s.calculatePremiumBatch(PremiumBatchRequest.newBuilder().setPolicyId(id)
                .addFamilies(young).addFamilies(tooOld).addFamilies(weird).build());
        assertThat(r.getResultsList()).extracting(FamilyPremiumResult::getFamilyRef).containsExactly("young", "old", "weird");
        var single = s.calculatePremium(PremiumRequest.newBuilder().setPolicyId(id).addAllMembers(young.getMembersList()).build());
        assertThat(r.getResults(0).getError()).isEmpty();
        assertThat(r.getResults(0).getResult()).isEqualTo(single);                       // identical to the one-family RPC
        assertThat(r.getResults(1).getError()).contains("no age band for age 70");
        assertThat(r.getResults(1).hasResult()).isFalse();
        assertThat(r.getResults(2).getError()).contains("unknown relation 'COUSIN'");
    }

    @Test
    void batchPricingGuardsTenantDuplicatesAndSize() throws Exception {
        long id = livePolicy(BROKER_A, EMP_A, "P-1");
        var fam = FamilyPremiumRequest.newBuilder().setFamilyRef("e1")
                .addMembers(MemberInput.newBuilder().setMemberRef("a").setRelationCode("SELF").setAge(30)).build();
        assertThat(code(() -> stub(admin(BROKER_B)).calculatePremiumBatch(PremiumBatchRequest.newBuilder().setPolicyId(id).addFamilies(fam).build())))
                .isEqualTo(Status.Code.NOT_FOUND);
        assertThat(code(() -> stub(admin(BROKER_A)).calculatePremiumBatch(PremiumBatchRequest.newBuilder().setPolicyId(id).addFamilies(fam).addFamilies(fam).build())))
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
        var big = PremiumBatchRequest.newBuilder().setPolicyId(id);
        for (int i = 0; i < 5001; i++) big.addFamilies(FamilyPremiumRequest.newBuilder().setFamilyRef("f" + i).addMembers(fam.getMembers(0)));
        assertThat(code(() -> stub(admin(BROKER_A)).calculatePremiumBatch(big.build()))).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(code(() -> stub(admin(BROKER_A)).calculatePremiumBatch(PremiumBatchRequest.newBuilder().setPolicyId(id).build()))).isEqualTo(Status.Code.OK);
    }

    @Test
    void getPolicyCarriesTheResolvedPerRelationRules() throws Exception {
        long id = livePolicy(BROKER_A, EMP_A, "P-RR");
        jdbc.sql("UPDATE policy_svc.policies SET pro_rata_type='DAILY' WHERE id=:i").param("i", id).update();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/broker/policies/" + id + "/relation-rules")
                .header("Authorization", admin(BROKER_A)).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"rules\":[{\"relationCode\":\"SON\",\"maxAge\":25,\"ageDifference\":18,\"differenceFromRelation\":\"SELF\",\"proRataType\":\"MONTHLY\",\"maxTwins\":1},{\"relationCode\":\"SPOUSE\",\"minAge\":21}]}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        PolicyInfo info = stub(admin(BROKER_A)).getPolicy(PolicyRequest.newBuilder().setPolicyId(id).build());
        assertThat(info.getProRataType()).isEqualTo("DAILY");
        var son = info.getRelationRulesList().stream().filter(r -> r.getRelationCode().equals("SON")).findFirst().orElseThrow();
        assertThat(son.getProRataType()).isEqualTo("MONTHLY");
        assertThat(son.hasMaxAge() && son.getMaxAge() == 25 && !son.hasMinAge()).isTrue();
        assertThat(son.getAgeDifference()).isEqualTo(18);
        assertThat(son.getDifferenceFromRelation()).isEqualTo("SELF");
        var spouse = info.getRelationRulesList().stream().filter(r -> r.getRelationCode().equals("SPOUSE")).findFirst().orElseThrow();
        assertThat(spouse.getProRataType()).isEqualTo("DAILY");                       // no own kind: inherits the policy's
        assertThat(spouse.hasAgeDifference()).isFalse();
        jdbc.sql("UPDATE policy_svc.policies SET pro_rata_type='NONE' WHERE id=:i").param("i", id).update();
        info = stub(admin(BROKER_A)).getPolicy(PolicyRequest.newBuilder().setPolicyId(id).build());
        assertThat(info.getRelationRulesList()).extracting(RelationRuleInfo::getProRataType).containsOnly("NONE");
    }
}
