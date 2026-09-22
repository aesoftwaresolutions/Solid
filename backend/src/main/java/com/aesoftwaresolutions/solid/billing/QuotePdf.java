package com.aesoftwaresolutions.solid.billing;

import static com.aesoftwaresolutions.solid.billing.Pdf.DESCRIPTION_WIDTH;
import static com.aesoftwaresolutions.solid.billing.Pdf.LINE;
import static com.aesoftwaresolutions.solid.billing.Pdf.MARGIN;
import static com.aesoftwaresolutions.solid.billing.Pdf.fit;
import static com.aesoftwaresolutions.solid.billing.Pdf.rule;
import static com.aesoftwaresolutions.solid.billing.Pdf.stamp;
import static com.aesoftwaresolutions.solid.billing.Pdf.text;
import static com.aesoftwaresolutions.solid.billing.Pdf.textRight;

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
 * Draws one quote on a page.
 *
 * <p>A quote is not a bill, and this document goes out of its way not to look like one: no due date, no
 * terms, no amount due, and a sentence saying in plain words what it is. Nobody should be able to pay from
 * it by mistake.
 */
final class QuotePdf {

    private QuotePdf() {
    }

    static byte[] render(LegalEntity entity, BillingModels.Customer customer, QuoteModels.Quote quote,
                         String invoiceNumber) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            PDType1Font regular = Pdf.regular();
            PDType1Font bold = Pdf.bold();
            float right = page.getMediaBox().getWidth() - MARGIN;

            List<QuoteModels.Line> overflow = List.of();
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                float y = page.getMediaBox().getHeight() - MARGIN;

                text(content, bold, 18, MARGIN, y, entity.legalName());
                y -= LINE * 2;
                text(content, bold, 14, MARGIN, y, "QUOTE " + quote.quoteNumber());
                y -= LINE + LINE / 2f;

                text(content, regular, 10, MARGIN, y, "For: " + customer.name());
                y -= LINE;
                if (customer.billingAddress() != null && !customer.billingAddress().isBlank()) {
                    for (String addressLine : customer.billingAddress().split("\\R")) {
                        text(content, regular, 10, MARGIN, y, addressLine);
                        y -= LINE;
                    }
                }
                y -= LINE / 2f;
                text(content, regular, 10, MARGIN, y, "Quoted " + quote.issueDate());
                y -= LINE;
                if (quote.validUntil() != null) {
                    text(content, regular, 10, MARGIN, y, "This price holds until " + quote.validUntil() + ".");
                    y -= LINE;
                }
                y -= LINE;

                text(content, bold, 10, MARGIN, y, "Description");
                text(content, bold, 10, MARGIN + 260, y, "Qty");
                text(content, bold, 10, MARGIN + 320, y, "Unit price");
                textRight(content, bold, 10, right, y, "Amount");
                y -= LINE;
                rule(content, MARGIN, right, y + 4);
                y -= LINE / 2f;

                for (QuoteModels.Line line : quote.lines()) {
                    // Room is kept for the total and the status sentence, so a long quote cannot run off the
                    // bottom of the page.
                    if (y < MARGIN + LINE * 7) {
                        text(content, regular, 9, MARGIN, y, "continued...");
                        overflow = quote.lines().subList(quote.lines().indexOf(line), quote.lines().size());
                        break;
                    }
                    text(content, regular, 10, MARGIN, y, fit(regular, line.description(), DESCRIPTION_WIDTH));
                    text(content, regular, 10, MARGIN + 260, y,
                            line.quantity().stripTrailingZeros().toPlainString());
                    text(content, regular, 10, MARGIN + 320, y, line.unitPrice().toDecimalString());
                    textRight(content, regular, 10, right, y, line.amount().toDecimalString());
                    y -= LINE;
                }

                y -= LINE / 2f;
                rule(content, MARGIN + 320, right, y + 8);
                y -= LINE / 2f;
                text(content, bold, 10, right - 200, y, "Total");
                textRight(content, bold, 10, right, y,
                        quote.total().toDecimalString() + " " + quote.total().currency());
                y -= LINE * 2;

                if (quote.memo() != null && !quote.memo().isBlank()) {
                    text(content, regular, 10, MARGIN, y, fit(regular, quote.memo(), right - MARGIN));
                    y -= LINE * 2;
                }

                text(content, bold, 11, MARGIN, y, statusSentence(quote, invoiceNumber));
                text(content, regular, 8, MARGIN, MARGIN, "Prepared with Solid");
            }

            List<QuoteModels.Line> remaining = overflow;
            while (!remaining.isEmpty()) {
                PDPage next = new PDPage(PDRectangle.LETTER);
                document.addPage(next);
                float nextRight = next.getMediaBox().getWidth() - MARGIN;
                int drawn = 0;
                try (PDPageContentStream content = new PDPageContentStream(document, next)) {
                    float y = next.getMediaBox().getHeight() - MARGIN;
                    text(content, bold, 12, MARGIN, y, "QUOTE " + quote.quoteNumber() + " — continued");
                    y -= LINE * 2;
                    for (QuoteModels.Line line : remaining) {
                        if (y < MARGIN + LINE * 2) {
                            break;
                        }
                        text(content, regular, 10, MARGIN, y, fit(regular, line.description(), DESCRIPTION_WIDTH));
                        text(content, regular, 10, MARGIN + 260, y,
                                line.quantity().stripTrailingZeros().toPlainString());
                        text(content, regular, 10, MARGIN + 320, y, line.unitPrice().toDecimalString());
                        textRight(content, regular, 10, nextRight, y, line.amount().toDecimalString());
                        y -= LINE;
                        drawn++;
                    }
                    text(content, regular, 8, MARGIN, MARGIN, "Prepared with Solid");
                }
                remaining = remaining.subList(drawn, remaining.size());
            }

            String watermark = switch (quote.status()) {
                case "expired" -> "EXPIRED";
                case "declined" -> "DECLINED";
                case "converted" -> "INVOICED";
                default -> null;
            };
            if (watermark != null) {
                stamp(document, page, watermark);
            }

            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render the quote", e);
        }
    }

    /** What this document is, in one sentence, so it can never be mistaken for a demand for money. */
    private static String statusSentence(QuoteModels.Quote quote, String invoiceNumber) {
        return switch (quote.status()) {
            case "draft" -> "This is a draft quote and has not been sent.";
            // "billed" rather than "invoiced": the word invoice belongs only on a quote that became one, so
            // nothing on this page can be read as a demand for money.
            case "sent" -> "This is a quote, not a bill. Nothing is owed until the work is agreed and billed.";
            case "accepted" -> "This quote has been accepted. A bill will follow; nothing is owed on this "
                    + "document.";
            case "declined" -> "This quote was declined"
                    + (quote.declinedReason() == null ? "." : ": " + quote.declinedReason());
            case "expired" -> "This quote expired on " + quote.validUntil() + " and the price is no longer held.";
            case "converted" -> "This quote became invoice "
                    + (invoiceNumber == null ? "(draft)" : invoiceNumber) + ". Pay against that invoice, not "
                    + "this quote.";
            default -> "This is a quote, not a bill.";
        };
    }
}
