package com.aesoftwaresolutions.solid.reporting;

import com.aesoftwaresolutions.solid.billing.BillingModels;
import com.aesoftwaresolutions.solid.billing.BillingService;
import com.aesoftwaresolutions.solid.billing.PayableModels;
import com.aesoftwaresolutions.solid.billing.PayableService;
import com.aesoftwaresolutions.solid.billing.RecurringInvoiceService;
import com.aesoftwaresolutions.solid.ledger.RecurringModels;
import com.aesoftwaresolutions.solid.ledger.RecurringService;
import com.aesoftwaresolutions.solid.money.Money;
import com.aesoftwaresolutions.solid.org.LegalEntity;
import com.aesoftwaresolutions.solid.org.OrgService;
import com.aesoftwaresolutions.solid.platform.OrgScope;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * What is scheduled to move money, between two dates.
 *
 * <p>This is <em>not</em> a forecast. It knows nothing about the sale that has not been made, the bill that
 * has not arrived or the customer who pays late. Everything it lists is a record that already exists — an
 * issued invoice, an entered bill — or an occurrence a template will produce, on the dates that template's
 * own arithmetic gives. The running balance is addition and subtraction from today's cash, and the note says
 * as much so nobody reads it as a prediction.
 */
@Service
public class WhatsComingService {

    static final String NOTE = "This lists what is already scheduled: invoices you have issued, bills you have "
            + "entered, and what your repeating templates will produce. It is not a forecast — it knows "
            + "nothing about work you have not billed, bills that have not arrived, or customers who pay "
            + "late. The balance is plain arithmetic on the items below.";

    public record Item(LocalDate date, String kind, String description, String reference, Money amountIn,
                       Money amountOut, Money projectedBalance) {
    }

    public record Totals(Money in, Money out, Money net, Money projectedClosing) {
    }

    /** @param lowestPoint the worst day this arithmetic reaches — the figure worth looking at */
    public record WhatsComing(LocalDate from, LocalDate to, String currency, Money openingCash, List<Item> items,
                              Totals totals, Item lowestPoint, String note) {
    }

    private final JdbcClient db;
    private final OrgScope orgScope;
    private final OrgService orgs;
    private final CashFlowService cashFlow;
    private final BillingService billing;
    private final PayableService payables;
    private final RecurringInvoiceService recurringInvoices;
    private final RecurringService recurringEntries;

    WhatsComingService(JdbcClient db, OrgScope orgScope, OrgService orgs, CashFlowService cashFlow,
                       BillingService billing, PayableService payables,
                       RecurringInvoiceService recurringInvoices, RecurringService recurringEntries) {
        this.db = db;
        this.orgScope = orgScope;
        this.orgs = orgs;
        this.cashFlow = cashFlow;
        this.billing = billing;
        this.payables = payables;
        this.recurringInvoices = recurringInvoices;
        this.recurringEntries = recurringEntries;
    }

    public WhatsComing whatsComing(UUID orgId, UUID entityId, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must be on or before 'to'");
        }
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        String ccy = entity.baseCurrency();
        Money openingCash = cashFlow.cashAsOf(orgId, entityId, from.minusDays(1));

        List<Item> items = new ArrayList<>();

        for (BillingModels.Invoice invoice : billing.listInvoices(orgId, entityId, null)) {
            if (invoice.balanceDue().isPositive() && !invoice.status().equals("draft")
                    && !invoice.status().equals("void")) {
                // Overdue money is owed now, not on the day it was missed: putting it in the past would
                // quietly drop it out of the arithmetic.
                LocalDate when = invoice.dueDate().isBefore(from) ? from : invoice.dueDate();
                if (!when.isAfter(to)) {
                    items.add(new Item(when, "invoice_due",
                            (invoice.dueDate().isBefore(from) ? "Overdue since " + invoice.dueDate() + ": " : "")
                                    + "Invoice " + invoice.invoiceNumber(),
                            invoice.invoiceNumber(), invoice.balanceDue(), Money.zero(ccy), null));
                }
            }
        }

        for (PayableModels.Bill bill : payables.listBills(orgId, entityId, null)) {
            if (bill.balanceDue().isPositive() && !bill.status().equals("void")) {
                LocalDate when = bill.dueDate().isBefore(from) ? from : bill.dueDate();
                if (!when.isAfter(to)) {
                    items.add(new Item(when, "bill_due",
                            (bill.dueDate().isBefore(from) ? "Overdue since " + bill.dueDate() + ": " : "")
                                    + "Bill " + (bill.vendorReference() == null ? "" : bill.vendorReference()),
                            bill.vendorReference(), Money.zero(ccy), bill.balanceDue(), null));
                }
            }
        }

        for (RecurringInvoiceService.Upcoming upcoming : recurringInvoices.upcoming(orgId, entityId, to)) {
            if (!upcoming.date().isBefore(from)) {
                items.add(new Item(upcoming.date(), "recurring_invoice",
                        upcoming.name() + " — " + upcoming.customerName(), null, upcoming.amount(),
                        Money.zero(ccy), null));
            }
        }

        Set<UUID> cashAccounts = cashAccountIds(orgId, entityId);
        for (RecurringService.Upcoming upcoming : recurringEntries.upcoming(orgId, entityId, to)) {
            if (upcoming.date().isBefore(from)) {
                continue;
            }
            long cashEffect = 0;
            for (RecurringModels.Line line : upcoming.lines()) {
                if (cashAccounts.contains(line.accountId())) {
                    cashEffect += line.amount().minorUnits();
                }
            }
            if (cashEffect == 0) {
                // Depreciation and the like move no money, so they do not belong on a page about cash.
                continue;
            }
            items.add(new Item(upcoming.date(), "recurring_entry", upcoming.name(), null,
                    Money.ofMinor(Math.max(cashEffect, 0), ccy), Money.ofMinor(Math.max(-cashEffect, 0), ccy),
                    null));
        }

        items.sort(Comparator.comparing(Item::date).thenComparing(Item::kind));

        long balance = openingCash.minorUnits();
        long totalIn = 0;
        long totalOut = 0;
        List<Item> withBalances = new ArrayList<>(items.size());
        Item lowest = null;
        for (Item item : items) {
            balance += item.amountIn().minorUnits() - item.amountOut().minorUnits();
            totalIn += item.amountIn().minorUnits();
            totalOut += item.amountOut().minorUnits();
            Item placed = new Item(item.date(), item.kind(), item.description(), item.reference(),
                    item.amountIn(), item.amountOut(), Money.ofMinor(balance, ccy));
            withBalances.add(placed);
            if (lowest == null || balance < lowest.projectedBalance().minorUnits()) {
                lowest = placed;
            }
        }

        Totals totals = new Totals(Money.ofMinor(totalIn, ccy), Money.ofMinor(totalOut, ccy),
                Money.ofMinor(totalIn - totalOut, ccy), Money.ofMinor(balance, ccy));
        return new WhatsComing(from, to, ccy, openingCash, List.copyOf(withBalances), totals, lowest, NOTE);
    }

    /** The accounts that hold actual money, by the same rule the cash-flow statement uses. */
    private Set<UUID> cashAccountIds(UUID orgId, UUID entityId) {
        return orgScope.call(orgId, () -> Set.copyOf(db.sql("""
                select id from gl.account
                where entity_id = ? and type = 'asset' and coalesce(subtype, '') in ('bank', 'cash')""")
                .param(entityId).query(UUID.class).list()));
    }
}
