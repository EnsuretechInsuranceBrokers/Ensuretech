package com.broker.sheetgen;

import com.broker.sheetgen.spec.SheetSpec;
import tools.jackson.databind.json.JsonMapper;

/** Hand-built sheet-spec/v1 documents shaped like policy-service's output. */
final class Specs {
    private Specs() {}

    static final JsonMapper JSON = JsonMapper.builder().build();

    static final String ALL_RELATIONS = """
            [{"id":2,"code":"SPOUSE","group":"SPOUSE","maxAge":null},{"id":3,"code":"HUSBAND","group":"SPOUSE","maxAge":null},{"id":4,"code":"WIFE","group":"SPOUSE","maxAge":null},
             {"id":5,"code":"SON","group":"CHILD","maxAge":25},{"id":6,"code":"SON_II","group":"CHILD","maxAge":25},{"id":7,"code":"SON_III","group":"CHILD","maxAge":25},
             {"id":9,"code":"DAUGHTER","group":"CHILD","maxAge":25},{"id":10,"code":"DAUGHTER_II","group":"CHILD","maxAge":25},{"id":11,"code":"DAUGHTER_III","group":"CHILD","maxAge":25},
             {"id":12,"code":"FATHER","group":"PARENT","maxAge":null},{"id":13,"code":"MOTHER","group":"PARENT","maxAge":null},
             {"id":14,"code":"FATHER_IN_LAW","group":"PARENT_IN_LAW","maxAge":null},{"id":15,"code":"MOTHER_IN_LAW","group":"PARENT_IN_LAW","maxAge":null}]""";

    /** @param rating JSON object body of "rating" (without braces), @param family JSON of "family" or "null", @param rules JSON array */
    static String spec(String start, String end, String rating, String family, String rules, String sheet, String gaps) {
        return """
                {"schema":"sheet-spec/v1",
                 "policy":{"id":7,"number":"POL-7","employerId":1,"type":"GMC","status":"ACTIVE","startDate":"%s","endDate":"%s","inception":false,"proRata":"DAILY","maxMembers":8},
                 "rating":{%s},"family":%s,"relationRules":%s,"sheet":%s,"gaps":%s}""".formatted(start, end, rating, family, rules, sheet, gaps);
    }

    static String family(String allowed, int maxMembers, Integer maxChildren, Integer maxParents, boolean parents, boolean inLaws, Integer ageMin, Integer ageMax) {
        return """
                {"allowedRelations":%s,"maxMembers":%d,"maxChildren":%s,"maxParents":%s,"includeParents":%b,"includeParentsInLaw":%b,"ageMin":%s,"ageMax":%s}"""
                .formatted(allowed, maxMembers, maxChildren, maxParents, parents, inLaws, ageMin, ageMax);
    }

    static SheetSpec parse(String json) { return SheetSpec.parse(JSON.readTree(json)); }

    static final String NO_NEEDS = "{\"needsGrade\":false,\"needsDesignation\":false,\"needsFlexPlan\":false}";

    /** Plain flat policy, every relation, free sum insured. */
    static SheetSpec flat() {
        return parse(spec("2026-04-01", "2027-03-31", "\"premiumType\":\"FLAT\",\"raterClass\":\"FlatRater\",\"dimensions\":[],\"sumInsured\":{\"mode\":\"FREE\",\"values\":[]}",
                family(ALL_RELATIONS, 8, 3, 2, true, true, null, null), "[]", NO_NEEDS, "[]"));
    }

    static SheetSpec grade() {
        return parse(spec("2026-04-01", "2027-03-31",
                "\"premiumType\":\"GRADE\",\"raterClass\":\"GradeRater\",\"dimensions\":[\"GRADE\",\"DESIGNATION\"],\"grades\":[\"A\",\"B\",\"C\"],\"designations\":[\"Manager\",\"Executive\"],"
                        + "\"sumInsured\":{\"mode\":\"ONE_OF\",\"values\":[300000,500000,1000000.0]}",
                family(ALL_RELATIONS, 6, 2, 2, true, false, 0, 80), "[]",
                "{\"needsGrade\":true,\"needsDesignation\":true,\"needsFlexPlan\":false}", "[]"));
    }

    /** Tight rules: relation windows, age gaps, twins up to 2, small caps. */
    static SheetSpec strict() {
        String rules = """
                [{"relationCode":"SPOUSE","minAge":21,"maxAge":50,"ageDifference":null,"differenceFromRelation":null,"maxTwins":null},
                 {"relationCode":"WIFE","minAge":21,"maxAge":50,"ageDifference":null,"differenceFromRelation":null,"maxTwins":null},
                 {"relationCode":"HUSBAND","minAge":21,"maxAge":50,"ageDifference":null,"differenceFromRelation":null,"maxTwins":null},
                 {"relationCode":"SON","minAge":0,"maxAge":23,"ageDifference":20,"differenceFromRelation":"SELF","maxTwins":2},
                 {"relationCode":"SON_II","minAge":0,"maxAge":23,"ageDifference":20,"differenceFromRelation":"SELF","maxTwins":2},
                 {"relationCode":"DAUGHTER","minAge":0,"maxAge":23,"ageDifference":20,"differenceFromRelation":"SELF","maxTwins":2},
                 {"relationCode":"DAUGHTER_II","minAge":0,"maxAge":23,"ageDifference":20,"differenceFromRelation":"SELF","maxTwins":2},
                 {"relationCode":"FATHER","minAge":40,"maxAge":65,"ageDifference":20,"differenceFromRelation":"SELF","maxTwins":null},
                 {"relationCode":"MOTHER","minAge":40,"maxAge":65,"ageDifference":20,"differenceFromRelation":"SELF","maxTwins":null}]""";
        return parse(spec("2026-04-01", "2027-03-31", "\"premiumType\":\"AGE_BAND\",\"raterClass\":\"AgeRater\",\"dimensions\":[\"AGE_BAND\"],"
                        + "\"sumInsured\":{\"mode\":\"ONE_OF\",\"values\":[200000,400000]}",
                family(ALL_RELATIONS.replace("\"SON_III\"", "\"SON_III_X\""), 6, 2, 2, true, false, 0, 70), rules, NO_NEEDS, "[]"));
    }
}
