package com.broker.sheetgen.api;

import com.broker.sheetgen.gen.Issue;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public final class Dtos {
    private Dtos() {}

    /** Every field optional: what is left out takes the documented default. */
    public record Opts(Integer families, Integer spousePct, Integer childPct, Integer twinPct, Integer parentPct, Integer midTermPct,
                       Boolean fillOptional, Boolean includePremiums, String effectiveDate, String sumInsured, String flexPlan,
                       String employeePremium, String employerPremium) {}

    /**
     * Either {@code policyId} (the spec is fetched with the caller's token) or an inline {@code spec}. ADD uses {@code options};
     * UPDATE and REMOVE need {@code source} rows from an earlier sheet plus {@code count}.
     */
    public record GenerateRequest(Long policyId, JsonNode spec, String operation, Opts options, List<Map<String, String>> source,
                                  Integer count, String removeMode, String codePrefix, Long seed) {}

    public record ValidateRequest(Long policyId, JsonNode spec, String operation, List<Map<String, String>> rows) {}

    public record CodeBlock(String prefix, long first, int count) {}

    public record GenerateResponse(String operation, long seed, List<String> columns, List<Map<String, String>> rows, List<String> warnings,
                                   CodeBlock codes, List<Issue> issues) {}

    public record ValidateResponse(boolean valid, int issueCount, List<Issue> issues) {}
}
