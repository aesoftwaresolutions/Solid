package com.aesoftwaresolutions.solid.billing;

import com.aesoftwaresolutions.solid.money.Money;
import com.aesoftwaresolutions.solid.org.LegalEntity;
import com.aesoftwaresolutions.solid.org.OrgService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** What a customer owed, was charged, paid, and still owes over a period. */
@Service
public class StatementService {

    public record Line(LocalDate date, String type, String reference, String description, Money charge, Money payment,
                       Money balance) {
    }

    public record Statement(BillingModels.Customer customer, LocalDate from, LocalDate to, String currency,
                            Money openingBalance, List<Line> lines, Money closingBalance) {
    }

    private final BillingService billing;
    private final CreditNoteService credits;
    private final OrgService orgs;
    private final Clock clock;

    StatementService(BillingService billing, CreditNoteService credits, OrgService orgs, Clock clock) {
        this.billing = billing;
        this.credits = credits;
        this.orgs = orgs;
        this.clock = clock;
    }

    public Statement statement(UUID orgId, UUID entityId, UUID customerId, LocalDate from, LocalDate to) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        String ccy = entity.baseCurrency();
        BillingModels.Customer customer = billing.getCustomer(orgId, entityId, customerId);

        LocalDate end = to != null ? to : LocalDate.now(clock);
        LocalDate start = from != null ? from : end.minusMonths(12).withDayOfMonth(1);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("'from' must be on or before 'to'");
        }

        // Only invoices that were actually issued: a draft was never sent, a voided one was never owed.
        List<BillingModels.Invoice> invoices = billing.listInvoices(orgId, entityId, null).stream()
                .filter(invoice -> invoice.customerId().equals(customerId))
                .filter(invoice -> !invoice.status().equals("draft") && !invoice.status().equals("void"))
                .toList();

        record Applied(LocalDate date, UUID paymentId, String reference, long minor) {
        }
        List<Applied> payments = new ArrayList<>();
        for (BillingModels.Payment payment : billing.listPayments(orgId, entityId)) {
            if (!payment.customerId().equals(customerId)) {
                continue;
            }
            long applied = payment.applications().stream()
                    .filter(application -> invoices.stream().anyMatch(i -> i.id().equals(application.invoiceId())))
                    .mapToLong(application -> application.amount().minorUnits())
                    .sum();
            if (applied != 0) {
                payments.add(new Applied(payment.receivedDate(), payment.id(),
                        payment.reference() != null ? payment.reference() : payment.method(), applied));
            }
        }

        // An issued credit note reduces what this customer owes from the day it was issued, whether or not it
        // has been pointed at a particular invoice — so the statement counts it like a payment.
        record Credit(LocalDate date, String reference, long minor) {
        }
        List<Credit> creditNotes = credits.list(orgId, entityId).stream()
                .filter(credit -> credit.customerId().equals(customerId))
                .filter(credit -> credit.status().equals("issued"))
                .map(credit -> new Credit(credit.issueDate(), credit.creditNumber(), credit.total().minorUnits()))
                .toList();

        long opening = invoices.stream().filter(i -> i.issueDate().isBefore(start))
                .mapToLong(i -> i.total().minorUnits()).sum()
                - payments.stream().filter(p -> p.date().isBefore(start)).mapToLong(Applied::minor).sum()
                - creditNotes.stream().filter(c -> c.date().isBefore(start)).mapToLong(Credit::minor).sum();

        // Invoices before payments on the same day, so a balance is never reduced before it is shown.
        record Event(LocalDate date, int order, String type, String reference, String description, long charge,
                     long payment) {
        }
        List<Event> events = new ArrayList<>();
        invoices.stream().filter(i -> !i.issueDate().isBefore(start) && !i.issueDate().isAfter(end))
                .forEach(i -> events.add(new Event(i.issueDate(), 0, "invoice",
                        i.invoiceNumber() != null ? i.invoiceNumber() : i.id().toString().substring(0, 8),
                        i.memo() != null ? i.memo() : "Invoice", i.total().minorUnits(), 0)));
        payments.stream().filter(p -> !p.date().isBefore(start) && !p.date().isAfter(end))
                .forEach(p -> events.add(new Event(p.date(), 1, "payment",
                        p.reference() != null ? p.reference() : p.paymentId().toString().substring(0, 8),
                        "Payment received", 0, p.minor())));
        creditNotes.stream().filter(c -> !c.date().isBefore(start) && !c.date().isAfter(end))
                .forEach(c -> events.add(new Event(c.date(), 1, "credit_note", c.reference(),
                        "Credit note", 0, c.minor())));
        events.sort(Comparator.comparing(Event::date).thenComparing(Event::order).thenComparing(Event::reference));

        List<Line> lines = new ArrayList<>(events.size());
        long running = opening;
        for (Event event : events) {
            running += event.charge() - event.payment();
            lines.add(new Line(event.date(), event.type(), event.reference(), event.description(),
                    Money.ofMinor(event.charge(), ccy), Money.ofMinor(event.payment(), ccy),
                    Money.ofMinor(running, ccy)));
        }

        return new Statement(customer, start, end, ccy, Money.ofMinor(opening, ccy), List.copyOf(lines),
                Money.ofMinor(running, ccy));
    }

    public byte[] statementPdf(UUID orgId, UUID entityId, UUID customerId, LocalDate from, LocalDate to) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        return StatementPdf.render(entity, statement(orgId, entityId, customerId, from, to),
                billing.letterhead(orgId, entityId), billing.logoBytes(orgId, entityId));
    }
}
