package com.aesoftwaresolutions.solid.bank;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads OFX 1.x (SGML, closing tags often omitted), OFX 2.x (XML) and Quicken QFX files. Only the fields needed
 * for bookkeeping are extracted from each {@code <STMTTRN>} block.
 */
final class OfxStatementParser {

    private static final Pattern START = Pattern.compile("<STMTTRN>", Pattern.CASE_INSENSITIVE);
    private static final Pattern END = Pattern.compile("</STMTTRN>|<STMTTRN>|</BANKTRANLIST>", Pattern.CASE_INSENSITIVE);

    private OfxStatementParser() {
    }

    static boolean looksLikeOfx(String content) {
        String head = content.substring(0, Math.min(content.length(), 2000)).toUpperCase(Locale.ROOT);
        return head.contains("OFXHEADER") || head.contains("<OFX>") || head.contains("<?OFX");
    }

    static List<ParsedTransaction> parse(String content, int maxRows) {
        List<ParsedTransaction> result = new ArrayList<>();
        Matcher start = START.matcher(content);
        int searchFrom = 0;
        while (start.find(searchFrom)) {
            int blockStart = start.end();
            Matcher end = END.matcher(content);
            int blockEnd = end.find(blockStart) ? end.start() : content.length();
            String block = content.substring(blockStart, blockEnd);
            searchFrom = blockEnd;
            int line = lineOf(content, start.start());

            if (result.size() >= maxRows) {
                throw new StatementParseException(0, "Too many transactions (maximum " + maxRows + ")");
            }
            String fitId = value(block, "FITID");
            String dateText = value(block, "DTPOSTED");
            String amountText = value(block, "TRNAMT");
            if (fitId == null || dateText == null || amountText == null) {
                throw new StatementParseException(line, "Transaction is missing FITID, DTPOSTED or TRNAMT");
            }
            LocalDate date;
            try {
                date = LocalDate.parse(dateText.substring(0, Math.min(8, dateText.length())), DateTimeFormatter.BASIC_ISO_DATE);
            } catch (DateTimeParseException e) {
                throw new StatementParseException(line, "Can't read DTPOSTED '" + dateText + "'");
            }
            BigDecimal amount = Amounts.parse(amountText, line);
            if (amount == null || amount.signum() == 0) {
                continue;
            }
            String name = value(block, "NAME");
            String memo = value(block, "MEMO");
            String description = name != null ? name : memo != null ? memo : "(no description)";
            if (name != null && memo != null && !memo.equalsIgnoreCase(name)) {
                description = name + " - " + memo;
            }
            result.add(new ParsedTransaction(fitId, date, amount, unescape(description)));
        }
        if (result.isEmpty() && !content.toUpperCase(Locale.ROOT).contains("<BANKTRANLIST>")) {
            throw new StatementParseException(0, "No transactions found; is this an OFX/QFX statement?");
        }
        return result;
    }

    /** Value of {@code <TAG>value} (SGML) or {@code <TAG>value</TAG>} (XML). */
    private static String value(String block, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">([^<\\r\\n]*)", Pattern.CASE_INSENSITIVE).matcher(block);
        if (!m.find()) {
            return null;
        }
        String v = m.group(1).trim();
        return v.isEmpty() ? null : v;
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    private static int lineOf(String s, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (s.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
