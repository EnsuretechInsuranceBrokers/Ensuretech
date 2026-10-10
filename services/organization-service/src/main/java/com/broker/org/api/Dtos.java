package com.broker.org.api;

import com.broker.org.domain.Broker;
import com.broker.org.domain.BrokerBranch;
import com.broker.org.domain.Employer;
import jakarta.validation.constraints.*;

public final class Dtos {
    private Dtos() {}

    public static final String PAN = "^[A-Z]{5}[0-9]{4}[A-Z]$";
    public static final String GSTIN = "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$";

    public record CreateEmployerRequest(
            @NotBlank @Size(max = 40) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String employerCode,
            @NotBlank @Size(max = 200) String legalName,
            @NotBlank @Size(max = 120) String displayName,
            @Pattern(regexp = GSTIN) String gstin,
            @Pattern(regexp = PAN) String pan,
            @Size(max = 80) String sector,
            @PositiveOrZero Integer employeeCount,
            @Email String contactEmail,
            @Size(max = 20) String contactPhone,
            @Size(max = 40) String hrmsType,
            Boolean hidden,
            Long parentEmployerId) {}

    /** PATCH semantics: null = leave unchanged. employerCode and brokerId are immutable. */
    public record UpdateEmployerRequest(
            @Size(max = 200) String legalName,
            @Size(max = 120) String displayName,
            @Pattern(regexp = GSTIN) String gstin,
            @Pattern(regexp = PAN) String pan,
            @Size(max = 80) String sector,
            @PositiveOrZero Integer employeeCount,
            @Email String contactEmail,
            @Size(max = 20) String contactPhone,
            @Size(max = 40) String hrmsType,
            Boolean hidden) {}

    public record EmployerResponse(Long id, Long brokerId, String employerCode, String legalName,
                                   String displayName, String gstin, String pan, String sector,
                                   Integer employeeCount, String contactEmail, String contactPhone,
                                   String hrmsType, boolean active, boolean hidden, Long parentEmployerId) {
        public static EmployerResponse of(Employer e) {
            return new EmployerResponse(e.getId(), e.getBrokerId(), e.getEmployerCode(), e.getLegalName(),
                    e.getDisplayName(), e.getGstin(), e.getPan(), e.getSector(), e.getEmployeeCount(),
                    e.getContactEmail(), e.getContactPhone(), e.getHrmsType(), e.isActive(), e.isHidden(), e.getParentEmployerId());
        }
    }

    public record SetParentRequest(@jakarta.validation.constraints.NotNull Long parentEmployerId) {}

    public record CreateBrokerRequest(
            @NotBlank @Size(max = 40) String code,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 200) String legalName,
            @Pattern(regexp = GSTIN) String gstin,
            @Pattern(regexp = PAN) String pan,
            @Size(max = 40) String irdaiLicenceNo,
            @NotBlank @Email String contactEmail,
            @Size(max = 20) String contactPhone) {}

    public record BrokerResponse(Long id, String code, String name, String contactEmail, boolean active) {
        public static BrokerResponse of(Broker b) {
            return new BrokerResponse(b.getId(), b.getCode(), b.getName(), b.getContactEmail(), b.isActive());
        }
    }

    public record CreateBranchRequest(@NotBlank @Size(max = 20) String branchCode,
                                      @Size(max = 80) String city, @Size(max = 80) String state,
                                      Boolean headOffice) {}

    public record BranchResponse(Long id, String branchCode, String city, String state, boolean headOffice) {
        public static BranchResponse of(BrokerBranch b) {
            return new BranchResponse(b.getId(), b.getBranchCode(), b.getCity(), b.getState(), b.isHeadOffice());
        }
    }

    public record ConfigRequest(@NotBlank String value, java.time.Instant expiresAt) {}

    public record ConfigResponse(String key, String value, java.time.Instant expiresAt) {}
}
