package com.aesoftwaresolutions.solid.ledger;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

final class AccountRequests {

    private AccountRequests() {
    }

    record CreateAccount(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9.\\-]{1,20}") String code,
            @NotBlank @Size(max = 120) String name,
            @NotNull AccountType type,
            @Pattern(regexp = "[a-z_]{1,40}") String subtype,
            UUID parentId,
            Boolean isHeader,
            String taxLineCode) {
    }

    /** Partial update. Send {@code "taxLineCode": ""} to clear the tax line. */
    record UpdateAccount(@Size(min = 1, max = 120) String name, String taxLineCode, Boolean archived) {
    }

    record ApplyTemplate(@NotBlank String template) {
    }
}
