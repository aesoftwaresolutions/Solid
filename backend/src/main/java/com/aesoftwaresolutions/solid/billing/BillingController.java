package com.aesoftwaresolutions.solid.billing;

import com.aesoftwaresolutions.solid.money.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/entities/{entityId}")
class BillingController {

    record CreateCustomer(@NotBlank @Size(max = 200) String name, @Size(max = 254) String email,
                          @Size(max = 40) String phone, @Size(max = 500) String billingAddress,
                          @Size(max = 1000) String notes) {
    }

    record LineRequest(@NotBlank @Size(max = 300) String description, @NotNull BigDecimal quantity,
                       @NotNull Money unitPrice, @NotNull UUID incomeAccountId) {
    }

    record InvoiceRequest(@NotNull UUID customerId, @NotNull LocalDate issueDate,
                          @NotNull @Pattern(regexp = "due_on_receipt|net_15|net_30|net_60") String terms,
                          @Size(max = 40) String invoiceNumber, @Size(max = 500) String memo,
                          @NotEmpty @Size(max = 200) List<@Valid LineRequest> lines) {
    }

    record ApplicationRequest(@NotNull UUID invoiceId, @NotNull Money amount) {
    }

    record PaymentRequest(@NotNull UUID customerId, @NotNull LocalDate receivedDate, @NotNull UUID depositAccountId,
                          @Size(max = 40) String method, @Size(max = 100) String reference,
                          @NotEmpty @Size(max = 200) List<@Valid ApplicationRequest> applications) {
    }

    private final BillingService billing;

    BillingController(BillingService billing) {
        this.billing = billing;
    }

    @GetMapping("/customers")
    List<BillingModels.Customer> customers(@PathVariable UUID orgId, @PathVariable UUID entityId) {
        return billing.listCustomers(orgId, entityId);
    }

    @PostMapping("/customers")
    @ResponseStatus(HttpStatus.CREATED)
    BillingModels.Customer createCustomer(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                          @Valid @RequestBody CreateCustomer body) {
        return billing.createCustomer(orgId, entityId, body.name(), body.email(), body.phone(), body.billingAddress(),
                body.notes());
    }

    @GetMapping("/invoices")
    List<BillingModels.Invoice> invoices(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                         @RequestParam(required = false) String status) {
        return billing.listInvoices(orgId, entityId, status);
    }

    @GetMapping("/invoices/{invoiceId}")
    BillingModels.Invoice invoice(@PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable UUID invoiceId) {
        return billing.getInvoice(orgId, entityId, invoiceId);
    }

    @PostMapping("/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    BillingModels.Invoice createInvoice(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                        @Valid @RequestBody InvoiceRequest body) {
        return billing.createInvoice(orgId, entityId, body.customerId(), body.issueDate(), body.terms(),
                body.invoiceNumber(), body.memo(), lines(body));
    }

    @PatchMapping("/invoices/{invoiceId}")
    BillingModels.Invoice updateInvoice(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                        @PathVariable UUID invoiceId, @Valid @RequestBody InvoiceRequest body) {
        return billing.updateDraft(orgId, entityId, invoiceId, body.customerId(), body.issueDate(), body.terms(),
                body.memo(), lines(body));
    }

    @PostMapping("/invoices/{invoiceId}/finalize")
    BillingModels.Invoice finalizeInvoice(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                          @PathVariable UUID invoiceId) {
        return billing.finalizeInvoice(orgId, entityId, invoiceId);
    }

    @PostMapping("/invoices/{invoiceId}/void")
    BillingModels.Invoice voidInvoice(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                      @PathVariable UUID invoiceId) {
        return billing.voidInvoice(orgId, entityId, invoiceId);
    }

    @GetMapping("/payments")
    List<BillingModels.Payment> payments(@PathVariable UUID orgId, @PathVariable UUID entityId) {
        return billing.listPayments(orgId, entityId);
    }

    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    BillingModels.Payment createPayment(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                        @Valid @RequestBody PaymentRequest body) {
        return billing.recordPayment(orgId, entityId, body.customerId(), body.receivedDate(), body.depositAccountId(),
                body.method(), body.reference(),
                body.applications().stream()
                        .map(a -> new BillingService.NewApplication(a.invoiceId(), a.amount())).toList());
    }

    @GetMapping("/reports/accounts-receivable-aging")
    BillingModels.AgingReport aging(@PathVariable UUID orgId, @PathVariable UUID entityId, @RequestParam LocalDate asOf) {
        return billing.aging(orgId, entityId, asOf);
    }

    private static List<BillingService.NewLine> lines(InvoiceRequest body) {
        return body.lines().stream()
                .map(l -> new BillingService.NewLine(l.description(), l.quantity(), l.unitPrice(), l.incomeAccountId()))
                .toList();
    }
}
