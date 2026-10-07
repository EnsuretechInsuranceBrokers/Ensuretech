package com.broker.sheetgen.spec;

import com.broker.sheetgen.api.BadRequestException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import tools.jackson.databind.JsonNode;

/**
 * What a valid sheet for one policy looks like: the typed view of policy-service's {@code sheet-spec/v1}. Nothing is defaulted
 * here: a value the policy has not configured is null / empty and the generator says so rather than inventing it.
 */
public record SheetSpec(
        long policyId, String policyNumber, LocalDate start, LocalDate end, int maxMembers, String proRata,
        String premiumType, String raterClass, String siSubtype, String raterType, List<String> dimensions,
        List<String> grades, List<String> designations,
        boolean siOneOf, List<String> siValues,
        boolean hasFamily, Map<String, Allowed> allowed, Integer maxChildren, Integer maxParents,
        boolean includeParents, boolean includeParentsInLaw, Integer ageMin, Integer ageMax,
        Map<String, RelRule> rules,
        boolean needsGrade, boolean needsDesignation, boolean needsFlexPlan,
        List<String> gaps) {

    public static final String SCHEMA = "sheet-spec/v1";

    /** One covered relation. {@code maxAge} is the relation's own ceiling (null = none). */
    public record Allowed(String code, String group, Integer maxAge) {}

    /** Per-relation rule from the policy. A null field means "no rule", never zero. */
    public record RelRule(Integer minAge, Integer maxAge, Integer ageDifference, String differenceFrom, Integer maxTwins) {}

    public boolean salaryBased() { return siSubtype != null && siSubtype.contains("SALARY"); }

    public boolean noRule() { return anyContains("NO_RULE"); }

    private boolean anyContains(String s) {
        return Arrays.asList(premiumType, siSubtype, raterType).stream().anyMatch(v -> v != null && v.contains(s));
    }

    public boolean covers(String relationCode) { return "SELF".equals(relationCode) || allowed.containsKey(relationCode); }

    /** Lowest age the policy allows for this relation (null when only the generic rules apply). */
    public Integer minAgeFor(String code) {
        RelRule r = rules.get(code);
        return r == null ? null : r.minAge();
    }

    /** Highest age: the relation rule, else the relation's own ceiling, else null. The family-wide ceiling is applied separately. */
    public Integer maxAgeFor(String code) {
        RelRule r = rules.get(code);
        if (r != null && r.maxAge() != null) return r.maxAge();
        Allowed a = allowed.get(code);
        return a == null ? null : a.maxAge();
    }

    public int maxTwinsFor(String code) {
        RelRule r = rules.get(code);
        return r == null || r.maxTwins() == null ? 1 : Math.max(1, r.maxTwins());
    }

    public static SheetSpec parse(JsonNode n) {
        if (n == null || !n.isObject()) throw new BadRequestException("spec must be a sheet-spec/v1 object");
        if (!SCHEMA.equals(text(n, "schema"))) throw new BadRequestException("spec.schema must be " + SCHEMA);
        JsonNode p = req(n, "policy"), r = req(n, "rating"), s = n.path("sheet");
        LocalDate start = date(p, "startDate"), end = date(p, "endDate");
        if (end.isBefore(start)) throw new BadRequestException("spec.policy.endDate is before startDate");

        List<String> si = new ArrayList<>();
        boolean oneOf = "ONE_OF".equals(text(r.path("sumInsured"), "mode"));
        for (JsonNode v : r.path("sumInsured").path("values")) si.add(amount(v));
        if (oneOf && si.isEmpty()) oneOf = false;

        JsonNode f = n.path("family");
        boolean hasFamily = f.isObject();
        Map<String, Allowed> allowed = new LinkedHashMap<>();
        if (hasFamily) {
            for (JsonNode a : f.path("allowedRelations")) {
                String code = text(a, "code");
                if (code != null) allowed.put(code, new Allowed(code, text(a, "group"), intOrNull(a, "maxAge")));
            }
        }
        Map<String, RelRule> rules = new LinkedHashMap<>();
        for (JsonNode x : n.path("relationRules")) {
            String code = text(x, "relationCode");
            if (code != null) rules.put(code, new RelRule(intOrNull(x, "minAge"), intOrNull(x, "maxAge"), intOrNull(x, "ageDifference"),
                    text(x, "differenceFromRelation"), intOrNull(x, "maxTwins")));
        }
        List<String> gaps = new ArrayList<>();
        n.path("gaps").forEach(g -> gaps.add(g.asString("")));
        return new SheetSpec(
                p.path("id").asLong(0), text(p, "number"), start, end, maxMembers(p, f), text(p, "proRata"),
                text(r, "premiumType"), text(r, "raterClass"), text(r, "suminsuredSubtype"), text(r, "raterType"), strings(r.path("dimensions")),
                strings(r.path("grades")), strings(r.path("designations")), oneOf, si,
                hasFamily, allowed, hasFamily ? intOrNull(f, "maxChildren") : null, hasFamily ? intOrNull(f, "maxParents") : null,
                hasFamily && f.path("includeParents").asBoolean(false), hasFamily && f.path("includeParentsInLaw").asBoolean(false),
                hasFamily ? intOrNull(f, "ageMin") : null, hasFamily ? intOrNull(f, "ageMax") : null,
                rules, s.path("needsGrade").asBoolean(false), s.path("needsDesignation").asBoolean(false), s.path("needsFlexPlan").asBoolean(false),
                gaps);
    }

    /** The tighter of the policy's and the family construct's member cap. */
    private static int maxMembers(JsonNode policy, JsonNode family) {
        int m = policy.path("maxMembers").asInt(Integer.MAX_VALUE);
        if (family.path("maxMembers").isNumber()) m = Math.min(m, family.path("maxMembers").asInt());
        return m <= 0 ? Integer.MAX_VALUE : m;
    }

    private static JsonNode req(JsonNode n, String k) {
        JsonNode c = n.path(k);
        if (!c.isObject()) throw new BadRequestException("spec." + k + " is missing");
        return c;
    }

    private static String text(JsonNode n, String k) {
        JsonNode v = n.path(k);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }

    private static Integer intOrNull(JsonNode n, String k) {
        JsonNode v = n.path(k);
        return v.isNumber() ? v.asInt() : null;
    }

    private static LocalDate date(JsonNode n, String k) {
        try {
            return LocalDate.parse(text(n, k));
        } catch (RuntimeException e) {
            throw new BadRequestException("spec.policy." + k + " must be an ISO date");
        }
    }

    private static List<String> strings(JsonNode a) {
        List<String> out = new ArrayList<>();
        a.forEach(v -> out.add(v.asString("")));
        return out;
    }

    /** Sum-insured limits come as numbers; render them without exponent or trailing zeros ("500000", "1234.5"). */
    private static String amount(JsonNode v) {
        try {
            return new BigDecimal(v.asString("")).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            throw new BadRequestException("spec sum insured value is not a number: " + v);
        }
    }
}
