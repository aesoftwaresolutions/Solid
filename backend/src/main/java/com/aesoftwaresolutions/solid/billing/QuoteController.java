package com.aesoftwaresolutions.solid.billing;

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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/entities/{entityId}/quotes")
class QuoteController {

    record LineRequest(@NotBlank @Size(max = 300) String description, @NotNull BigDecimal quantity,
                       @NotNull com.aesoftwaresolutions.solid.money.Money unitPrice,
                       @NotNull UUID incomeAccountId) {
    }

    record QuoteRequest(@NotNull UUID customerId, @NotNull LocalDate issueDate, LocalDate validUntil,
                        @Size(max = 40) String quoteNumber, @Size(max = 500) String memo,
                        @NotEmpty @Size(max = 200) List<@Valid LineRequest> lines) {
    }

    record DeclineRequest(@Size(max = 500) String reason) {
    }

    record ConvertRequest(LocalDate issueDate,
                          @Pattern(regexp = "due_on_receipt|net_15|net_30|net_60") String terms) {
    }

    private final QuoteService quotes;

    QuoteController(QuoteService quotes) {
        this.quotes = quotes;
    }

    @GetMapping
    List<QuoteModels.Quote> list(@PathVariable UUID orgId, @PathVariable UUID entityId) {
        return quotes.list(orgId, entityId);
    }

    @GetMapping("/{quoteId}")
    QuoteModels.Quote get(@PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable UUID quoteId) {
        return quotes.get(orgId, entityId, quoteId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    QuoteModels.Quote create(@PathVariable UUID orgId, @PathVariable UUID entityId,
                             @Valid @RequestBody QuoteRequest body) {
        return quotes.create(orgId, entityId, body.customerId(), body.issueDate(), body.validUntil(),
                body.quoteNumber(), body.memo(), lines(body));
    }

    @PatchMapping("/{quoteId}")
    QuoteModels.Quote update(@PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable UUID quoteId,
                             @Valid @RequestBody QuoteRequest body) {
        return quotes.update(orgId, entityId, quoteId, body.customerId(), body.issueDate(), body.validUntil(),
                body.memo(), lines(body));
    }

    @GetMapping("/{quoteId}/pdf")
    org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> pdf(
            @PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable UUID quoteId) {
        QuoteModels.Quote quote = quotes.get(orgId, entityId, quoteId);
        byte[] pdf = quotes.quotePdf(orgId, entityId, quoteId);
        String name = "quote-" + quote.quoteNumber() + ".pdf";
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment().filename(name).build().toString())
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .contentLength(pdf.length)
                .body(new org.springframework.core.io.ByteArrayResource(pdf));
    }

    @PostMapping("/{quoteId}/send")
    QuoteModels.Quote send(@PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable UUID quoteId) {
        return quotes.send(orgId, entityId, quoteId);
    }

    @PostMapping("/{quoteId}/accept")
    QuoteModels.Quote accept(@PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable UUID quoteId) {
        return quotes.accept(orgId, entityId, quoteId);
    }

    @PostMapping("/{quoteId}/decline")
    QuoteModels.Quote decline(@PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable UUID quoteId,
                              @RequestBody(required = false) DeclineRequest body) {
        return quotes.decline(orgId, entityId, quoteId, body == null ? null : body.reason());
    }

    /** Makes the draft invoice this quote becomes. */
    @PostMapping("/{quoteId}/convert")
    @ResponseStatus(HttpStatus.CREATED)
    BillingModels.Invoice convert(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                  @PathVariable UUID quoteId,
                                  @RequestBody(required = false) ConvertRequest body) {
        return quotes.convert(orgId, entityId, quoteId, body == null ? null : body.issueDate(),
                body == null ? null : body.terms());
    }

    private static List<QuoteService.NewLine> lines(QuoteRequest body) {
        return body.lines().stream()
                .map(line -> new QuoteService.NewLine(line.description(), line.quantity(), line.unitPrice(),
                        line.incomeAccountId()))
                .toList();
    }
}
