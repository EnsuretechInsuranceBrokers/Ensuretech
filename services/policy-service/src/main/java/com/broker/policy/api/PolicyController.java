package com.broker.policy.api;

import com.broker.policy.api.Dtos.*;
import com.broker.policy.domain.*;
import com.broker.policy.engine.Member;
import com.broker.policy.security.TenantContextHolder;
import com.broker.policy.service.*;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Broker gateway. broker_id always comes from the token; writes need BROKER_ADMIN. */
@RestController
@RequestMapping("/api/v1/broker/policies")
public class PolicyController {
    private final PolicyService policies;
    private final RateCardService rateCards;
    private final FamilyConstructService families;
    private final PremiumService premiums;
    private final PolicyReadinessService readiness;
    private final RelationRuleService relationRules;
    private final ContributionService contributions;
    private final SheetSpecService sheetSpecs;
    private final BigDecimal defaultTaxRate;

    PolicyController(PolicyService policies, RateCardService rateCards, FamilyConstructService families,
                     PremiumService premiums, PolicyReadinessService readiness,
                     RelationRuleService relationRules, ContributionService contributions, SheetSpecService sheetSpecs,
                     @Value("${app.default-tax-rate:18.00}") BigDecimal defaultTaxRate) {
        this.policies = policies;
        this.rateCards = rateCards;
        this.families = families;
        this.premiums = premiums;
        this.readiness = readiness;
        this.relationRules = relationRules;
        this.contributions = contributions;
        this.sheetSpecs = sheetSpecs;
        this.defaultTaxRate = defaultTaxRate;
    }

    private static long broker() { return TenantContextHolder.requireBrokerId(); }

    @PostMapping
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    ResponseEntity<PolicyResponse> create(@Valid @RequestBody CreatePolicyRequest r) {
        Policy p = new Policy(broker(), r.employerId(), r.policyNumber(), r.premiumTypeId());
        p.setInsurerId(r.insurerId());
        p.setTpaId(r.tpaId());
        p.setPolicyType(r.policyType());
        p.setPolicySubType(r.policySubType());
        p.setSuminsuredSubtypeId(r.suminsuredSubtypeId());
        p.setRaterTypeId(r.raterTypeId());
        p.setStartDate(r.startDate());
        p.setEndDate(r.endDate());
        p.setRenewalDate(r.renewalDate());
        p.setMaxMembers(r.maxMembers());
        p.setInception(Boolean.TRUE.equals(r.inception()));
        if (r.proRataType() != null) p.setProRataType(r.proRataType());
        Policy saved = policies.create(broker(), p);
        return ResponseEntity.created(URI.create("/api/v1/broker/policies/" + saved.getId()))
                .body(PolicyResponse.of(saved));
    }

    @GetMapping
    Page<PolicyResponse> list(@RequestParam(required = false) Long employerId,
                              @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return policies.list(broker(), employerId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200), Sort.by("id")))
                .map(PolicyResponse::of);
    }

    @GetMapping("/{id}")
    PolicyResponse get(@PathVariable long id) { return PolicyResponse.of(policies.get(broker(), id)); }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    PolicyResponse approve(@PathVariable long id) { return PolicyResponse.of(policies.approve(broker(), id)); }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    PolicyResponse deactivate(@PathVariable long id) { return PolicyResponse.of(policies.deactivate(broker(), id)); }

    @GetMapping("/{id}/readiness")
    IssuesResponse readiness(@PathVariable long id) {
        return IssuesResponse.of(readiness.check(policies.get(broker(), id)));
    }

    @PutMapping("/{id}/rate-card")
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    RateCardResponse putRateCard(@PathVariable long id, @Valid @RequestBody PutRateCardRequest r) {
        BigDecimal tax = r.taxRate() != null ? r.taxRate() : defaultTaxRate;
        return RateCardResponse.of(rateCards.replace(broker(), id, r.effectiveFrom(), tax, r.rateTable()));
    }

    @GetMapping("/{id}/rate-card")
    RateCardResponse getRateCard(@PathVariable long id) {
        policies.get(broker(), id);
        return RateCardResponse.of(rateCards.getActive(broker(), id));
    }

    @PutMapping("/{id}/family-construct")
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    FamilyConstructResponse putConstruct(@PathVariable long id, @Valid @RequestBody FamilyConstructRequest r) {
        FamilyConstruct c = new FamilyConstruct(id, broker());
        c.setAllowedRelations(r.allowedRelations());
        c.setMaxMembers(r.maxMembers());
        c.setMaxChildren(r.maxChildren());
        c.setMaxParents(r.maxParents());
        c.setIncludeParents(Boolean.TRUE.equals(r.includeParents()));
        c.setIncludeParentsInLaw(Boolean.TRUE.equals(r.includeParentsInLaw()));
        c.setAgeMin(r.ageMin());
        c.setAgeMax(r.ageMax());
        return FamilyConstructResponse.of(families.put(broker(), id, c));
    }

    @GetMapping("/{id}/family-construct")
    FamilyConstructResponse getConstruct(@PathVariable long id) {
        policies.get(broker(), id);
        return FamilyConstructResponse.of(families.get(broker(), id));
    }

    @PutMapping("/{id}/relation-rules")
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    List<RelationRuleDto> putRelationRules(@PathVariable long id, @Valid @RequestBody PutRelationRulesRequest r) {
        return relationRules.put(broker(), id, r.rules().stream().map(RelationRuleDto::toEntity).toList())
                .stream().map(RelationRuleDto::of).toList();
    }

    @GetMapping("/{id}/relation-rules")
    List<RelationRuleDto> getRelationRules(@PathVariable long id) {
        return relationRules.get(broker(), id).stream().map(RelationRuleDto::of).toList();
    }

    @PutMapping("/{id}/contributions")
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    ContributionDto putContributions(@PathVariable long id, @Valid @RequestBody ContributionDto r) {
        ContributionPlan.Type type;
        try {
            type = ContributionPlan.Type.valueOf(r.type());
        } catch (IllegalArgumentException e) {
            type = null;
        }
        return ContributionDto.of(contributions.put(broker(), id, type, r.groups()));
    }

    @GetMapping("/{id}/contributions")
    ContributionDto getContributions(@PathVariable long id) {
        return ContributionDto.of(contributions.get(broker(), id));
    }

    @GetMapping("/{id}/sheet-spec")
    Map<String, Object> sheetSpec(@PathVariable long id) {
        return sheetSpecs.build(policies.get(broker(), id));
    }

    @PostMapping("/{id}/family/validate")
    IssuesResponse validateFamily(@PathVariable long id, @Valid @RequestBody FamilyValidateRequest r) {
        policies.get(broker(), id);
        return IssuesResponse.of(families.validate(broker(), id,
                r.members().stream().map(m -> new FamilyConstructService.Person(m.relationCode(), m.age(), m.dob())).toList()));
    }

    @PostMapping("/{id}/premium/quote")
    QuoteResponse quote(@PathVariable long id, @Valid @RequestBody QuoteRequest r) {
        List<Member> members = r.members().stream().map(m -> new Member(m.ref(), m.relationCode(), m.age(),
                m.grade(), m.designation(), m.sumInsured(), m.layers())).toList();
        return QuoteResponse.of(premiums.quote(broker(), id, members));
    }
}
