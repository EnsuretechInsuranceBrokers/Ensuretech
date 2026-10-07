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
    private final RelationRuleRepository relationRules;

    FamilyConstructService(PolicyRepository policies, FamilyConstructRepository constructs,
                           RelationTypeRepository relations, RelationRuleRepository relationRules) {
        this.relationRules = relationRules;
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

    private static final Set<String> ADULT_GROUPS = Set.of("SELF", "SPOUSE", "PARENT", "PARENT_IN_LAW");

    /** Legacy rules: the relation's age window, the minimum gap to the employee/spouse, and "adults are at least 18" when no window is set. */
    private static void checkRelationRules(List<Violation> out, int i, Person p, RelationType r, RelationRule rule, List<Person> people,
                                           Map<String, RelationType> byCode) {
        Short min = rule == null ? null : rule.getMinAge(), max = rule == null ? null : rule.getMaxAge();
        if (min == null && ADULT_GROUPS.contains(r.getRelationGroup()) && p.age() < 18) {
            out.add(new Violation("ADULT_UNDER_18", "member " + i + ": " + r.getCode() + " must be at least 18, is " + p.age(), i));
        }
        if (min != null && p.age() < min) {
            out.add(new Violation("RELATION_AGE_BELOW_MIN", "member " + i + ": " + r.getCode() + " age " + p.age() + " is below " + min, i));
        }
        if (max != null && p.age() > max) {
            out.add(new Violation("RELATION_AGE_ABOVE_MAX", "member " + i + ": " + r.getCode() + " age " + p.age() + " is above " + max, i));
        }
        if (rule != null && rule.getAgeDifference() != null) {
            String from = rule.getDifferenceFromRelation();
            for (int j = 0; j < people.size(); j++) {
                RelationType other = byCode.get(people.get(j).relationCode());
                if (j == i || other == null || !(from.equals("SELF") ? "SELF" : "SPOUSE").equals(other.getRelationGroup())) continue;
                if (Math.abs(people.get(j).age() - p.age()) < rule.getAgeDifference()) {
                    out.add(new Violation("AGE_GAP_TOO_SMALL", "member " + i + ": " + r.getCode() + " must be at least " + rule.getAgeDifference()
                            + " years " + (people.get(j).age() >= p.age() ? "younger than" : "older than") + " " + from + " (ages are whole years)", i));
                    break;
                }
            }
        }
    }

    /** A rule violation; memberIndex is the offending person's position in the family, -1 when family-wide. */
    public record Violation(String code, String message, int memberIndex) {
        public Issue toIssue() { return new Issue(code, message); }
    }

    /** Checks one family (employee + dependents) against the policy's construct. Empty list = valid. */
    @Transactional(readOnly = true)
    public List<Issue> validate(long brokerId, long policyId, List<Person> people) {
        return validateMany(brokerId, policyId, List.of(people)).get(0).stream().map(Violation::toIssue).toList();
    }

    /** Validates many families with a single load of the construct and relation tables. Result order = input order. */
    @Transactional(readOnly = true)
    public List<List<Violation>> validateMany(long brokerId, long policyId, List<List<Person>> families) {
        FamilyConstruct c = get(brokerId, policyId);
        Map<String, RelationType> byCode = relations.findAll().stream()
                .collect(Collectors.toMap(RelationType::getCode, r -> r));
        Set<Integer> allowed = new HashSet<>(c.getAllowedRelations());
        Map<String, RelationRule> rules = relationRules.findByPolicyIdOrderByRelationCode(policyId).stream()
                .collect(Collectors.toMap(RelationRule::getRelationCode, r -> r));
        List<List<Violation>> all = new ArrayList<>(families.size());
        for (List<Person> people : families) all.add(check(c, byCode, allowed, rules, people));
        return all;
    }

    private static List<Violation> check(FamilyConstruct c, Map<String, RelationType> byCode, Set<Integer> allowed,
                                         Map<String, RelationRule> rules, List<Person> people) {
        List<Violation> out = new ArrayList<>();
        int children = 0, parents = 0, selves = 0;
        for (int i = 0; i < people.size(); i++) {
            Person p = people.get(i);
            RelationType r = byCode.get(p.relationCode());
            if (r == null) {
                out.add(new Violation("UNKNOWN_RELATION", "member " + i + ": unknown relation '" + p.relationCode() + "'", i));
                continue;
            }
            if ("SELF".equals(r.getCode())) selves++;
            else if (!allowed.contains(r.getId().intValue())) {
                out.add(new Violation("RELATION_NOT_ALLOWED", "member " + i + ": " + r.getCode() + " not covered", i));
            }
            if ("CHILD".equals(r.getRelationGroup())) children++;
            if ("PARENT".equals(r.getRelationGroup()) || "PARENT_IN_LAW".equals(r.getRelationGroup())) parents++;
            if (r.getMaxAge() != null && p.age() > r.getMaxAge()) {
                out.add(new Violation("DEPENDENT_OVER_MAX_AGE",
                        "member " + i + ": " + r.getCode() + " age " + p.age() + " exceeds " + r.getMaxAge(), i));
            }
            checkRelationRules(out, i, p, r, rules.get(r.getCode()), people, byCode);
            if ((c.getAgeMin() != null && p.age() < c.getAgeMin()) || (c.getAgeMax() != null && p.age() > c.getAgeMax())) {
                out.add(new Violation("AGE_OUT_OF_RANGE", "member " + i + ": age " + p.age() + " outside "
                        + c.getAgeMin() + "-" + c.getAgeMax(), i));
            }
        }
        if (selves != 1) out.add(new Violation("SELF_COUNT", "exactly one SELF member is required, got " + selves, -1));
        if (people.size() > c.getMaxMembers()) {
            out.add(new Violation("MAX_MEMBERS_EXCEEDED", people.size() + " members, max " + c.getMaxMembers(), -1));
        }
        if (c.getMaxChildren() != null && children > c.getMaxChildren()) {
            out.add(new Violation("MAX_CHILDREN_EXCEEDED", children + " children, max " + c.getMaxChildren(), -1));
        }
        if (c.getMaxParents() != null && parents > c.getMaxParents()) {
            out.add(new Violation("MAX_PARENTS_EXCEEDED", parents + " parents/in-laws, max " + c.getMaxParents(), -1));
        }
        return out;
    }
}
