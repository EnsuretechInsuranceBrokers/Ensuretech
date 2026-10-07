package com.broker.policy.service;

import com.broker.policy.api.NotFoundException;
import com.broker.policy.api.ValidationFailedException;
import com.broker.policy.api.ValidationFailedException.Issue;
import com.broker.policy.domain.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FamilyConstructService {
    public record Person(String relationCode, int age) {}

    private final PolicyRepository policies;
    private final FamilyConstructRepository constructs;
    private final RelationTypeRepository relations;

    FamilyConstructService(PolicyRepository policies, FamilyConstructRepository constructs,
                           RelationTypeRepository relations) {
        this.policies = policies;
        this.constructs = constructs;
        this.relations = relations;
    }

    @Transactional(readOnly = true)
    public FamilyConstruct get(long brokerId, long policyId) {
        return constructs.findByPolicyIdAndBrokerId(policyId, brokerId)
                .orElseThrow(() -> new NotFoundException("No family construct for policy"));
    }

    @Transactional
    public FamilyConstruct put(long brokerId, long policyId, FamilyConstruct in) {
        policies.findByIdAndBrokerId(policyId, brokerId).orElseThrow(() -> new NotFoundException("Policy not found"));
        Map<Short, RelationType> byId = relations.findAll().stream()
                .collect(Collectors.toMap(RelationType::getId, r -> r));
        List<Issue> bad = new ArrayList<>();
        for (Integer id : in.getAllowedRelations()) {
            RelationType r = byId.get(id.shortValue());
            if (r == null) {
                bad.add(new Issue("UNKNOWN_RELATION", "Unknown relation id " + id));
            } else if ("PARENT".equals(r.getRelationGroup()) && !in.isIncludeParents()) {
                bad.add(new Issue("PARENTS_FLAG_MISMATCH", r.getCode() + " allowed but include_parents=false"));
            } else if ("PARENT_IN_LAW".equals(r.getRelationGroup()) && !in.isIncludeParentsInLaw()) {
                bad.add(new Issue("PARENTS_IN_LAW_FLAG_MISMATCH", r.getCode() + " allowed but include_parents_in_law=false"));
            }
        }
        if (in.getAgeMin() != null && in.getAgeMax() != null && in.getAgeMin() > in.getAgeMax()) {
            bad.add(new Issue("AGE_RANGE", "age_min greater than age_max"));
        }
        if (!bad.isEmpty()) throw new ValidationFailedException(bad);

        FamilyConstruct c = constructs.findByPolicyIdAndBrokerId(policyId, brokerId)
                .orElseGet(() -> new FamilyConstruct(policyId, brokerId));
        c.setAllowedRelations(in.getAllowedRelations());
        c.setMaxMembers(in.getMaxMembers());
        c.setMaxChildren(in.getMaxChildren());
        c.setMaxParents(in.getMaxParents());
        c.setIncludeParents(in.isIncludeParents());
        c.setIncludeParentsInLaw(in.isIncludeParentsInLaw());
        c.setAgeMin(in.getAgeMin());
        c.setAgeMax(in.getAgeMax());
        return constructs.saveAndFlush(c);
    }

    /** Checks one family (employee + dependents) against the policy's construct. Empty list = valid. */
    @Transactional(readOnly = true)
    public List<Issue> validate(long brokerId, long policyId, List<Person> people) {
        FamilyConstruct c = get(brokerId, policyId);
        Map<String, RelationType> byCode = relations.findAll().stream()
                .collect(Collectors.toMap(RelationType::getCode, r -> r));
        Set<Integer> allowed = new HashSet<>(c.getAllowedRelations());
        List<Issue> out = new ArrayList<>();
        int children = 0, parents = 0, selves = 0;
        for (int i = 0; i < people.size(); i++) {
            Person p = people.get(i);
            RelationType r = byCode.get(p.relationCode());
            if (r == null) {
                out.add(new Issue("UNKNOWN_RELATION", "member " + i + ": unknown relation '" + p.relationCode() + "'"));
                continue;
            }
            if ("SELF".equals(r.getCode())) selves++;
            else if (!allowed.contains(r.getId().intValue())) {
                out.add(new Issue("RELATION_NOT_ALLOWED", "member " + i + ": " + r.getCode() + " not covered"));
            }
            if ("CHILD".equals(r.getRelationGroup())) children++;
            if ("PARENT".equals(r.getRelationGroup()) || "PARENT_IN_LAW".equals(r.getRelationGroup())) parents++;
            if (r.getMaxAge() != null && p.age() > r.getMaxAge()) {
                out.add(new Issue("DEPENDENT_OVER_MAX_AGE",
                        "member " + i + ": " + r.getCode() + " age " + p.age() + " exceeds " + r.getMaxAge()));
            }
            if ((c.getAgeMin() != null && p.age() < c.getAgeMin()) || (c.getAgeMax() != null && p.age() > c.getAgeMax())) {
                out.add(new Issue("AGE_OUT_OF_RANGE", "member " + i + ": age " + p.age() + " outside "
                        + c.getAgeMin() + "-" + c.getAgeMax()));
            }
        }
        if (selves != 1) out.add(new Issue("SELF_COUNT", "exactly one SELF member is required, got " + selves));
        if (people.size() > c.getMaxMembers()) {
            out.add(new Issue("MAX_MEMBERS_EXCEEDED", people.size() + " members, max " + c.getMaxMembers()));
        }
        if (c.getMaxChildren() != null && children > c.getMaxChildren()) {
            out.add(new Issue("MAX_CHILDREN_EXCEEDED", children + " children, max " + c.getMaxChildren()));
        }
        if (c.getMaxParents() != null && parents > c.getMaxParents()) {
            out.add(new Issue("MAX_PARENTS_EXCEEDED", parents + " parents/in-laws, max " + c.getMaxParents()));
        }
        return out;
    }
}
