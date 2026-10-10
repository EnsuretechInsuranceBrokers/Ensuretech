package com.broker.policy.engine;

import com.broker.policy.domain.PremiumType;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Strategy selector: premium_types.rater_class -> Rater. Annual, pre-tax premiums; tax from the rate card. */
@Component
public class PremiumEngine implements Rater.Registry {
    private final Map<String, Rater> raters = new HashMap<>();

    public PremiumEngine(List<Rater> all) {
        for (Rater r : all) for (String c : r.raterClasses()) raters.put(c, r);
    }

    @Override
    public Rater get(String raterClass) { return raters.get(raterClass); }

    public boolean supports(String raterClass) { return raters.containsKey(raterClass); }

    public List<String> validate(PremiumType type, Map<String, Object> table) {
        Rater r = raters.get(type.getRaterClass());
        if (r == null) return List.of("no rater implementation for " + type.getRaterClass());
        return r.validate(type, new RateTable(table), this);
    }

    public Quote quote(PremiumType type, Map<String, Object> table, BigDecimal taxRatePercent, List<Member> members) {
        if (members.isEmpty()) throw new RatingException("at least one member is required");
        List<String> problems = validate(type, table);
        if (!problems.isEmpty()) throw new InvalidRateTableException(problems);
        List<BigDecimal> raw = raters.get(type.getRaterClass()).rate(type, new RateTable(table), members, this);
        BigDecimal subtotal = BigDecimal.ZERO;
        List<MemberPremium> out = new java.util.ArrayList<>();
        for (int i = 0; i < members.size(); i++) {
            BigDecimal p = raw.get(i).setScale(2, RoundingMode.HALF_UP);
            subtotal = subtotal.add(p);
            out.add(new MemberPremium(members.get(i).ref(), p));
        }
        BigDecimal tax = subtotal.multiply(taxRatePercent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return new Quote(out, subtotal, taxRatePercent, tax, subtotal.add(tax));
    }

    public record MemberPremium(String memberRef, BigDecimal premium) implements Serializable {}

    public record Quote(List<MemberPremium> members, BigDecimal subtotal, BigDecimal taxRate, BigDecimal tax,
                        BigDecimal total) {}
}
