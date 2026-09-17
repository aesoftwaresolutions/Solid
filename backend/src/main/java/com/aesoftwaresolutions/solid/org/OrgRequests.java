package com.aesoftwaresolutions.solid.org;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/** Request bodies for the org API. Bean Validation annotations produce 400 errors for bad input. */
final class OrgRequests {

    private OrgRequests() {
    }

    record CreateOrganization(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "household|business|firm_client") String kind) {
    }

    record CreateEntity(
            @NotNull @Pattern(regexp = "individual|sole_prop|smllc|partnership|s_corp|c_corp|trust") String kind,
            @NotBlank @Size(max = 200) String legalName,
            @Min(1) @Max(12) Integer fiscalYearEnd,
            @Pattern(regexp = "cash|accrual") String accountingMethod,
            @Pattern(regexp = "[A-Z]{2}") String homeState,
            @Pattern(regexp = "[A-Z]{3}") String baseCurrency) {
    }

    record CreateOwnership(
            @NotNull UUID ownerEntityId,
            @NotNull UUID ownedEntityId,
            @NotNull @Pattern(regexp = "\\d{1,3}(\\.\\d{1,4})?", message = "must be a decimal string with up to 4 decimals")
            String percent,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo) {
    }
}
