package com.broker.sheetgen.api;

import com.broker.sheetgen.SheetService;
import com.broker.sheetgen.api.Dtos.*;
import com.broker.sheetgen.gen.Column;
import com.broker.sheetgen.gen.Issue;
import com.broker.sheetgen.io.SheetWriter;
import com.broker.sheetgen.security.TenantContext;
import com.broker.sheetgen.security.TenantContextHolder;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/** Broker-only. The caller's token is passed on to policy-service, which alone decides whether that broker may read that policy. */
@RestController
@RequestMapping("/api/v1/broker/sheets")
public class SheetController {
    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final SheetService sheets;

    SheetController(SheetService sheets) { this.sheets = sheets; }

    private static long broker() {
        TenantContext t = TenantContextHolder.require();
        if (t.brokerId() == null || !t.isBrokerScoped()) throw new ForbiddenTenantException("Caller is not a broker user");
        return t.brokerId();
    }

    @GetMapping("/spec")
    JsonNode spec(@RequestParam long policyId, @RequestHeader("Authorization") String auth) {
        broker();
        return sheets.fetchSpec(policyId, auth);
    }

    @PostMapping("/generate")
    GenerateResponse generate(@RequestBody GenerateRequest req, @RequestHeader("Authorization") String auth) {
        var g = sheets.generate(req, broker(), auth);
        List<Issue> issues = selfCheck(g, req);
        return new GenerateResponse(g.sheet().operation(), g.seed(), g.sheet().columns().stream().map(Column::header).toList(), g.sheet().byHeader(),
                g.sheet().warnings(), g.codes(), issues);
    }

    @PostMapping("/generate/file")
    ResponseEntity<byte[]> generateFile(@RequestBody GenerateRequest req, @RequestParam(defaultValue = "xlsx") String format,
                                        @RequestHeader("Authorization") String auth) {
        boolean csv = "csv".equalsIgnoreCase(format);
        if (!csv && !"xlsx".equalsIgnoreCase(format)) throw new BadRequestException("format must be xlsx or csv");
        var g = sheets.generate(req, broker(), auth);
        byte[] body = csv ? SheetWriter.csv(g.sheet()) : SheetWriter.xlsx(g.sheet());
        String number = g.spec().policyNumber() == null ? "policy" : g.spec().policyNumber().replaceAll("[^A-Za-z0-9_-]", "");
        String name = g.sheet().operation().toLowerCase(Locale.ROOT) + "-" + number + "-" + LocalDate.now() + (csv ? ".csv" : ".xlsx");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .header("X-Sheet-Seed", String.valueOf(g.seed()))
                .contentType(csv ? new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8) : XLSX).body(body);
    }

    @PostMapping("/validate")
    ValidateResponse validate(@RequestBody ValidateRequest req, @RequestHeader("Authorization") String auth) {
        broker();
        List<Issue> issues = sheets.validate(req, auth);
        return new ValidateResponse(issues.isEmpty(), issues.size(), issues);
    }

    /** What a generated ADD sheet fails in the same validator a user's upload would face; always empty unless there is a bug. */
    private List<Issue> selfCheck(SheetService.Generated g, GenerateRequest req) {
        if (!"ADD".equals(g.sheet().operation())) return List.of();
        return sheets.check(g);
    }
}
