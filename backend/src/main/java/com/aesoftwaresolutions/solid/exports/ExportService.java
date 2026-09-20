package com.aesoftwaresolutions.solid.exports;

import com.aesoftwaresolutions.solid.audit.AuditLog;
import com.aesoftwaresolutions.solid.bank.BankModels;
import com.aesoftwaresolutions.solid.bank.BankService;
import com.aesoftwaresolutions.solid.billing.BillingModels;
import com.aesoftwaresolutions.solid.billing.BillingService;
import com.aesoftwaresolutions.solid.billing.PayableModels;
import com.aesoftwaresolutions.solid.billing.PayableService;
import com.aesoftwaresolutions.solid.docs.DocumentModels;
import com.aesoftwaresolutions.solid.docs.DocumentService;
import com.aesoftwaresolutions.solid.ledger.Account;
import com.aesoftwaresolutions.solid.ledger.AccountService;
import com.aesoftwaresolutions.solid.ledger.JournalEntry;
import com.aesoftwaresolutions.solid.ledger.JournalService;
import com.aesoftwaresolutions.solid.org.LegalEntity;
import com.aesoftwaresolutions.solid.org.OrgService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;

/** Builds a ZIP of plain CSV files holding everything one entity's books contain. */
@Service
public class ExportService {

    private final OrgService orgs;
    private final AccountService accounts;
    private final JournalService journal;
    private final BankService bank;
    private final BillingService billing;
    private final PayableService payables;
    private final DocumentService documents;
    private final AuditLog audit;
    private final Clock clock;

    ExportService(OrgService orgs, AccountService accounts, JournalService journal, BankService bank,
                  BillingService billing, PayableService payables, DocumentService documents, AuditLog audit,
                  Clock clock) {
        this.orgs = orgs;
        this.accounts = accounts;
        this.journal = journal;
        this.bank = bank;
        this.billing = billing;
        this.payables = payables;
        this.documents = documents;
        this.audit = audit;
        this.clock = clock;
    }

