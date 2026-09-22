package com.aesoftwaresolutions.solid.billing;

import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;

/**
 * The drawing this module's documents share: text, right-aligned text, rules, truncation, the WinAnsi
 * clean-up and the watermark. One copy, so a fix to the invoice's layout is a fix to the quote's too.
 *
 * <p>Sizes are points, as PDF measures things (72 to the inch), and are held as ints: the project forbids
 * floating-point fields, and while that rule is about money there is no reason to make an exception here.
 */
final class Pdf {

    static final int MARGIN = 54;      // 0.75 inch
    static final int LINE = 14;
    static final int DESCRIPTION_WIDTH = 250;

    private Pdf() {
    }

    static PDType1Font regular() {
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }

    static PDType1Font bold() {
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    }

    static void text(PDPageContentStream content, PDType1Font font, float size, float x, float y, String value)
            throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(clean(value));
        content.endText();
    }

    static void textRight(PDPageContentStream content, PDType1Font font, float size, float right, float y,
                          String value) throws IOException {
        String cleaned = clean(value);
        float width = font.getStringWidth(cleaned) / 1000 * size;
        text(content, font, size, right - width, y, cleaned);
    }

    static void rule(PDPageContentStream content, float from, float to, float y) throws IOException {
        content.setLineWidth(0.5f);
        content.moveTo(from, y);
        content.lineTo(to, y);
        content.stroke();
    }

    /** Cuts a value to the width it has, so a long description cannot run into the next column. */
    static String fit(PDType1Font font, String value, float maxWidth) throws IOException {
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
    static String clean(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            out.append(c >= 32 && c <= 255 ? c : '?');
        }
        return out.toString();
    }

    /** Big pale letters across the page, so nobody mistakes one kind of document for another. */
    static void stamp(PDDocument document, PDPage page, String word) throws IOException {
        try (PDPageContentStream content = new PDPageContentStream(document, page,
                PDPageContentStream.AppendMode.APPEND, true, true)) {
            PDExtendedGraphicsState state = new PDExtendedGraphicsState();
            state.setNonStrokingAlphaConstant(0.15f);
            content.setGraphicsStateParameters(state);
            content.beginText();
            content.setFont(bold(), 90);
            content.setTextMatrix(org.apache.pdfbox.util.Matrix.getRotateInstance(Math.toRadians(35), 120, 260));
            content.showText(word);
            content.endText();
        }
    }
}
