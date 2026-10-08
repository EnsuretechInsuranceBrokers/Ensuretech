package com.broker.policy.grpc;

import com.broker.policy.api.NotFoundException;
import com.broker.policy.api.ValidationFailedException;
import com.broker.policy.domain.Policy;
import com.broker.policy.domain.PremiumType;
import com.broker.policy.domain.PremiumTypeRepository;
import com.broker.policy.domain.ContributionPlanRepository;
import com.broker.policy.domain.RelationRule;
import com.broker.policy.domain.RelationRuleRepository;
import com.broker.policy.engine.InvalidRateTableException;
import com.broker.policy.engine.Member;
import com.broker.policy.engine.RatingException;
import com.broker.policy.grpc.gen.*;
import com.broker.policy.security.TenantContext;
import com.broker.policy.service.FamilyConstructService;
import com.broker.policy.service.PolicyReadinessService;
import com.broker.policy.service.PolicyService;
import com.broker.policy.service.PremiumService;
import com.broker.policy.service.RateCardService;
import com.broker.policy.service.RateCardView;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class PolicyGrpcService extends PolicyValidationGrpc.PolicyValidationImplBase {
    private final PolicyService policies;
    private final PolicyReadinessService readiness;
    private final RateCardService rateCards;
    private final PremiumService premiums;
    private final PremiumTypeRepository premiumTypes;
    private final FamilyConstructService families;
    private final RelationRuleRepository relationRules;
    private final ContributionPlanRepository contributionPlans;
    private final ObjectMapper mapper;

    PolicyGrpcService(PolicyService policies, PolicyReadinessService readiness, RateCardService rateCards,
                      PremiumService premiums, PremiumTypeRepository premiumTypes, FamilyConstructService families,
                      RelationRuleRepository relationRules, ContributionPlanRepository contributionPlans, ObjectMapper mapper) {
        this.relationRules = relationRules;
        this.contributionPlans = contributionPlans;
        this.policies = policies;
        this.readiness = readiness;
        this.rateCards = rateCards;
        this.premiums = premiums;
        this.premiumTypes = premiumTypes;
        this.families = families;
        this.mapper = mapper;
    }

    /** Broker tokens act on their own tenant (request value ignored); SYSTEM tokens must name the tenant. */
    private static long brokerFor(long requested) {
        TenantContext t = GrpcAuthInterceptor.TENANT.get();
        if (t == null) throw Status.UNAUTHENTICATED.asRuntimeException();
        if ("SYSTEM".equals(t.role())) {
            if (requested <= 0) throw Status.INVALID_ARGUMENT.withDescription("broker_id required for service tokens").asRuntimeException();
            return requested;
        }
        if (t.brokerId() == null) throw Status.PERMISSION_DENIED.withDescription("token has no broker scope").asRuntimeException();
        return t.brokerId();
    }

    private <T> void call(StreamObserver<T> out, Supplier<T> body) {
        try {
            out.onNext(body.get());
            out.onCompleted();
        } catch (StatusRuntimeException e) {
            out.onError(e);
        } catch (NotFoundException e) {
            out.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        } catch (ValidationFailedException e) {
            out.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (InvalidRateTableException | RatingException e) {
            out.onError(Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException());
        } catch (RuntimeException e) {
            out.onError(Status.INTERNAL.withDescription("internal error").asRuntimeException());
        }
    }

    @Override
    public void validatePolicyConfig(PolicyConfigRequest req, StreamObserver<ValidationResult> out) {
        call(out, () -> {
            Policy p = policies.get(brokerFor(req.getBrokerId()), req.getPolicyId());
            var issues = readiness.check(p);
            ValidationResult.Builder b = ValidationResult.newBuilder().setValid(issues.isEmpty());
            issues.forEach(i -> b.addIssues(ValidationIssue.newBuilder().setCode(i.code()).setMessage(i.message()).setMemberIndex(-1)));
            return b.build();
        });
    }

    @Override
    public void getRateCard(RateCardRequest req, StreamObserver<RateCardResponse> out) {
        call(out, () -> {
            long broker = brokerFor(req.getBrokerId());
            Policy p = policies.get(broker, req.getPolicyId());           // tenant check before touching the cache
            RateCardView v = rateCards.getActive(broker, p.getId());
            PremiumType pt = premiumTypes.findById(p.getPremiumTypeId()).orElseThrow();
            return RateCardResponse.newBuilder().setRateCardId(v.id()).setPolicyId(v.policyId())
                    .setEffectiveFrom(v.effectiveFrom().toString()).setTaxRate(v.taxRate().toPlainString())
                    .setRateTableJson(mapper.writeValueAsString(v.table()))
                    .setPremiumTypeId(pt.getId()).setRaterClass(pt.getRaterClass()).build();
        });
    }

    @Override
    public void calculatePremium(PremiumRequest req, StreamObserver<PremiumResponse> out) {
        call(out, () -> {
            long broker = brokerFor(req.getBrokerId());
            List<Member> members = req.getMembersList().stream().map(m -> new Member(m.getMemberRef(),
                    m.getRelationCode(), m.getAge(), emptyToNull(m.getGrade()), emptyToNull(m.getDesignation()),
                    decimal(m.getSumInsured()), m.getLayersList())).toList();
            var q = premiums.quote(broker, req.getPolicyId(), members);
            PremiumResponse.Builder b = PremiumResponse.newBuilder().setSubtotal(q.subtotal().toPlainString())
                    .setTaxRate(q.taxRate().toPlainString()).setTax(q.tax().toPlainString())
                    .setTotal(q.total().toPlainString());
            q.members().forEach(m -> b.addMembers(MemberPremium.newBuilder().setMemberRef(m.memberRef())
                    .setPremium(m.premium().toPlainString())));
            return b.build();
        });
    }

    private static final int MAX_BATCH_FAMILIES = 5000;

    @Override
    public void calculatePremiumBatch(PremiumBatchRequest req, StreamObserver<PremiumBatchResponse> out) {
        call(out, () -> {
            long broker = brokerFor(req.getBrokerId());
            if (req.getFamiliesCount() > MAX_BATCH_FAMILIES) {
                throw Status.INVALID_ARGUMENT.withDescription("at most " + MAX_BATCH_FAMILIES + " families per call").asRuntimeException();
            }
            java.util.Map<String, List<Member>> input = new java.util.LinkedHashMap<>();
            for (FamilyPremiumRequest f : req.getFamiliesList()) {
                List<Member> members = f.getMembersList().stream().map(m -> new Member(m.getMemberRef(),
                        m.getRelationCode(), m.getAge(), emptyToNull(m.getGrade()), emptyToNull(m.getDesignation()),
                        decimal(m.getSumInsured()), m.getLayersList())).toList();
                if (input.put(f.getFamilyRef(), members) != null) {
                    throw Status.INVALID_ARGUMENT.withDescription("duplicate family_ref '" + f.getFamilyRef() + "'").asRuntimeException();
                }
            }
            PremiumBatchResponse.Builder b = PremiumBatchResponse.newBuilder();
            for (PremiumService.FamilyQuote fq : premiums.quoteMany(broker, req.getPolicyId(), input)) {
                FamilyPremiumResult.Builder r = FamilyPremiumResult.newBuilder().setFamilyRef(fq.ref());
                if (fq.error() != null) {
                    r.setError(fq.error());
                } else {
                    var q = fq.quote();
                    PremiumResponse.Builder pr = PremiumResponse.newBuilder().setSubtotal(q.subtotal().toPlainString())
                            .setTaxRate(q.taxRate().toPlainString()).setTax(q.tax().toPlainString())
                            .setTotal(q.total().toPlainString());
                    q.members().forEach(m -> pr.addMembers(MemberPremium.newBuilder().setMemberRef(m.memberRef())
                            .setPremium(m.premium().toPlainString())));
                    r.setResult(pr);
                }
                b.addResults(r);
            }
            return b.build();
        });
    }

    @Override
    public void getPolicy(PolicyRequest req, StreamObserver<PolicyInfo> out) {
        call(out, () -> {
            Policy p = policies.get(brokerFor(req.getBrokerId()), req.getPolicyId());
            PremiumType pt = premiumTypes.findById(p.getPremiumTypeId()).orElseThrow();
            PolicyInfo.Builder info = PolicyInfo.newBuilder().setPolicyId(p.getId()).setEmployerId(p.getEmployerId())
                    .setPolicyNumber(p.getPolicyNumber()).setPolicyType(p.getPolicyType())
                    .setStatus(p.getStatus().name()).setStartDate(p.getStartDate().toString())
                    .setEndDate(p.getEndDate().toString()).setProRataType(p.getProRataType().name())
                    .setMidtermBasis(p.getMidtermBasis()).setPremiumRounding(p.getPremiumRounding()).setRoundingUnit(p.getRoundingUnit()).setIsInception(p.isInception()).setPremiumTypeId(pt.getId()).setPremiumTypeCode(pt.getCode())
                    .setRequiresFamilyConstruct(java.util.Set.of("GMC", "OPD", "WELLNESS").contains(p.getPolicyType()));
            for (RelationRule r : relationRules.findByPolicyIdOrderByRelationCode(p.getId())) {
                RelationRuleInfo.Builder b = RelationRuleInfo.newBuilder().setRelationCode(r.getRelationCode()).setMaxTwins(r.getMaxTwins())
                        .setProRataType(com.broker.policy.service.RelationRuleService.effectiveProRata(p.getProRataType(), r).name());
                if (r.getMinAge() != null) b.setMinAge(r.getMinAge());
                if (r.getMaxAge() != null) b.setMaxAge(r.getMaxAge());
                if (r.getAgeDifference() != null) b.setAgeDifference(r.getAgeDifference()).setDifferenceFromRelation(r.getDifferenceFromRelation());
                info.addRelationRules(b);
            }
            contributionPlans.findByPolicyIdAndBrokerId(p.getId(), p.getBrokerId()).ifPresentOrElse(c -> {
                info.setContributionType(c.getType().name());
                c.getGroups().forEach((k, v) -> info.putContributionGroups(k, v.stripTrailingZeros().toPlainString()));
            }, () -> info.setContributionType("NONE"));
            return info.build();
        });
    }

    @Override
    public void validateFamilies(FamilyValidationRequest req, StreamObserver<FamilyValidationResponse> out) {
        call(out, () -> {
            long broker = brokerFor(req.getBrokerId());
            policies.get(broker, req.getPolicyId());                      // tenant check first
            List<List<FamilyConstructService.Person>> input = req.getFamiliesList().stream()
                    .map(f -> f.getPeopleList().stream()
                            .map(p -> new FamilyConstructService.Person(p.getRelationCode(), p.getAge(), p.getDob().isBlank() ? null : p.getDob())).toList())
                    .toList();
            var results = families.validateMany(broker, req.getPolicyId(), input);
            FamilyValidationResponse.Builder b = FamilyValidationResponse.newBuilder();
            for (int i = 0; i < results.size(); i++) {
                FamilyResult.Builder r = FamilyResult.newBuilder().setFamilyRef(req.getFamilies(i).getFamilyRef());
                results.get(i).forEach(v -> r.addIssues(ValidationIssue.newBuilder().setCode(v.code())
                        .setMessage(v.message()).setMemberIndex(v.memberIndex())));
                b.addResults(r);
            }
            return b.build();
        });
    }

    private static String emptyToNull(String s) { return s == null || s.isBlank() ? null : s; }

    private static BigDecimal decimal(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw Status.INVALID_ARGUMENT.withDescription("sum_insured is not a decimal").asRuntimeException();
        }
    }
}