    public byte[] exportEntity(UUID orgId, UUID entityId) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        OffsetDateTime takenAt = OffsetDateTime.now(clock);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            write(zip, "README.txt", readme(entity, takenAt));
            write(zip, "accounts.csv", accountsCsv(orgId, entityId));
            write(zip, "journal-entries.csv", entriesCsv(orgId, entityId));
            write(zip, "journal-lines.csv", linesCsv(orgId, entityId));
            write(zip, "bank-transactions.csv", bankCsv(orgId, entityId));
            write(zip, "invoices.csv", invoicesCsv(orgId, entityId));
            write(zip, "bills.csv", billsCsv(orgId, entityId));
            write(zip, "documents.csv", documentsCsv(orgId, entityId));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build the export", e);
        }

        audit.record(AuditLog.Actor.current(), orgId, "data_exported", "entity", entityId,
                Map.of("bytes", bytes.size()));
        return bytes.toByteArray();
    }

    private static void write(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String readme(LegalEntity entity, OffsetDateTime takenAt) {
        return """
                Solid export
                ============
                Entity:    %s (%s)
                Currency:  %s
                Taken at:  %s

                Every file here is UTF-8 CSV with a header row. Amounts are plain decimal strings with the currency
                in its own column; dates are ISO-8601 (2026-09-18). The id columns are the same ids the API uses, so
                journal-lines.csv joins to journal-entries.csv on entry_id, and both join to accounts.csv on
                account_id.

                A field that begins with = + - or @ is written with a leading apostrophe so that a spreadsheet opens
                it as text instead of running it as a formula. Remove the apostrophe if you need the raw text.

                What is NOT in this export:
                  * the uploaded files themselves. documents.csv lists what is in the vault (name, kind, size, its
                    SHA-256 and what it is attached to), but the encrypted files come out through a server backup —
                    see docs/operations.md.
                  * other entities and organizations on this installation.
                  * users, passwords, sessions and the audit log.
                """.formatted(entity.legalName(), entity.kind(), entity.baseCurrency(), takenAt);
    }

    private String accountsCsv(UUID orgId, UUID entityId) {
        StringBuilder csv = new StringBuilder(Csv.row("account_id", "code", "name", "type", "subtype", "parent_id",
                "is_header", "tax_line_code", "is_archived"));
        for (Account account : accounts.list(orgId, entityId)) {
            csv.append(Csv.row(account.id(), account.code(), account.name(), account.type(), account.subtype(),
                    account.parentId(), account.isHeader(), account.taxLineCode(), account.isArchived()));
        }
        return csv.toString();
    }

    private String entriesCsv(UUID orgId, UUID entityId) {
        StringBuilder csv = new StringBuilder(Csv.row("entry_id", "entry_date", "memo", "source", "status",
                "reverses_entry_id", "posting_seq", "posted_at"));
        for (JournalEntry entry : allEntries(orgId, entityId)) {
            csv.append(Csv.row(entry.id(), entry.entryDate(), entry.memo(), entry.source(), entry.status(),
                    entry.reversesEntryId(), entry.postingSeq(), entry.postedAt()));
        }
        return csv.toString();
    }

    private String linesCsv(UUID orgId, UUID entityId) {
        StringBuilder csv = new StringBuilder(Csv.row("entry_id", "line_no", "account_id", "amount", "currency",
                "memo", "entry_date", "status"));
        for (JournalEntry entry : allEntries(orgId, entityId)) {
            for (JournalEntry.Line line : entry.lines()) {
                csv.append(Csv.row(entry.id(), line.lineNo(), line.accountId(), line.amount().toDecimalString(),
                        line.amount().currency(), line.memo(), entry.entryDate(), entry.status()));
            }
        }
        return csv.toString();
    }

    /** Every entry, with no page limit: an export that silently stopped at 1000 rows would be worse than none. */
    private List<JournalEntry> allEntries(UUID orgId, UUID entityId) {
        return journal.allEntryIds(orgId, entityId).stream()
                .map(id -> journal.get(orgId, entityId, id))
                .toList();
    }

    private String bankCsv(UUID orgId, UUID entityId) {
        StringBuilder csv = new StringBuilder(Csv.row("bank_transaction_id", "bank_account_id", "posted_date",
                "description", "amount", "currency", "status", "journal_entry_id"));
        for (BankModels.BankTransaction txn : bank.allTransactions(orgId, entityId)) {
            csv.append(Csv.row(txn.id(), txn.bankAccountId(), txn.postedDate(), txn.description(),
                    txn.amount().toDecimalString(), txn.amount().currency(), txn.status(), txn.journalEntryId()));
        }
        return csv.toString();
    }

    private String invoicesCsv(UUID orgId, UUID entityId) {
        StringBuilder csv = new StringBuilder(Csv.row("invoice_id", "invoice_number", "customer_id", "issue_date",
                "due_date", "terms", "status", "total", "amount_paid", "balance_due", "currency", "memo"));
        for (BillingModels.Invoice invoice : billing.listInvoices(orgId, entityId, null)) {
            csv.append(Csv.row(invoice.id(), invoice.invoiceNumber(), invoice.customerId(), invoice.issueDate(),
                    invoice.dueDate(), invoice.terms(), invoice.status(), invoice.total().toDecimalString(),
                    invoice.amountPaid().toDecimalString(), invoice.balanceDue().toDecimalString(),
                    invoice.total().currency(), invoice.memo()));
        }
        return csv.toString();
    }

    private String billsCsv(UUID orgId, UUID entityId) {
        StringBuilder csv = new StringBuilder(Csv.row("bill_id", "vendor_id", "vendor_reference", "bill_date",
                "due_date", "terms", "status", "total", "amount_paid", "balance_due", "currency", "memo"));
        for (PayableModels.Bill bill : payables.listBills(orgId, entityId, null)) {
            csv.append(Csv.row(bill.id(), bill.vendorId(), bill.vendorReference(), bill.billDate(), bill.dueDate(),
                    bill.terms(), bill.status(), bill.total().toDecimalString(), bill.amountPaid().toDecimalString(),
                    bill.balanceDue().toDecimalString(), bill.total().currency(), bill.memo()));
        }
        return csv.toString();
    }

    private String documentsCsv(UUID orgId, UUID entityId) {
        StringBuilder csv = new StringBuilder(Csv.row("document_id", "filename", "kind", "content_type", "size_bytes",
                "sha256", "uploaded_at", "note", "attached_to"));
        for (DocumentModels.Document document : documents.list(orgId, entityId, null, null, null)) {
            String attachedTo = document.links().stream()
                    .map(link -> link.objectType() + ":" + link.objectId())
                    .reduce((a, b) -> a + " " + b).orElse("");
            csv.append(Csv.row(document.id(), document.filename(), document.kind(), document.contentType(),
                    document.sizeBytes(), document.sha256(), document.uploadedAt(), document.note(), attachedTo));
        }
        return csv.toString();
    }
}
