package com.broker.policy.service;

import com.broker.policy.domain.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A machine-readable description of what a valid endorsement sheet for one policy looks like, built from the policy's own configuration
 * (rate card, family construct, relation rules). The sheet generator and any upload UI use it so test data can never drift from the policy.
 * Schema id: {@code sheet-spec/v1}. Nothing here is guessed: a section the policy has not configured yet is reported under {@code gaps}.
 */
@Service
public class SheetSpecService {
    public static final String SCHEMA = "sheet-spec/v1";
    private final PremiumTypeRepository premiumTypes;
    private final SumInsuredSubtypeRepository suminsured;
    private final RaterTypeRepository raterTypes;
    private final RateCardRepository cardPointers;
    private final RateCardLoader cardLoader;
    private final FamilyConstructRepository constructs;
    private final RelationRuleRepository rules;
    private final RelationTypeRepository relations;

    SheetSpecService(PremiumTypeRepository premiumTypes, SumInsuredSubtypeRepository suminsured, RaterTypeRepository raterTypes,
                     RateCardRepository cardPointers, RateCardLoader cardLoader, FamilyConstructRepository constructs, RelationRuleRepository rules,
                     RelationTypeRepository relations) {
        this.premiumTypes = premiumTypes; this.suminsured = suminsured; this.raterTypes = raterTypes; this.cardPointers = cardPointers; this.cardLoader = cardLoader;
        this.constructs = constructs; this.rules = rules; this.relations = relations;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> build(Policy p) {
        List<String> gaps = new ArrayList<>();
        PremiumType pt = premiumTypes.findById(p.getPremiumTypeId()).orElseThrow();
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("schema", SCHEMA);
        spec.put("policy", policy(p));
        spec.put("rating", rating(p, pt, gaps));
        spec.put("family", family(p, gaps));
        spec.put("relationRules", relationRules(p));
        spec.put("sheet", sheet(pt, spec));
        spec.put("gaps", gaps);
        return spec;
    }

    private Map<String, Object> policy(Policy p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("number", p.getPolicyNumber());
        m.put("employerId", p.getEmployerId());
        m.put("type", p.getPolicyType());
        m.put("status", p.getStatus().name());
        m.put("startDate", p.getStartDate().toString());
        m.put("endDate", p.getEndDate().toString());
        m.put("inception", p.isInception());
        m.put("proRata", p.getProRataType().name());
        m.put("maxMembers", p.getMaxMembers());
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rating(Policy p, PremiumType pt, List<String> gaps) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("premiumType", pt.getCode());
        r.put("raterClass", pt.getRaterClass());
        r.put("dimensions", pt.getConstructRaters());
        suminsured.findById(p.getSuminsuredSubtypeId()).ifPresent(s -> r.put("suminsuredSubtype", s.getCode()));
        raterTypes.findById(p.getRaterTypeId()).ifPresent(t -> r.put("raterType", t.getCode()));
        Optional<Long> cardId = cardPointers.findActiveCardId(p.getId(), p.getBrokerId());   // a lookup, not an exception: this runs inside one transaction
        if (cardId.isEmpty()) {
            gaps.add("NO_ACTIVE_RATE_CARD: grades, designations, age bands and sum insured values are unknown until a rate card is saved");
            r.put("sumInsured", Map.of("mode", "FREE", "values", List.of()));
            return r;
        }
        RateCardView card = cardLoader.byId(cardId.get());
        Map<String, Object> t = card.table();
        Set<String> grades = new TreeSet<>(), designations = new TreeSet<>();
        List<Map<String, Object>> bands = new ArrayList<>();
        List<Object> lol = new ArrayList<>();
        collect(t, grades, designations, bands, lol);
        r.put("taxRate", card.taxRate());
        r.put("grades", grades);
        r.put("designations", designations);
        r.put("ageBands", bands);
        r.put("perMille", containsKey(t, "permille"));
        Map<String, Object> si = new LinkedHashMap<>();
        if (!lol.isEmpty()) {
            si.put("mode", "ONE_OF");
            si.put("values", lol);
        } else {
            si.put("mode", "FREE");
            si.put("values", List.of());
        }
        r.put("sumInsured", si);
        return r;
    }

    private static boolean containsKey(Map<String, Object> t, String key) {
        if (t.containsKey(key)) return true;
        return nestedTables(t).stream().anyMatch(n -> containsKey(n, key));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nestedTables(Map<String, Object> t) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (t.get("sections") instanceof List<?> l) {
            for (Object o : l) if (o instanceof Map<?, ?> s && s.get("table") instanceof Map<?, ?> tt) out.add((Map<String, Object>) tt);
        }
        for (String k : List.of("base", "topup", "gmc", "wellness")) if (t.get(k) instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        if (t.get("layers") instanceof Map<?, ?> layers) for (Object v : layers.values()) if (v instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void collect(Map<String, Object> t, Set<String> grades, Set<String> designations, List<Map<String, Object>> bands, List<Object> lol) {
        if (t.get("grades") instanceof Map<?, ?> m) m.keySet().forEach(k -> grades.add(String.valueOf(k)));
        if (t.get("opd_grades") instanceof Map<?, ?> m) m.keySet().forEach(k -> grades.add(String.valueOf(k)));
        if (t.get("designations") instanceof Map<?, ?> m) m.keySet().forEach(k -> designations.add(String.valueOf(k)));
        for (String key : List.of("grade_age_matrix", "designation_age_matrix")) {
            if (t.get(key) instanceof Map<?, ?> m) {
                (key.startsWith("grade") ? grades : designations).addAll(m.keySet().stream().map(String::valueOf).toList());
                m.values().stream().findFirst().ifPresent(v -> addBands(v, bands));
            }
        }
        for (String key : List.of("age_bands", "gdac_bands")) if (t.get(key) != null) addBands(t.get(key), bands);
        if (t.get("lol") instanceof List<?> l) {
            for (Object o : l) if (o instanceof Map<?, ?> e && e.get("limit") != null) lol.add(e.get("limit"));
        }
        nestedTables(t).forEach(n -> collect(n, grades, designations, bands, lol));
    }

    private static void addBands(Object v, List<Map<String, Object>> bands) {
        if (!(v instanceof List<?> l)) return;
        for (Object o : l) {
            if (o instanceof Map<?, ?> b && b.get("from") != null && b.get("to") != null) {
                Map<String, Object> band = Map.of("from", b.get("from"), "to", b.get("to"));
                if (!bands.contains(band)) bands.add(band);
            }
        }
    }

    private Map<String, Object> family(Policy p, List<String> gaps) {
        Optional<FamilyConstruct> c = constructs.findByPolicyIdAndBrokerId(p.getId(), p.getBrokerId());
        if (c.isEmpty()) {
            if (Set.of("GMC", "OPD", "WELLNESS").contains(p.getPolicyType())) gaps.add("NO_FAMILY_CONSTRUCT: allowed relations and limits are unknown");
            return null;
        }
        FamilyConstruct f = c.get();
        Map<Short, RelationType> byId = relations.findAll().stream().collect(Collectors.toMap(RelationType::getId, r -> r));
        List<Map<String, Object>> allowed = new ArrayList<>();
        for (Integer id : f.getAllowedRelations()) {
            RelationType r = byId.get(id.shortValue());
            if (r != null) allowed.add(Map.of("id", r.getId(), "code", r.getCode(), "group", r.getRelationGroup()));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("allowedRelations", allowed);
        m.put("maxMembers", f.getMaxMembers());
        m.put("maxChildren", f.getMaxChildren());
        m.put("maxParents", f.getMaxParents());
        m.put("includeParents", f.isIncludeParents());
        m.put("includeParentsInLaw", f.isIncludeParentsInLaw());
        m.put("ageMin", f.getAgeMin());
        m.put("ageMax", f.getAgeMax());
        return m;
    }

    private List<Map<String, Object>> relationRules(Policy p) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Short> relMaxAge = relations.findAll().stream().filter(r -> r.getMaxAge() != null)
                .collect(Collectors.toMap(RelationType::getCode, RelationType::getMaxAge));
        for (RelationRule r : rules.findByPolicyIdOrderByRelationCode(p.getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("relationCode", r.getRelationCode());
            m.put("minAge", r.getMinAge());
            m.put("maxAge", r.getMaxAge() != null ? r.getMaxAge() : relMaxAge.get(r.getRelationCode()));
            m.put("ageDifference", r.getAgeDifference());
            m.put("differenceFromRelation", r.getDifferenceFromRelation());
            m.put("maxTwins", r.getMaxTwins());
            m.put("proRata", RelationRuleService.effectiveProRata(p.getProRataType(), r).name());
            out.add(m);
        }
        return out;
    }

    /** What a sheet for this policy must carry, derived from the premium type's dimensions and the rater class. */
    private Map<String, Object> sheet(PremiumType pt, Map<String, Object> spec) {
        List<String> dims = pt.getConstructRaters() == null ? List.of() : pt.getConstructRaters();
        String rc = pt.getRaterClass() == null ? "" : pt.getRaterClass();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("needsGrade", dims.contains("GRADE") || rc.equals("GradeRater") || rc.equals("MatrixRater") && dims.contains("GRADE"));
        m.put("needsDesignation", dims.contains("DESIGNATION") || rc.equals("DesignationRater"));
        m.put("needsFlexPlan", rc.equals("FlexRater"));
        m.put("employeeColumnsOnSelfRowOnly", List.of("grade", "designation"));     // GradeRater / DesignationRater: dependants inherit the employee's
        m.put("familyUnitPricing", Set.of("FAMILY", "FLAT_EMPLOYEE_ONLY", "FLAT_EMPLOYEE_PLUS_1", "FLAT_EMPLOYEE_PLUS_2").stream().anyMatch(dims::contains));
        return m;
    }
}
