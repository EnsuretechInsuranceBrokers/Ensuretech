package com.broker.integration.api;

import com.broker.integration.security.TenantContext;
import com.broker.integration.security.TenantContextHolder;
import com.broker.integration.sync.OfflineMemberService;
import com.broker.integration.sync.OfflineMemberService.Scope;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Member ids and e-cards that arrive on a spreadsheet instead of from the TPA's server. The same endpoints serve the broker (uploading for the TPA)
 * and the TPA's own user; the scope comes from the token, so each only ever sees its own policies.
 */
@RestController
@RequestMapping({"/api/v1/broker/integration", "/api/v1/tpa/integration"})
public class OfflineMemberController {
    private final OfflineMemberService service;

    OfflineMemberController(OfflineMemberService service) { this.service = service; }

    private static Scope scope() {
        TenantContext t = TenantContextHolder.require();
        if (t.isTpaScoped()) {
            if (t.tpaId() == null) throw new com.broker.integration.api.ForbiddenTenantException("Caller has no TPA");
            return new Scope(t.userId(), t.role(), t.brokerId(), t.tpaId());
        }
        if (t.brokerId() == null) throw new com.broker.integration.api.ForbiddenTenantException("Caller is not broker-scoped");
        return new Scope(t.userId(), t.role(), t.brokerId(), null);
    }

    @GetMapping("/tpa-pending")
    OfflineMemberService.Board board() { return service.board(scope()); }

    @GetMapping("/policies/{policyId}/tpa-pending")
    OfflineMemberService.PolicyDetail detail(@PathVariable long policyId, @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "200") int limit) {
        return service.detail(scope(), policyId, after, limit);
    }

    @GetMapping("/policies/{policyId}/offline-template")
    ResponseEntity<byte[]> template(@PathVariable long policyId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"tpa-member-ids-policy-" + policyId + ".xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(service.template(scope(), policyId));
    }

    @PostMapping(value = "/policies/{policyId}/offline-sheet", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('BROKER_ADMIN','BROKER_SPECIALIZER','TPA_USER')")
    OfflineMemberService.Report upload(@PathVariable long policyId, @RequestPart("file") MultipartFile file, @RequestParam(defaultValue = "preview") String mode,
                                       @RequestParam(defaultValue = "false") boolean ignoreCoverDate) throws IOException {
        if (!mode.equals("preview") && !mode.equals("apply")) throw new IllegalArgumentException("mode is preview or apply");
        if (file.isEmpty()) throw new IllegalArgumentException("The file is empty");
        return service.process(scope(), policyId, file.getBytes(), file.getOriginalFilename() == null ? "sheet" : file.getOriginalFilename(), mode.equals("apply"), ignoreCoverDate);
    }

    @GetMapping("/offline-uploads")
    List<OfflineMemberService.UploadSummary> uploads(@RequestParam(required = false) Long policyId) { return service.uploads(scope(), policyId); }

    @GetMapping("/offline-uploads/{id}")
    Map<String, Object> one(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean rejectedOnly) { return service.upload(scope(), id, rejectedOnly); }
}
