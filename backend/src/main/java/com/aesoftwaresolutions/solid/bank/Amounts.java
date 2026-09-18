package com.aesoftwaresolutions.solid.bank;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Parses money text from bank files ("$1,234.56", "(12.34)", "-12.34", "12.34-") exactly, without floating point. */
final class Amounts {

    private static final Pattern VALID = Pattern.compile("^\\d+(\\.\\d+)?$");

    private Amounts() {
    }

    static BigDecimal parse(String raw, int line) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().replace("$", "").replace(",", "").replace(" ", "");
        boolean negative = false;
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true;
            s = s.substring(1, s.length() - 1);
        }
        if (s.endsWith("-")) {
            negative = !negative;
            s = s.substring(0, s.length() - 1);
        }
        if (s.startsWith("+")) {
            s = s.substring(1);
        } else if (s.startsWith("-")) {
            negative = !negative;
            s = s.substring(1);
        }
        if (s.startsWith(".")) {
            s = "0" + s;
        }
        if (!VALID.matcher(s).matches()) {
            throw new StatementParseException(line, "Can't read amount '" + raw + "'");
        }
        BigDecimal value = new BigDecimal(s);
        return negative ? value.negate() : value;
    }
}
