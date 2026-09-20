package com.aesoftwaresolutions.solid.closing;

import com.aesoftwaresolutions.solid.assets.AssetModels;
import com.aesoftwaresolutions.solid.assets.AssetService;
import com.aesoftwaresolutions.solid.bank.BankModels;
import com.aesoftwaresolutions.solid.bank.BankService;
import com.aesoftwaresolutions.solid.bank.ReconciliationService;
import com.aesoftwaresolutions.solid.billing.PayableModels;
import com.aesoftwaresolutions.solid.billing.PayableService;
import com.aesoftwaresolutions.solid.ledger.JournalEntry;
import com.aesoftwaresolutions.solid.ledger.JournalService;
import com.aesoftwaresolutions.solid.org.OrgService;
import com.aesoftwaresolutions.solid.reporting.TaxLineReport;
import com.aesoftwaresolutions.solid.reporting.TaxLineReportService;
import com.aesoftwaresolutions.solid.tax.TaxRulePacks;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Is this tax year finished? Answers from the data, one item at a time, and changes nothing.
 *
 * <p>Every count comes from the same service the matching screen uses, so the checklist and the screen cannot
 * disagree. Anything Solid cannot decide is reported as unknown with a reason rather than quietly passing.
 */
@Service
public class YearEndService {

    public enum Status { done, todo, unknown }

    /**
     * @param where the screen that fixes this, for the UI to link to
     * @param count what the number behind the item is, or null when it is not a count
     */
    public record Item(String key, String title, Status status, String detail, Integer count, String where) {
    }

    public record Checklist(int taxYear, LocalDate from, LocalDate to, boolean ready, List<Item> items) {
    }

    private final OrgService orgs;
    private final BankService bank;
    private final ReconciliationService reconciliations;
    private final JournalService journal;
    private final TaxLineReportService taxLines;
    private final AssetService assets;
    private final PayableService payables;
    private final TaxRulePacks rulePacks;

    YearEndService(OrgService orgs, BankService bank, ReconciliationService reconciliations, JournalService journal,
                   TaxLineReportService taxLines, AssetService assets, PayableService payables,
                   TaxRulePacks rulePacks) {
        this.orgs = orgs;
        this.bank = bank;
        this.reconciliations = reconciliations;
        this.journal = journal;
        this.taxLines = taxLines;
        this.assets = assets;
        this.payables = payables;
        this.rulePacks = rulePacks;
    }

    public Checklist checklist(UUID orgId, UUID entityId, int taxYear) {
        orgs.getEntity(orgId, entityId);
        if (taxYear < 1913 || taxYear > 2100) {
            throw new IllegalArgumentException("taxYear must be between 1913 and 2100");
        }
        LocalDate from = LocalDate.of(taxYear, 1, 1);
        LocalDate to = LocalDate.of(taxYear, 12, 31);

        List<Item> items = new ArrayList<>();
        items.add(categorized(orgId, entityId));
        items.add(noDrafts(orgId, entityId, from, to));
        items.add(mapped(orgId, entityId, taxYear));
        items.add(reconciled(orgId, entityId, to));
        items.add(depreciation(orgId, entityId, from, to));
        items.add(vendors1099(orgId, entityId, taxYear));
        items.add(closed(orgId, entityId, to));
        items.add(ruleCoverage(taxYear));

        boolean ready = items.stream().noneMatch(item -> item.status() == Status.todo);
        return new Checklist(taxYear, from, to, ready, List.copyOf(items));
    }

    private Item categorized(UUID orgId, UUID entityId) {
        int waiting = bank.countUncategorized(orgId, entityId);
        return new Item("bank_categorized", "Bank activity categorized",
                waiting == 0 ? Status.done : Status.todo,
                waiting == 0 ? "Nothing is waiting in the review queue."
                        : waiting + " imported transaction(s) still need a category.",
                waiting, "bank");
    }

    private Item noDrafts(UUID orgId, UUID entityId, LocalDate from, LocalDate to) {
        int drafts = journal.list(orgId, entityId, from, to, JournalEntry.Status.draft).size();
        return new Item("no_drafts", "No draft entries", drafts == 0 ? Status.done : Status.todo,
                drafts == 0 ? "Every entry in the year is posted."
                        : drafts + " entry(ies) are still drafts, so they are in no report.",
                drafts, "journal");
    }

