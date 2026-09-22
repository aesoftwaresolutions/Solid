package com.aesoftwaresolutions.solid.billing;

import com.aesoftwaresolutions.solid.money.Money;
import com.aesoftwaresolutions.solid.org.Branding;
import com.aesoftwaresolutions.solid.org.LegalEntity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** The statement as one or more plain pages, in the same style as the invoice. */
final class StatementPdf {

    private static final int MARGIN = 54;
    private static final int LINE = 14;
    private static final int ROWS_PER_PAGE = 40;

    private StatementPdf() {
    }

    static byte[] render(LegalEntity entity, StatementService.Statement statement, Branding branding,
                         byte[] logo) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            var bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            int rows = Math.max(1, statement.lines().size());
            // The letterhead takes room off the first page, so that page carries fewer rows. Without this a
            // long statement would run off the bottom the moment someone filled in an address.
            int firstPageRows = Math.max(10, ROWS_PER_PAGE - letterheadLines(branding, logo));
            int pages = rows <= firstPageRows ? 1
                    : 1 + (rows - firstPageRows + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE;
            for (int pageNo = 0; pageNo < pages; pageNo++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                document.addPage(page);
                float right = page.getMediaBox().getWidth() - MARGIN;

                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    float y = page.getMediaBox().getHeight() - MARGIN;
                    text(content, bold, 18, MARGIN, y, entity.legalName());
                    y -= LINE + LINE / 2f;
                    if (pageNo == 0) {
                        y -= Pdf.letterhead(document, content, regular, y, right, branding, logo)
                                * Pdf.LETTERHEAD_LINE;
                    }
                    y -= LINE / 2f;
                    text(content, bold, 14, MARGIN, y, "STATEMENT");
                    y -= LINE + LINE / 2f;
                    text(content, regular, 10, MARGIN, y, "For: " + statement.customer().name());
                    y -= LINE;
                    text(content, regular, 10, MARGIN, y, "Period: " + statement.from() + " to " + statement.to());
                    y -= LINE * 2;

                    text(content, bold, 10, MARGIN, y, "Date");
                    text(content, bold, 10, MARGIN + 80, y, "Reference");
                    text(content, bold, 10, MARGIN + 220, y, "Charge");
                    text(content, bold, 10, MARGIN + 300, y, "Payment");
                    textRight(content, bold, 10, right, y, "Balance");
                    y -= LINE;
                    rule(content, MARGIN, right, y + 4);
                    y -= LINE / 2f;

                    if (pageNo == 0) {
                        text(content, regular, 10, MARGIN, y, "Brought forward");
                        textRight(content, regular, 10, right, y, money(statement.openingBalance()));
                        y -= LINE;
                    }

                    int start = pageNo == 0 ? 0 : firstPageRows + (pageNo - 1) * ROWS_PER_PAGE;
                    int end = Math.min(statement.lines().size(),
                            start + (pageNo == 0 ? firstPageRows : ROWS_PER_PAGE));
                    for (int i = start; i < end; i++) {
                        StatementService.Line line = statement.lines().get(i);
                        text(content, regular, 10, MARGIN, y, line.date().toString());
                        text(content, regular, 10, MARGIN + 80, y, line.reference());
                        if (line.charge().minorUnits() != 0) {
                            text(content, regular, 10, MARGIN + 220, y, money(line.charge()));
                        }
                        if (line.payment().minorUnits() != 0) {
                            text(content, regular, 10, MARGIN + 300, y, money(line.payment()));
                        }
                        textRight(content, regular, 10, right, y, money(line.balance()));
                        y -= LINE;
                    }

                    if (pageNo == pages - 1) {
                        y -= LINE / 2f;
                        rule(content, MARGIN + 300, right, y + 8);
                        y -= LINE / 2f;
                        text(content, bold, 11, MARGIN + 220, y, "Amount due");
                        textRight(content, bold, 11, right, y,
                                money(statement.closingBalance()) + " " + statement.currency());
                        y -= LINE * 2;
                        if (branding != null && branding.paymentInstructions() != null
                                && !branding.paymentInstructions().isBlank()) {
                            text(content, regular, 10, MARGIN, y, "How to pay");
                            y -= LINE;
                            for (String instruction : branding.paymentInstructions().split("\\R")) {
                                if (y < MARGIN + LINE * 2) {
                                    break;
                                }
                                text(content, regular, 10, MARGIN, y, instruction);
                                y -= LINE;
                            }
                        }
                    }

                    text(content, regular, 8, MARGIN, MARGIN, "Prepared with Solid");
                }
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render the statement", e);
        }
    }

    /** How many rows' worth of height the letterhead uses on the first page. */
    private static int letterheadLines(Branding branding, byte[] logo) {
        int lines = 0;
        if (branding != null) {
            if (branding.address() != null && !branding.address().isBlank()) {
                lines += branding.address().split("\\R").length;
            }
            if (!branding.contactLine().isBlank()) {
                lines++;
            }
            if (branding.taxId() != null && !branding.taxId().isBlank()) {
                lines++;
            }
            if (branding.paymentInstructions() != null && !branding.paymentInstructions().isBlank()) {
                lines += branding.paymentInstructions().split("\\R").length + 2;
            }
        }
        if (logo != null && logo.length > 0) {
            lines = Math.max(lines, Pdf.LOGO_HEIGHT / Pdf.LETTERHEAD_LINE);
        }
        return lines;
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
