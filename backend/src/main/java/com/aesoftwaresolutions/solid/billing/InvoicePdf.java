package com.aesoftwaresolutions.solid.billing;

import static com.aesoftwaresolutions.solid.billing.Pdf.DESCRIPTION_WIDTH;
import static com.aesoftwaresolutions.solid.billing.Pdf.LINE;
import static com.aesoftwaresolutions.solid.billing.Pdf.MARGIN;
import static com.aesoftwaresolutions.solid.billing.Pdf.fit;
import static com.aesoftwaresolutions.solid.billing.Pdf.rule;
import static com.aesoftwaresolutions.solid.billing.Pdf.stamp;
import static com.aesoftwaresolutions.solid.billing.Pdf.text;
import static com.aesoftwaresolutions.solid.billing.Pdf.textRight;

import com.aesoftwaresolutions.solid.money.Money;
import com.aesoftwaresolutions.solid.org.Branding;
import com.aesoftwaresolutions.solid.org.LegalEntity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

/**
 * Draws one invoice on one page: plain, readable, and honest about a draft or a voided invoice.
 *
 * <p>Deliberately simple — one font, no logo. Branding is its own slice; getting the numbers and the status right
 * is this one.
 */
final class InvoicePdf {

    private InvoicePdf() {
    }

    static byte[] render(LegalEntity entity, BillingModels.Customer customer, BillingModels.Invoice invoice,
                         Branding branding, byte[] logo) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            PDType1Font regular = Pdf.regular();
            PDType1Font bold = Pdf.bold();
            float right = page.getMediaBox().getWidth() - MARGIN;

            List<BillingModels.InvoiceLine> overflow = List.of();
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                float y = page.getMediaBox().getHeight() - MARGIN;

                text(content, bold, 18, MARGIN, y, entity.legalName());
                y -= LINE + LINE / 2f;
                y -= Pdf.letterhead(document, content, regular, y, right, branding, logo)
                        * Pdf.LETTERHEAD_LINE;
                y -= LINE / 2f;
                text(content, bold, 14, MARGIN, y, "INVOICE " + (invoice.invoiceNumber() == null
                        ? "(draft)" : invoice.invoiceNumber()));
                y -= LINE + LINE / 2f;

                text(content, regular, 10, MARGIN, y, "Bill to: " + customer.name());
                y -= LINE;
                if (customer.billingAddress() != null && !customer.billingAddress().isBlank()) {
                    for (String addressLine : customer.billingAddress().split("\\R")) {
                        text(content, regular, 10, MARGIN, y, addressLine);
                        y -= LINE;
                    }
                }
                y -= LINE / 2f;
                text(content, regular, 10, MARGIN, y, "Issued " + invoice.issueDate()
                        + "     Due " + invoice.dueDate()
                        + "     Terms " + invoice.terms().replace('_', ' '));
                y -= LINE * 2;

                text(content, bold, 10, MARGIN, y, "Description");
                text(content, bold, 10, MARGIN + 260, y, "Qty");
                text(content, bold, 10, MARGIN + 320, y, "Unit price");
                textRight(content, bold, 10, right, y, "Amount");
                y -= LINE;
                rule(content, MARGIN, right, y + 4);
                y -= LINE / 2f;

                for (BillingModels.InvoiceLine line : invoice.lines()) {
                    // Keep room for the totals block; a 200-line invoice must not run off the bottom of the page.
                    if (y < MARGIN + LINE * 11) {
                        text(content, regular, 9, MARGIN, y, "continued...");
                        overflow = invoice.lines().subList(invoice.lines().indexOf(line), invoice.lines().size());
                        break;
                    }
                    text(content, regular, 10, MARGIN, y, fit(regular, line.description(), DESCRIPTION_WIDTH));
                    text(content, regular, 10, MARGIN + 260, y, line.quantity().stripTrailingZeros().toPlainString());
                    text(content, regular, 10, MARGIN + 320, y, money(line.unitPrice()));
                    textRight(content, regular, 10, right, y, money(line.amount()));
                    y -= LINE;
                }

