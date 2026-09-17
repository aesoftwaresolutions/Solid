package com.aesoftwaresolutions.solid.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

/** Example-based tests for spec 002. Expected values are hand-computed. */
class MoneyTests {

    @Test
    void ac1_parsesExactDecimalIntoMinorUnits() {
        assertThat(Money.of("12.34", "USD").minorUnits()).isEqualTo(1234);
        assertThat(Money.of("-0.05", "usd")).isEqualTo(Money.ofMinor(-5, "USD"));
        assertThat(Money.of("7", "USD").minorUnits()).isEqualTo(700);
        assertThat(Money.of("100", "JPY").minorUnits()).isEqualTo(100); // JPY has no minor unit
    }

    @Test
    void ac1_rejectsTooManyDecimalsUnlessRoundingModeGiven() {
        assertThatThrownBy(() -> Money.of("12.345", "USD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more decimal places");
        assertThat(Money.of(new BigDecimal("12.345"), "USD", RoundingMode.HALF_UP).minorUnits()).isEqualTo(1235);
        assertThat(Money.of(new BigDecimal("12.345"), "USD", RoundingMode.HALF_EVEN).minorUnits()).isEqualTo(1234);
    }

    @Test
    void rejectsUnknownCurrency() {
        assertThatThrownBy(() -> Money.of("1.00", "XYZ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ac2_mixingCurrenciesFails() {
        Money usd = Money.of("1.00", "USD");
        Money eur = Money.of("1.00", "EUR");
        assertThatThrownBy(() -> usd.add(eur)).isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> usd.subtract(eur)).isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> usd.compareTo(eur)).isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void ac3_overflowThrowsInsteadOfWrapping() {
        Money max = Money.ofMinor(Long.MAX_VALUE, "USD");
        assertThatThrownBy(() -> max.add(Money.ofMinor(1, "USD"))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Money.ofMinor(Long.MIN_VALUE, "USD").negate()).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void ac4_multiplyRequiresExplicitRounding() {
        Money price = Money.of("10.00", "USD");
        assertThat(price.multiply(new BigDecimal("0.0725"), RoundingMode.HALF_UP)).isEqualTo(Money.of("0.73", "USD"));
        assertThat(price.multiply(new BigDecimal("0.0725"), RoundingMode.DOWN)).isEqualTo(Money.of("0.72", "USD"));
        assertThatThrownBy(() -> price.multiply(new BigDecimal("0.0725"), RoundingMode.UNNECESSARY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ac5_allocatesEvenlyWithRemainderToEarlierParts() {
        assertThat(Money.of("100.00", "USD").allocate(1, 1, 1)).containsExactly(
                Money.of("33.34", "USD"), Money.of("33.33", "USD"), Money.of("33.33", "USD"));
    }

    @Test
    void ac5_allocatesByRatioUsingLargestRemainder() {
        // $0.05 split 30/70: exact shares 1.5 and 3.5 cents; tie on remainder goes to the first part.
        assertThat(Money.of("0.05", "USD").allocate(30, 70))
                .containsExactly(Money.of("0.02", "USD"), Money.of("0.03", "USD"));
        // $10.00 split 1:2:3: exact 1.6667, 3.3333, 5.0 → remainders favour part 1.
        assertThat(Money.of("10.00", "USD").allocate(1, 2, 3))
                .containsExactly(Money.of("1.67", "USD"), Money.of("3.33", "USD"), Money.of("5.00", "USD"));
    }

    @Test
    void ac5_allocatesNegativeAmounts() {
        assertThat(Money.of("-100.00", "USD").allocate(1, 1, 1)).containsExactly(
                Money.of("-33.34", "USD"), Money.of("-33.33", "USD"), Money.of("-33.33", "USD"));
    }

    @Test
    void ac5_rejectsInvalidRatios() {
        Money m = Money.of("1.00", "USD");
        assertThatThrownBy(m::allocate).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> m.allocate(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> m.allocate(1, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ac8_decimalStringNeverUsesScientificNotation() {
        assertThat(Money.ofMinor(100_000_000_000L, "USD").toDecimalString()).isEqualTo("1000000000.00");
        assertThat(Money.of("1E+3", "USD").toDecimalString()).isEqualTo("1000.00");
        assertThat(Money.ofMinor(-5, "USD").toString()).isEqualTo("-0.05 USD");
    }
}
