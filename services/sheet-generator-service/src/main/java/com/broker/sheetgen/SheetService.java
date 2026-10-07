package com.broker.sheetgen;

import com.broker.sheetgen.api.BadRequestException;
import com.broker.sheetgen.api.Dtos.*;
import com.broker.sheetgen.counter.CodeCounter;
import com.broker.sheetgen.gen.*;
import com.broker.sheetgen.spec.SheetSpec;
import com.broker.sheetgen.spec.SpecClient;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class SheetService {
    private static final Pattern PREFIX = Pattern.compile("[A-Z0-9]{1,10}");
    private static final String DEFAULT_PREFIX = "GEN";

    private final SpecClient specs;
    private final CodeCounter counter;
    private final Clock clock;
    private final int maxFamilies;
    private final int maxRows;

    SheetService(SpecClient specs, CodeCounter counter, Clock clock, @Value("${app.sheets.max-families:20000}") int maxFamilies,
                 @Value("${app.sheets.max-rows-in-memory:200000}") int maxRows) {
        this.specs = specs;
        this.counter = counter;
        this.clock = clock;
        this.maxFamilies = maxFamilies;
        this.maxRows = maxRows;
    }

    public JsonNode fetchSpec(long policyId, String authorization) { return specs.fetch(policyId, authorization); }

    private SheetSpec resolve(Long policyId, JsonNode inline, String authorization) {
        if (policyId == null && inline == null) throw new BadRequestException("Give either policyId or spec");
        if (policyId != null && inline != null) throw new BadRequestException("Give policyId or spec, not both");
        return SheetSpec.parse(inline != null ? inline : specs.fetch(policyId, authorization));
    }

    public record Generated(Sheet sheet, long seed, CodeBlock codes, SheetSpec spec) {}

    public Generated generate(GenerateRequest req, long brokerId, String authorization) {
        SheetSpec spec = resolve(req.policyId(), req.spec(), authorization);
        String op = operation(req.operation());
        long seed = req.seed() != null ? req.seed() : System.nanoTime();
        LocalDate today = LocalDate.now(clock);
        switch (op) {
            case "ADD" -> {
                Options o = options(req.options(), seed);
                if (o.families() > maxFamilies) throw new BadRequestException("families is capped at " + maxFamilies);
                SheetGenerator.preflight(spec);            // refuse before a block of codes is spent on a sheet that cannot be made
                String prefix = prefix(req.codePrefix());
                long first = counter.reserve(brokerId, prefix, o.families());
                Sheet s = SheetGenerator.add(spec, o, new SheetGenerator.Ids(prefix, first, brokerId), today);
                return new Generated(s, seed, new CodeBlock(prefix, first, o.families()), spec);
            }
            case "REMOVE" -> {
                List<Map<String, String>> src = source(req);
                int n = count(req);
                SheetDerive.RemoveMode mode = removeMode(req.removeMode());
                return new Generated(SheetDerive.remove(spec, src, n, mode, seed, today), seed, null, spec);
            }
            default -> {
                List<Map<String, String>> src = source(req);
                return new Generated(SheetDerive.update(spec, src, count(req), seed, today), seed, null, spec);
            }
        }
    }

    /** The generated sheet run through the validator: anything returned here is a generator defect, not user error. */
    public List<Issue> check(Generated g) {
        return SheetValidator.validate(g.spec(), g.sheet().operation(), g.sheet().byHeader(), LocalDate.now(clock));
    }

    public List<Issue> validate(ValidateRequest req, String authorization) {
        SheetSpec spec = resolve(req.policyId(), req.spec(), authorization);
        if (req.rows() == null) throw new BadRequestException("rows is required");
        if (req.rows().size() > maxRows) throw new BadRequestException("at most " + maxRows + " rows can be validated at once");
        return SheetValidator.validate(spec, operation(req.operation()), req.rows(), LocalDate.now(clock));
    }

    private static String operation(String s) {
        String op = s == null ? "ADD" : s.toUpperCase(Locale.ROOT);
        if (!List.of("ADD", "UPDATE", "REMOVE").contains(op)) throw new BadRequestException("operation must be ADD, UPDATE or REMOVE");
        return op;
    }

    private List<Map<String, String>> source(GenerateRequest req) {
        if (req.source() == null || req.source().isEmpty()) throw new BadRequestException(operation(req.operation()) + " needs source rows from an earlier sheet");
        if (req.source().size() > maxRows) throw new BadRequestException("at most " + maxRows + " source rows");
        return req.source();
    }

    private static int count(GenerateRequest req) {
        if (req.count() == null) throw new BadRequestException("count is required");
        return req.count();
    }

    private static SheetDerive.RemoveMode removeMode(String s) {
        try {
            return s == null ? SheetDerive.RemoveMode.MIX : SheetDerive.RemoveMode.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("removeMode must be FAMILY, MEMBER or MIX");
        }
    }

    private static String prefix(String p) {
        String v = p == null || p.isBlank() ? DEFAULT_PREFIX : p.trim().toUpperCase(Locale.ROOT);
        if (!PREFIX.matcher(v).matches()) throw new BadRequestException("codePrefix must be 1-10 letters or digits");
        return v;
    }

    private static Options options(Opts o, long seed) {
        Options d = Options.defaults(10);
        if (o == null) return d.withSeed(seed);
        LocalDate eff;
        try {
            eff = o.effectiveDate() == null || o.effectiveDate().isBlank() ? null : Rules.parseDate(o.effectiveDate());
        } catch (DateTimeParseException e) {
            eff = null;
        }
        if (o.effectiveDate() != null && !o.effectiveDate().isBlank() && eff == null) throw new BadRequestException("effectiveDate must be dd-MM-yyyy or yyyy-MM-dd");
        return new Options(or(o.families(), d.families()), or(o.spousePct(), d.spousePct()), or(o.childPct(), d.childPct()), or(o.twinPct(), d.twinPct()),
                or(o.parentPct(), d.parentPct()), or(o.midTermPct(), d.midTermPct()), Boolean.TRUE.equals(o.fillOptional()), Boolean.TRUE.equals(o.includePremiums()),
                eff, seed, o.sumInsured(), o.flexPlan(), o.employeePremium(), o.employerPremium());
    }

    private static int or(Integer v, int d) { return v == null ? d : v; }
}