                y -= LINE / 2f;
                rule(content, MARGIN + 320, right, y + 8);
                y -= LINE / 2f;
                if (invoice.taxTotal().isPositive()) {
                    total(content, regular, bold, right, y, "Sales tax", invoice.taxTotal());
                    y -= LINE;
                }
                total(content, regular, bold, right, y, "Total", invoice.total());
                y -= LINE;
                total(content, regular, bold, right, y, "Paid", invoice.amountPaid());
                y -= LINE;
                total(content, bold, bold, right, y, "Amount due", invoice.balanceDue());
                y -= LINE * 2;

                if (invoice.memo() != null && !invoice.memo().isBlank()) {
                    text(content, regular, 10, MARGIN, y, fit(regular, invoice.memo(), right - MARGIN));
                    y -= LINE * 2;
                }

                String status = switch (invoice.status()) {
                    case "draft" -> "This invoice is a draft and has not been issued.";
                    case "void" -> "This invoice has been voided and is not payable.";
                    default -> null;
                };
                if (status != null) {
                    text(content, bold, 11, MARGIN, y, status);
                    y -= LINE * 2;
                }
                if (branding != null && branding.paymentInstructions() != null
                        && !branding.paymentInstructions().isBlank()) {
                    text(content, regular, 10, MARGIN, y, "How to pay");
                    y -= LINE;
                    for (String line : branding.paymentInstructions().split("\\R")) {
                        if (y < MARGIN + LINE * 2) {
                            break;
                        }
                        text(content, regular, 10, MARGIN, y, fit(regular, line, right - MARGIN));
                        y -= LINE;
                    }
                }

                text(content, regular, 8, MARGIN, MARGIN, "Prepared with Solid");
            }

            // Anything that did not fit goes on its own continuation pages, so no charge is ever lost.
            List<BillingModels.InvoiceLine> remaining = overflow;
            while (!remaining.isEmpty()) {
                PDPage next = new PDPage(PDRectangle.LETTER);
                document.addPage(next);
                float nextRight = next.getMediaBox().getWidth() - MARGIN;
                int drawn = 0;
                try (PDPageContentStream content = new PDPageContentStream(document, next)) {
                    float y = next.getMediaBox().getHeight() - MARGIN;
                    text(content, bold, 12, MARGIN, y, "INVOICE " + (invoice.invoiceNumber() == null
                            ? "(draft)" : invoice.invoiceNumber()) + " — continued");
                    y -= LINE * 2;
                    for (BillingModels.InvoiceLine line : remaining) {
                        if (y < MARGIN + LINE * 2) {
                            break;
                        }
                        text(content, regular, 10, MARGIN, y, fit(regular, line.description(), DESCRIPTION_WIDTH));
                        text(content, regular, 10, MARGIN + 260, y,
                                line.quantity().stripTrailingZeros().toPlainString());
                        text(content, regular, 10, MARGIN + 320, y, money(line.unitPrice()));
                        textRight(content, regular, 10, nextRight, y, money(line.amount()));
                        y -= LINE;
                        drawn++;
                    }
                    text(content, regular, 8, MARGIN, MARGIN, "Prepared with Solid");
                }
                remaining = remaining.subList(drawn, remaining.size());
            }

            String watermark = switch (invoice.status()) {
                case "draft" -> "DRAFT";
                case "void" -> "VOID";
                default -> null;
            };
            if (watermark != null) {
                stamp(document, page, watermark);
            }

            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render the invoice", e);
        }
    }

    private static void total(PDPageContentStream content, PDType1Font labelFont, PDType1Font valueFont, float right,
                              float y, String label, Money amount) throws IOException {
        text(content, labelFont, 10, right - 200, y, label);
        textRight(content, valueFont, 10, right, y, money(amount) + " " + amount.currency());
    }

    private static String money(Money amount) {
        return amount.toDecimalString();
    }
}
