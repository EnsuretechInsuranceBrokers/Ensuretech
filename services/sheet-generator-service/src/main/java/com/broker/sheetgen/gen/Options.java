package com.broker.sheetgen.gen;

import java.time.LocalDate;

/**
 * How a sheet is generated. Percentages are the chance a family gets that member type; they never override the policy (a family the
 * policy does not allow is simply not generated).
 */
public record Options(
        int families, int spousePct, int childPct, int twinPct, int parentPct, int midTermPct,
        boolean fillOptional, boolean includePremiums,
        LocalDate effectiveDate, Long seed,
        String sumInsured, String flexPlan, String employeePremium, String employerPremium) {

    public static final int MAX_PCT = 100;

    public static Options defaults(int families) {
        return new Options(families, 80, 60, 10, 20, 30, false, false, null, null, null, null, null, null);
    }

    public Options withSeed(long s) {
        return new Options(families, spousePct, childPct, twinPct, parentPct, midTermPct, fillOptional, includePremiums,
                effectiveDate, s, sumInsured, flexPlan, employeePremium, employerPremium);
    }
}
