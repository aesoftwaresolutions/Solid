package com.aesoftwaresolutions.solid.billing;

import com.aesoftwaresolutions.solid.money.Money;
import com.aesoftwaresolutions.solid.org.LegalEntity;
import java.util.List;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;

/**
 * Draws one invoice on one page: plain, readable, and honest about a draft or a voided invoice.
 *
 * <p>Deliberately simple — one font, no logo. Branding is its own slice; getting the numbers and the status right
 * is this one.
 */
final class InvoicePdf {

    // Points, as PDF measures things (72 to the inch). Held as ints because the project forbids float fields —
    // that rule is about money, and these are coordinates, but there is no reason to make an exception.
    private static final int MARGIN = 54;      // 0.75 inch
    private static final int LINE = 14;
    private static final int DESCRIPTION_WIDTH = 250;

    private InvoicePdf() {
    }

    static byte[] render(LegalEntity entity, BillingModels.Customer customer, BillingModels.Invoice invoice) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            var regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            var bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            float right = page.getMediaBox().getWidth() - MARGIN;

            List<BillingModels.InvoiceLine> overflow = List.of();
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                float y = page.getMediaBox().getHeight() - MARGIN;

                text(content, bold, 18, MARGIN, y, entity.legalName());
                y -= LINE * 2;
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
                    if (y < MARGIN + LINE * 8) {
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

    /** Big pale letters across the page, so nobody mistakes a draft or a voided invoice for a real one. */
    private static void stamp(PDDocument document, PDPage page, String word) throws IOException {
        try (PDPageContentStream content = new PDPageContentStream(document, page,
                PDPageContentStream.AppendMode.APPEND, true, true)) {
            PDExtendedGraphicsState state = new PDExtendedGraphicsState();
            state.setNonStrokingAlphaConstant(0.15f);
            content.setGraphicsStateParameters(state);
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 90);
            content.setTextMatrix(org.apache.pdfbox.util.Matrix.getRotateInstance(Math.toRadians(35), 120, 260));
            content.showText(word);
            content.endText();
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

    private static void text(PDPageContentStream content, PDType1Font font, float size, float x, float y, String value)
            throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(clean(value));
        content.endText();
    }

    private static void textRight(PDPageContentStream content, PDType1Font font, float size, float right, float y,
                                  String value) throws IOException {
        String cleaned = clean(value);
        float width = font.getStringWidth(cleaned) / 1000 * size;
        text(content, font, size, right - width, y, cleaned);
    }

    private static void rule(PDPageContentStream content, float from, float to, float y) throws IOException {
        content.setLineWidth(0.5f);
        content.moveTo(from, y);
        content.lineTo(to, y);
        content.stroke();
    }

    /** Cuts a value to the width it has, so a long description cannot run into the next column. */
    private static String fit(PDType1Font font, String value, float maxWidth) throws IOException {
        String cleaned = clean(value);
        if (font.getStringWidth(cleaned) / 1000 * 10 <= maxWidth) {
            return cleaned;
        }
        StringBuilder shortened = new StringBuilder();
        for (char c : cleaned.toCharArray()) {
            if (font.getStringWidth(shortened.toString() + c + "...") / 1000 * 10 > maxWidth) {
                break;
            }
            shortened.append(c);
        }
        return shortened + "...";
    }

    /** The standard PDF fonts only speak WinAnsi; anything else becomes '?' rather than an exception. */
    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            out.append(c >= 32 && c <= 255 ? c : '?');
        }
        return out.toString();
    }
}