    private Item mapped(UUID orgId, UUID entityId, int taxYear) {
        TaxLineReport report = taxLines.report(orgId, entityId, taxYear);
        int unmapped = report.readiness().unmappedAccounts();
        return new Item("accounts_mapped", "Accounts mapped to tax lines",
                unmapped == 0 ? Status.done : Status.todo,
                unmapped == 0 ? "Every account with activity has a tax line."
                        : unmapped + " account(s) with activity have no tax line, so they are missing from the "
                                + "tax-line report.",
                unmapped, "accounts");
    }

    private Item reconciled(UUID orgId, UUID entityId, LocalDate yearEnd) {
        List<BankModels.BankAccount> accounts = bank.listBankAccounts(orgId, entityId);
        if (accounts.isEmpty()) {
            return new Item("reconciled", "Bank accounts reconciled", Status.done,
                    "There are no bank accounts to reconcile.", 0, "reconcile");
        }
        List<String> outstanding = new ArrayList<>();
        for (BankModels.BankAccount account : accounts) {
            boolean done = reconciliations.history(orgId, entityId, account.id()).stream()
                    .anyMatch(status -> !status.status().equals("open") && !status.statementDate().isBefore(yearEnd));
            if (!done) {
                outstanding.add(account.name());
            }
        }
        return new Item("reconciled", "Bank accounts reconciled",
                outstanding.isEmpty() ? Status.done : Status.todo,
                outstanding.isEmpty() ? "Every bank account is reconciled through the year end."
                        : "Not reconciled through " + yearEnd + ": " + String.join(", ", outstanding) + ".",
                outstanding.size(), "reconcile");
    }

    private Item depreciation(UUID orgId, UUID entityId, LocalDate from, LocalDate to) {
        List<AssetModels.Asset> all = assets.list(orgId, entityId);
        if (all.isEmpty()) {
            return new Item("depreciation", "Depreciation posted", Status.done,
                    "There are no fixed assets.", 0, "assets");
        }
        int unposted = 0;
        for (AssetModels.Asset asset : all) {
            for (AssetModels.ScheduleMonth month : asset.monthlySchedule()) {
                boolean inYear = !month.month().isBefore(from) && !month.month().isAfter(to);
                if (inYear && !month.posted()) {
                    unposted++;
                }
            }
        }
        int count = unposted;
        return new Item("depreciation", "Depreciation posted", count == 0 ? Status.done : Status.todo,
                count == 0 ? "Every month of the year is posted for the assets on file."
                        : count + " asset-month(s) in the year have not been posted yet.",
                count, "assets");
    }

    private Item vendors1099(UUID orgId, UUID entityId, int taxYear) {
        PayableModels.Form1099Report report = payables.form1099Candidates(orgId, entityId, taxYear);
        List<String> incomplete = report.vendors().stream()
                .filter(vendor -> !vendor.missingInformation().isEmpty())
                .map(vendor -> vendor.vendorName() + " (" + String.join(", ", vendor.missingInformation()) + ")")
                .toList();
        if (report.vendors().isEmpty()) {
            return new Item("vendors_1099", "1099 vendors complete", Status.done,
                    "No vendor is marked for 1099 tracking.", 0, "purchases");
        }
        return new Item("vendors_1099", "1099 vendors complete",
                incomplete.isEmpty() ? Status.done : Status.todo,
                incomplete.isEmpty() ? "Every 1099 vendor has the details a form needs."
                        : "Missing details: " + String.join("; ", incomplete) + ".",
                incomplete.size(), "purchases");
    }

    private Item closed(UUID orgId, UUID entityId, LocalDate yearEnd) {
        Optional<LocalDate> lock = journal.periodLock(orgId, entityId);
        boolean closed = lock.isPresent() && !lock.get().isBefore(yearEnd);
        return new Item("books_closed", "Books closed", closed ? Status.done : Status.todo,
                closed ? "The books are locked through " + lock.get() + "."
                        : "Lock the period through " + yearEnd + " once everything else is done, so the figures "
                                + "you hand over cannot change afterwards.",
                null, "journal");
    }

    private Item ruleCoverage(int taxYear) {
        TaxRulePacks.CoverageReport coverage = rulePacks.coverage(taxYear);
        boolean complete = coverage.missing() == 0;
        // Never "todo": a missing published figure is not something the person can fix, and must not read as
        // their mistake or block "ready".
        return new Item("tax_figures", "Tax figures on file", complete ? Status.done : Status.unknown,
                complete ? "Every rule pack on this server covers " + taxYear + "."
                        : coverage.missing() + " rule pack(s) have no figure for " + taxYear + ". That does not "
                                + "stop you closing the books; it means a projection would be incomplete.",
                coverage.missing(), "instance");
    }
}
