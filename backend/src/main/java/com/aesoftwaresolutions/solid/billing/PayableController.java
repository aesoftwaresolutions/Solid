package com.aesoftwaresolutions.solid.billing;

import com.aesoftwaresolutions.solid.common.Patch;
import com.aesoftwaresolutions.solid.money.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
class PayableController {

    record CreateVendor(@NotBlank @Size(max = 200) String name, @Size(max = 254) String email,
                        @Size(max = 40) String phone, @Size(max = 500) String address,
                        @Pattern(regexp = "[0-9]{4}") String taxIdLast4,
                        @Pattern(regexp = "individual|sole_proprietor|single_member_llc|partnership|c_corporation|s_corporation|trust_estate|llc_c|llc_s|llc_p|other")
                        String taxClassification,
                        Boolean is1099Vendor, UUID defaultExpenseAccountId) {
    }

    record BillLineRequest(@NotBlank @Size(max = 300) String description, @NotNull Money amount,
                           @NotNull UUID expenseAccountId) {
    }

    record BillRequest(@NotNull UUID vendorId, @NotNull LocalDate billDate,
                       @NotNull @Pattern(regexp = "due_on_receipt|net_15|net_30|net_60") String terms,
                       @Size(max = 60) String vendorReference, @Size(max = 500) String memo,
                       @NotEmpty @Size(max = 200) List<@Valid BillLineRequest> lines) {
    }

    record ApplicationRequest(@NotNull UUID billId, @NotNull Money amount) {
    }

    record BillPaymentRequest(@NotNull UUID vendorId, @NotNull LocalDate paidDate, @NotNull UUID paymentAccountId,
                              @Size(max = 40) String method, @Size(max = 100) String reference,
                              @NotEmpty @Size(max = 200) List<@Valid ApplicationRequest> applications) {
    }

    private final PayableService payables;

    PayableController(PayableService payables) {
        this.payables = payables;
    }

    @GetMapping("/vendors")
    List<PayableModels.Vendor> vendors(@PathVariable UUID orgId, @PathVariable UUID entityId) {
        return payables.listVendors(orgId, entityId);
    }

    @PostMapping("/vendors")
    @ResponseStatus(HttpStatus.CREATED)
    PayableModels.Vendor createVendor(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                      @Valid @RequestBody CreateVendor body) {
        return payables.createVendor(orgId, entityId, body.name(), body.email(), body.phone(), body.address(),
                body.taxIdLast4(), body.taxClassification(), Boolean.TRUE.equals(body.is1099Vendor()),
                body.defaultExpenseAccountId());
    }

    @PatchMapping("/vendors/{vendorId}")
    PayableModels.Vendor updateVendor(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                      @PathVariable UUID vendorId, @RequestBody Map<String, Object> body) {
        return payables.updateVendor(orgId, entityId, vendorId,
                Patch.text(body, "name", 200), Patch.text(body, "email", 254), Patch.text(body, "phone", 40),
                Patch.text(body, "address", 500), Patch.text(body, "taxIdLast4", 4),
                Patch.text(body, "taxClassification", 40), Patch.flag(body, "is1099Vendor"),
                Patch.id(body, "defaultExpenseAccountId"), Patch.flag(body, "archived"));
    }

    @GetMapping("/bills")
    List<PayableModels.Bill> bills(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                   @RequestParam(required = false) String status) {
        return payables.listBills(orgId, entityId, status);
    }

    @GetMapping("/bills/{billId}")
    PayableModels.Bill bill(@PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable UUID billId) {
        return payables.getBill(orgId, entityId, billId);
    }

    @PostMapping("/bills")
    @ResponseStatus(HttpStatus.CREATED)
    PayableModels.Bill createBill(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                  @Valid @RequestBody BillRequest body) {
        return payables.createBill(orgId, entityId, body.vendorId(), body.billDate(), body.terms(),
                body.vendorReference(), body.memo(), lines(body));
    }

    @PatchMapping("/bills/{billId}")
    PayableModels.Bill updateBill(@PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable UUID billId,
                                  @Valid @RequestBody BillRequest body) {
        return payables.updateDraft(orgId, entityId, billId, body.vendorId(), body.billDate(), body.terms(),
                body.vendorReference(), body.memo(), lines(body));
    }

    @PostMapping("/bills/{billId}/approve")
    PayableModels.Bill approve(@PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable UUID billId) {
        return payables.approve(orgId, entityId, billId);
    }

    @PostMapping("/bills/{billId}/void")
    PayableModels.Bill voidBill(@PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable UUID billId) {
        return payables.voidBill(orgId, entityId, billId);
    }

    @PostMapping("/bill-payments")
    @ResponseStatus(HttpStatus.CREATED)
    PayableModels.BillPayment pay(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                  @Valid @RequestBody BillPaymentRequest body) {
        return payables.payBills(orgId, entityId, body.vendorId(), body.paidDate(), body.paymentAccountId(),
                body.method(), body.reference(),
                body.applications().stream().map(a -> new PayableService.NewApplication(a.billId(), a.amount())).toList());
    }

    @GetMapping("/reports/accounts-payable-aging")
    BillingModels.AgingReport aging(@PathVariable UUID orgId, @PathVariable UUID entityId, @RequestParam LocalDate asOf) {
        return payables.aging(orgId, entityId, asOf);
    }

    @GetMapping("/reports/form-1099-candidates")
    PayableModels.Form1099Report form1099(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                          @RequestParam int taxYear) {
        return payables.form1099Candidates(orgId, entityId, taxYear);
    }

    private static List<PayableService.NewBillLine> lines(BillRequest body) {
        return body.lines().stream()
                .map(l -> new PayableService.NewBillLine(l.description(), l.amount(), l.expenseAccountId())).toList();
    }
}
