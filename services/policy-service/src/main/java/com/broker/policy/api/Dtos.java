package com.broker.policy.api;

import com.broker.policy.api.ValidationFailedException.Issue;
import com.broker.policy.domain.*;
import com.broker.policy.engine.PremiumEngine;
import com.broker.policy.service.RateCardView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class Dtos {
    private Dtos() {}

    public record CreatePolicyRequest(
            @NotNull Long employerId,
            @NotBlank @Size(max = 60) String policyNumber,
            @NotNull Short insurerId,
            Short tpaId,
            @NotNull @Pattern(regexp = "GMC|GPA|GTL|OPD|WELLNESS") String policyType,
            @Size(max = 60) String policySubType,
            @NotNull Short premiumTypeId,
            @NotNull Short suminsuredSubtypeId,
            @NotNull Short raterTypeId,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            LocalDate renewalDate,
            @Positive Integer maxMembers,
            Boolean inception,
            ProRataType proRataType) {}

    public record PolicyResponse(Long id, Long brokerId, Long employerId, String policyNumber, Short insurerId,
                                 Short tpaId, String policyType, String policySubType, Short premiumTypeId,
                                 Short suminsuredSubtypeId, Short raterTypeId, PolicyStatus status,
                                 LocalDate startDate, LocalDate endDate, LocalDate renewalDate, Integer maxMembers,
                                 boolean inception, ProRataType proRataType) {
        public static PolicyResponse of(Policy p) {
            return new PolicyResponse(p.getId(), p.getBrokerId(), p.getEmployerId(), p.getPolicyNumber(),
                    p.getInsurerId(), p.getTpaId(), p.getPolicyType(), p.getPolicySubType(), p.getPremiumTypeId(),
                    p.getSuminsuredSubtypeId(), p.getRaterTypeId(), p.getStatus(), p.getStartDate(), p.getEndDate(),
                    p.getRenewalDate(), p.getMaxMembers(), p.isInception(), p.getProRataType());
        }
    }

    public record PutRateCardRequest(
            @NotNull LocalDate effectiveFrom,
            @DecimalMin("0") @DecimalMax("100") BigDecimal taxRate,
            @NotEmpty Map<String, Object> rateTable) {}

    public record RateCardResponse(Long id, Long policyId, LocalDate effectiveFrom, BigDecimal taxRate,
                                   Map<String, Object> rateTable) {
        public static RateCardResponse of(RateCardView v) {
            return new RateCardResponse(v.id(), v.policyId(), v.effectiveFrom(), v.taxRate(), v.table());
        }
    }

    public record FamilyConstructRequest(
            @NotEmpty List<Integer> allowedRelations,
            @NotNull @Positive Short maxMembers,
            @PositiveOrZero Short maxChildren,
            @PositiveOrZero Short maxParents,
            Boolean includeParents,
            Boolean includeParentsInLaw,
            @PositiveOrZero Short ageMin,
            @PositiveOrZero Short ageMax) {}

    public record FamilyConstructResponse(Long policyId, List<Integer> allowedRelations, Short maxMembers,
                                          Short maxChildren, Short maxParents, boolean includeParents,
                                          boolean includeParentsInLaw, Short ageMin, Short ageMax) {
        public static FamilyConstructResponse of(FamilyConstruct c) {
            return new FamilyConstructResponse(c.getPolicyId(), c.getAllowedRelations(), c.getMaxMembers(),
                    c.getMaxChildren(), c.getMaxParents(), c.isIncludeParents(), c.isIncludeParentsInLaw(),
                    c.getAgeMin(), c.getAgeMax());
        }
    }

    public record RelationRuleDto(
            @NotBlank @Size(max = 40) String relationCode,
            @Min(0) @Max(120) Short minAge,
            @Min(0) @Max(120) Short maxAge,
            @Min(0) @Max(120) Short ageDifference,
            @Pattern(regexp = "SELF|SPOUSE") String differenceFromRelation,
            ProRataType proRataType,
            @Min(0) @Max(5) Short maxTwins) {
        public static RelationRuleDto of(RelationRule r) {
            return new RelationRuleDto(r.getRelationCode(), r.getMinAge(), r.getMaxAge(), r.getAgeDifference(),
                    r.getDifferenceFromRelation(), r.getProRataType(), r.getMaxTwins());
        }

        public RelationRule toEntity() {
            RelationRule r = new RelationRule(null, null, relationCode);
            r.setMinAge(minAge); r.setMaxAge(maxAge); r.setAgeDifference(ageDifference);
            r.setDifferenceFromRelation(differenceFromRelation); r.setProRataType(proRataType); r.setMaxTwins(maxTwins);
            return r;
        }
    }

    public record ContributionDto(@NotBlank String type, java.util.Map<String, java.math.BigDecimal> groups) {
        public static ContributionDto of(ContributionPlan p) { return new ContributionDto(p.getType().name(), p.getGroups()); }
    }

    /** type NONE | FLOOR | CEIL | ROUND; unit is the multiple the charged amount is rounded to (1 = whole rupees). */
    public record RoundingDto(@NotBlank String type, Integer unit) {}

    public record DependantMidtermDto(java.util.Map<String, com.broker.policy.domain.DependantMidtermRules.Rule> rules) {}

    public record MidtermDto(@NotBlank String basis) {}

    public record PutRelationRulesRequest(@NotNull List<@Valid RelationRuleDto> rules) {}

    public record PersonDto(@NotBlank String relationCode, @NotNull @PositiveOrZero Integer age, String dob) {}

    public record FamilyValidateRequest(@NotEmpty List<@Valid PersonDto> members) {}

    public record IssuesResponse(boolean valid, List<Issue> issues) {
        public static IssuesResponse of(List<Issue> issues) { return new IssuesResponse(issues.isEmpty(), issues); }
    }

    public record QuoteMember(@NotBlank String ref, @NotBlank String relationCode, @NotNull @PositiveOrZero Integer age,
                              String grade, String designation, @PositiveOrZero BigDecimal sumInsured,
                              List<String> layers) {}

    public record QuoteRequest(@NotEmpty List<@Valid QuoteMember> members) {}

    public record QuoteResponse(List<PremiumEngine.MemberPremium> members, BigDecimal subtotal, BigDecimal taxRate,
                                BigDecimal tax, BigDecimal total) {
        public static QuoteResponse of(PremiumEngine.Quote q) {
            return new QuoteResponse(q.members(), q.subtotal(), q.taxRate(), q.tax(), q.total());
        }
    }
}
