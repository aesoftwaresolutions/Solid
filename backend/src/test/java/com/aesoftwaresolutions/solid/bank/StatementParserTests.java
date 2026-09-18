package com.aesoftwaresolutions.solid.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Spec 008, AC 1–2. */
class StatementParserTests {

    static String fixture(String name) {
        try (InputStream in = StatementParserTests.class.getResourceAsStream("/fixtures/bank/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void csvWithSignedAmountsQuotesAndParentheses() {
        List<ParsedTransaction> txns = CsvStatementParser.parse(fixture("checking-2026-09.csv"), CsvStatementParser.Hints.NONE, 100);

        assertThat(txns).hasSize(6); // zero-amount hold skipped
        assertThat(txns.get(0)).isEqualTo(new ParsedTransaction(null, LocalDate.of(2026, 9, 1), new BigDecimal("2500.00"), "ACME CLIENT LLC DEPOSIT"));
        assertThat(txns.get(2).description()).isEqualTo("STAPLES #1234, TAMPA FL");
        assertThat(txns.get(2).amount()).isEqualByComparingTo("-42.10");
        assertThat(txns.get(5).amount()).isEqualByComparingTo("-1200.00");
    }

    @Test
    void csvWithDebitAndCreditColumns() {
        List<ParsedTransaction> txns = CsvStatementParser.parse(fixture("card-debit-credit.csv"), CsvStatementParser.Hints.NONE, 100);
        assertThat(txns).extracting(ParsedTransaction::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("-54.99"), new BigDecimal("300.00"));
        assertThat(txns.get(0).date()).isEqualTo(LocalDate.of(2026, 9, 2));
    }

    @Test
    void csvHintsOverrideDetection() {
        String csv = "When,What,Value\n31/12/2026,Thing,-1.00\n";
        List<ParsedTransaction> txns = CsvStatementParser.parse(csv,
                new CsvStatementParser.Hints("When", "What", "Value", null, null, "dd/MM/yyyy"), 10);
        assertThat(txns.get(0).date()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void csvErrorsReportLineNumbers() {
        assertThatThrownBy(() -> CsvStatementParser.parse("Date,Description,Amount\n2026-01-01,A,1.00\n2026-13-45,B,2.00\n",
                CsvStatementParser.Hints.NONE, 10))
                .isInstanceOf(StatementParseException.class).hasMessageStartingWith("Line 3:");
        assertThatThrownBy(() -> CsvStatementParser.parse("Date,Description,Amount\n2026-01-01,A,1.2.3\n",
                CsvStatementParser.Hints.NONE, 10))
                .hasMessageStartingWith("Line 2:").hasMessageContaining("amount");
        assertThatThrownBy(() -> CsvStatementParser.parse("Foo,Bar\n1,2\n", CsvStatementParser.Hints.NONE, 10))
                .hasMessageContaining("date column");
        assertThatThrownBy(() -> CsvStatementParser.parse("Date,Description,Amount\n2026-01-01,\"unclosed,1.00\n",
                CsvStatementParser.Hints.NONE, 10)).hasMessageContaining("Unclosed quote");
    }

    @Test
    void csvRowLimit() {
        StringBuilder sb = new StringBuilder("Date,Description,Amount\n");
        for (int i = 0; i < 11; i++) {
            sb.append("2026-01-01,X,1.00\n");
        }
        assertThatThrownBy(() -> CsvStatementParser.parse(sb.toString(), CsvStatementParser.Hints.NONE, 10))
                .hasMessageContaining("Too many rows");
    }

    @Test
    void ofxSgmlWithUnclosedTags() {
        String ofx = fixture("checking-sgml.ofx");
        assertThat(OfxStatementParser.looksLikeOfx(ofx)).isTrue();
        List<ParsedTransaction> txns = OfxStatementParser.parse(ofx, 100);
        assertThat(txns).containsExactly(
                new ParsedTransaction("2026090101", LocalDate.of(2026, 9, 1), new BigDecimal("2500.00"), "ACME CLIENT LLC - INVOICE 1001"),
                new ParsedTransaction("2026090302", LocalDate.of(2026, 9, 3), new BigDecimal("-54.99"), "ADOBE *CREATIVE CLD"),
                new ParsedTransaction("2026090503", LocalDate.of(2026, 9, 5), new BigDecimal("-42.10"), "STAPLES & CO"));
    }

    @Test
    void qfxXml() {
        String qfx = fixture("card-xml.qfx");
        assertThat(OfxStatementParser.looksLikeOfx(qfx)).isTrue();
        List<ParsedTransaction> txns = OfxStatementParser.parse(qfx, 100);
        assertThat(txns).extracting(ParsedTransaction::fitId).containsExactly("CC-1", "CC-2");
        assertThat(txns.get(1).description()).isEqualTo("PAYMENT - THANK YOU");
    }

    @Test
    void ofxMissingFieldsIsAnError() {
        String bad = "OFXHEADER:100\n<OFX><BANKTRANLIST>\n<STMTTRN>\n<TRNAMT>1.00\n</BANKTRANLIST></OFX>";
        assertThatThrownBy(() -> OfxStatementParser.parse(bad, 10)).hasMessageContaining("missing FITID");
    }

    @Test
    void amountsParseExactly() {
        assertThat(Amounts.parse("$1,234.56", 1)).isEqualByComparingTo("1234.56");
        assertThat(Amounts.parse("(12.34)", 1)).isEqualByComparingTo("-12.34");
        assertThat(Amounts.parse("12.34-", 1)).isEqualByComparingTo("-12.34");
        assertThat(Amounts.parse("+.5", 1)).isEqualByComparingTo("0.5");
        assertThat(Amounts.parse(" ", 1)).isNull();
        assertThatThrownBy(() -> Amounts.parse("1e5", 7)).hasMessageStartingWith("Line 7:");
        assertThatThrownBy(() -> Amounts.parse("--5", 1)).isInstanceOf(StatementParseException.class);
    }

    @Test
    void normalizationDropsVaryingDigits() {
        assertThat(BankService.normalize("ADOBE *CREATIVE CLD 800-833-6687 CA")).isEqualTo("ADOBE CREATIVE CLD CA");
        assertThat(BankService.normalize("ADOBE *CREATIVE CLD 800-555-0101 CA")).isEqualTo("ADOBE CREATIVE CLD CA");
        assertThat(BankService.normalize("1234")).isEqualTo("(BLANK)");
    }
}
