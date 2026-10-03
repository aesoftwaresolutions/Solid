package com.aesoftwaresolutions.solid.bank;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/entities/{entityId}")
class BankController {

    record CreateBankAccount(@NotBlank @Size(max = 120) String name, @NotNull UUID glAccountId,
                             @Size(max = 120) String institution, @Pattern(regexp = "[0-9]{2,4}") String mask) {
    }

    record Categorize(@NotNull UUID accountId, @Size(max = 500) String memo, UUID businessLineId) {
    }

    record BulkItem(@NotNull UUID id, @NotNull UUID accountId, UUID businessLineId) {
    }

    record BulkCategorize(@NotEmpty @Size(max = 500) List<@Valid BulkItem> items) {
    }

    record CreateRule(@NotBlank @Size(min = 2, max = 100) String contains, @NotNull UUID accountId,
                      @Min(0) @Max(1000) Integer priority) {
    }

    private final BankService bank;

    BankController(BankService bank) {
        this.bank = bank;
    }

    @PostMapping("/bank-accounts")
    @ResponseStatus(HttpStatus.CREATED)
    BankModels.BankAccount createAccount(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                         @Valid @RequestBody CreateBankAccount body) {
        return bank.createBankAccount(orgId, entityId, body.name(), body.glAccountId(), body.institution(), body.mask());
    }

    @GetMapping("/bank-accounts")
    List<BankModels.BankAccount> accounts(@PathVariable UUID orgId, @PathVariable UUID entityId) {
        return bank.listBankAccounts(orgId, entityId);
    }

    @PostMapping(path = "/bank-accounts/{bankAccountId}/imports", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    BankModels.ImportResult importFile(@PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable UUID bankAccountId,
                                       @RequestPart("file") MultipartFile file,
                                       @RequestParam(required = false) String dateColumn,
                                       @RequestParam(required = false) String descriptionColumn,
                                       @RequestParam(required = false) String amountColumn,
                                       @RequestParam(required = false) String debitColumn,
                                       @RequestParam(required = false) String creditColumn,
                                       @RequestParam(required = false) String dateFormat) throws IOException {
        if (file.getSize() > BankService.MAX_FILE_BYTES) {
            throw new IllegalArgumentException("File is too large (maximum 5 MB)");
        }
        CsvStatementParser.Hints hints = new CsvStatementParser.Hints(dateColumn, descriptionColumn, amountColumn,
                debitColumn, creditColumn, dateFormat);
        return bank.importStatement(orgId, entityId, bankAccountId, file.getOriginalFilename(), file.getBytes(), hints);
    }

    @GetMapping("/bank-transactions")
    List<BankModels.BankTransaction> transactions(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) UUID bankAccountId) {
        return bank.listTransactions(orgId, entityId, status, bankAccountId);
    }

    @PostMapping("/bank-transactions/{txnId}/categorize")
    BankModels.BankTransaction categorize(@PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable UUID txnId,
                                          @Valid @RequestBody Categorize body) {
        return bank.categorize(orgId, entityId, txnId, body.accountId(), body.memo(), body.businessLineId());
    }

    @PostMapping("/bank-transactions/categorize")
    List<BankModels.BankTransaction> categorizeAll(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                                   @Valid @RequestBody BulkCategorize body) {
        return bank.categorizeAll(orgId, entityId, body.items().stream()
                .map(i -> new BankService.CategorizeItem(i.id(), i.accountId(), i.businessLineId())).toList());
    }

    @PostMapping("/bank-transactions/{txnId}/exclude")
    BankModels.BankTransaction exclude(@PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable UUID txnId) {
        return bank.exclude(orgId, entityId, txnId);
    }

    @PostMapping("/bank-transactions/{txnId}/uncategorize")
    BankModels.BankTransaction uncategorize(@PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable UUID txnId) {
        return bank.uncategorize(orgId, entityId, txnId);
    }

    @GetMapping("/categorization-rules")
    List<BankModels.Rule> rules(@PathVariable UUID orgId, @PathVariable UUID entityId) {
        return bank.listRules(orgId, entityId);
    }

    @PostMapping("/categorization-rules")
    @ResponseStatus(HttpStatus.CREATED)
    BankModels.Rule createRule(@PathVariable UUID orgId, @PathVariable UUID entityId, @Valid @RequestBody CreateRule body) {
        return bank.createRule(orgId, entityId, body.contains(), body.accountId(), body.priority());
    }

    @DeleteMapping("/categorization-rules/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteRule(@PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable UUID ruleId) {
        bank.deleteRule(orgId, entityId, ruleId);
    }
}
