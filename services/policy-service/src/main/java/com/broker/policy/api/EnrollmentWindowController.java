package com.broker.policy.api;

import com.broker.policy.security.TenantContextHolder;
import com.broker.policy.service.EnrollmentWindowEvaluator.*;
import com.broker.policy.service.EnrollmentWindowService;
import com.broker.policy.service.EnrollmentWindowService.Audit;
import com.broker.policy.service.EnrollmentWindowService.OverrideIn;
import java.time.LocalDate;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Who may enrol in a policy, and until when. Broker side; employees reach their own status through the BFF's evaluation call. */
@RestController
@RequestMapping("/api/v1/broker/policies/{id}/enrollment-window")
public class EnrollmentWindowController {
    public record SettingsDto(Boolean enabled, LocalDate startDate, LocalDate endDate, Integer midtermDays, Boolean allowAfterExpiry, Integer daysAfterExpiry) {
        static SettingsDto of(Settings s) { return new SettingsDto(s.enabled(), s.start(), s.end(), s.midtermDays(), s.allowAfterExpiry(), s.daysAfterExpiry()); }
        Settings toSettings() { return new Settings(enabled != null && enabled, startDate, endDate, midtermDays == null ? 30 : midtermDays, allowAfterExpiry != null && allowAfterExpiry, daysAfterExpiry == null ? 0 : daysAfterExpiry); }
    }
    public record OverrideDto(String employeeRef, Boolean enabled, LocalDate startDate, LocalDate endDate, LocalDate closeDate) {
        static OverrideDto of(OverrideIn o) { return new OverrideDto(o.employeeRef(), o.enabled(), o.start(), o.end(), o.closeDate()); }
        OverrideIn toIn() { return new OverrideIn(employeeRef, enabled == null || enabled, startDate, endDate, closeDate); }
    }
    public record OverridesDto(List<OverrideDto> employees) {}
    public record FactsDto(String employeeRef, LocalDate dateOfJoining, LocalDate dateOfUpload, Boolean hasMembers, Boolean inception, LocalDate asOf) {
        Employee toEmployee() { return new Employee(dateOfJoining, dateOfUpload == null ? LocalDate.now() : dateOfUpload, hasMembers == null || hasMembers, inception != null && inception, null, null); }
    }
    public record ResultDto(String status, LocalDate startDate, LocalDate endDate, java.time.Instant confirmedAt, boolean windowSet, boolean midtermWindowSet,
                     boolean endtermWindowSet, boolean customWindowSet, LocalDate closeDate) {
        static ResultDto of(Result r) {
            return new ResultDto(r.status().name(), r.start(), r.end(), r.confirmedAt(), r.windowSet(), r.midtermWindowSet(), r.endtermWindowSet(), r.customWindowSet(), r.closeDate());
        }
    }

    private final EnrollmentWindowService svc;

    EnrollmentWindowController(EnrollmentWindowService svc) { this.svc = svc; }

    private static long broker() { return TenantContextHolder.requireBrokerId(); }
    private static String actor() { return TenantContextHolder.require().userId(); }

    @GetMapping
    SettingsDto get(@PathVariable long id) { return SettingsDto.of(svc.settings(broker(), id)); }

    @PutMapping
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    SettingsDto put(@PathVariable long id, @RequestBody SettingsDto in) { return SettingsDto.of(svc.putSettings(broker(), id, in.toSettings(), actor())); }

    @GetMapping("/employees")
    List<OverrideDto> overrides(@PathVariable long id) { return svc.overrides(broker(), id).stream().map(OverrideDto::of).toList(); }

    @PutMapping("/employees")
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    List<OverrideDto> putOverrides(@PathVariable long id, @RequestBody OverridesDto in) {
        List<OverrideIn> list = in.employees() == null ? List.of() : in.employees().stream().map(OverrideDto::toIn).toList();
        return svc.putOverrides(broker(), id, list, actor()).stream().map(OverrideDto::of).toList();
    }

    @DeleteMapping("/employees/{ref}")
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    void deleteOverride(@PathVariable long id, @PathVariable String ref) { svc.deleteOverride(broker(), id, ref, actor()); }

    @PostMapping("/evaluate")
    ResultDto evaluate(@PathVariable long id, @RequestBody FactsDto f) {
        requireRef(f.employeeRef());
        return ResultDto.of(svc.evaluate(broker(), id, f.employeeRef(), f.toEmployee(), f.asOf() == null ? LocalDate.now() : f.asOf()));
    }

    @PostMapping("/confirm")
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    ResultDto confirm(@PathVariable long id, @RequestBody FactsDto f) {
        requireRef(f.employeeRef());
        return ResultDto.of(svc.confirm(broker(), id, f.employeeRef(), f.toEmployee(), f.asOf() == null ? LocalDate.now() : f.asOf(), actor()));
    }

    @DeleteMapping("/confirm/{ref}")
    @PreAuthorize("hasRole('BROKER_ADMIN')")
    void unconfirm(@PathVariable long id, @PathVariable String ref) { svc.unconfirm(broker(), id, ref, actor()); }

    @GetMapping("/audit")
    List<Audit> audit(@PathVariable long id, @RequestParam(defaultValue = "100") int limit) { return svc.audit(broker(), id, limit); }

    private static void requireRef(String r) {
        if (r == null || r.isBlank() || r.length() > 60) throw new ValidationFailedException(List.of(new ValidationFailedException.Issue("EMPLOYEE_REF", "employeeRef is required (max 60 characters)")));
    }
}
