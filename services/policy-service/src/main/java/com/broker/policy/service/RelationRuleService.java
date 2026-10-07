package com.broker.policy.service;

import com.broker.policy.api.NotFoundException;
import com.broker.policy.api.ValidationFailedException;
import com.broker.policy.api.ValidationFailedException.Issue;
import com.broker.policy.domain.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The per-relation rules of a policy. `put` replaces the whole set atomically, so a policy never has a half-edited rule set. */
@Service
public class RelationRuleService {
    private final PolicyRepository policies;
    private final RelationRuleRepository rules;
    private final RelationTypeRepository relations;

    RelationRuleService(PolicyRepository policies, RelationRuleRepository rules, RelationTypeRepository relations) {
        this.policies = policies; this.rules = rules; this.relations = relations;
    }

    @Transactional(readOnly = true)
    public List<RelationRule> get(long brokerId, long policyId) {
        policies.findByIdAndBrokerId(policyId, brokerId).orElseThrow(() -> new NotFoundException("Policy not found"));
        return rules.findByPolicyIdOrderByRelationCode(policyId);
    }

    @Transactional
    public List<RelationRule> put(long brokerId, long policyId, List<RelationRule> in) {
        policies.findByIdAndBrokerId(policyId, brokerId).orElseThrow(() -> new NotFoundException("Policy not found"));
        Set<String> known = relations.findAll().stream().map(RelationType::getCode).collect(Collectors.toSet());
        List<Issue> bad = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RelationRule r : in) {
            if (!known.contains(r.getRelationCode())) bad.add(new Issue("UNKNOWN_RELATION", "Unknown relation '" + r.getRelationCode() + "'"));
            if (!seen.add(r.getRelationCode())) bad.add(new Issue("DUPLICATE_RELATION", "Relation '" + r.getRelationCode() + "' appears twice"));
            if (r.getMinAge() != null && r.getMaxAge() != null && r.getMinAge() > r.getMaxAge())
                bad.add(new Issue("AGE_RANGE", r.getRelationCode() + ": min_age is greater than max_age"));
            if ((r.getAgeDifference() == null) != (r.getDifferenceFromRelation() == null))
                bad.add(new Issue("AGE_DIFFERENCE", r.getRelationCode() + ": age_difference and difference_from_relation go together"));
            if (r.getDifferenceFromRelation() != null && !Set.of("SELF", "SPOUSE").contains(r.getDifferenceFromRelation()))
                bad.add(new Issue("AGE_DIFFERENCE", r.getRelationCode() + ": difference_from_relation must be SELF or SPOUSE"));
            if (r.getMaxTwins() != null && r.getMaxTwins() > 0 && !"CHILD".equals(groupOf(r.getRelationCode())))
                bad.add(new Issue("TWINS_ONLY_FOR_CHILDREN", r.getRelationCode() + ": max_twins applies to children only"));
        }
        if (!bad.isEmpty()) throw new ValidationFailedException(bad);
        rules.deleteByPolicyId(policyId);
        rules.flush();
        return rules.saveAll(in.stream().map(r -> {
            RelationRule n = new RelationRule(policyId, brokerId, r.getRelationCode());
            n.setMinAge(r.getMinAge()); n.setMaxAge(r.getMaxAge()); n.setAgeDifference(r.getAgeDifference());
            n.setDifferenceFromRelation(r.getDifferenceFromRelation()); n.setProRataType(r.getProRataType()); n.setMaxTwins(r.getMaxTwins());
            return n;
        }).toList());
    }

    private String groupOf(String code) {
        return relations.findAll().stream().filter(x -> x.getCode().equals(code)).map(RelationType::getRelationGroup).findFirst().orElse("");
    }

    /** The pro-rata kind that applies to a relation: NONE when the policy forbids pro-rata, else the relation's own kind, else the policy's. */
    public static ProRataType effectiveProRata(ProRataType policyKind, RelationRule rule) {
        if (policyKind == ProRataType.NONE) return ProRataType.NONE;
        return rule != null && rule.getProRataType() != null ? rule.getProRataType() : policyKind;
    }
}
