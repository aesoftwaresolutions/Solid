package com.aesoftwaresolutions.solid.money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * An exact amount of money: a whole number of minor units (e.g. cents) in one ISO-4217 currency.
 *
 * <p>Immutable and safe to share. Never use float/double for money; always use this type.
 *
 * @param minorUnits amount in the currency's smallest unit (cents for USD)
 * @param currency   ISO-4217 currency code, e.g. "USD"
 */
public record Money(long minorUnits, String currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency");
        // Validates the code and normalises it (throws IllegalArgumentException for unknown codes).
        currency = Currency.getInstance(currency.toUpperCase()).getCurrencyCode();
        if (fractionDigits(currency) < 0) {
            throw new IllegalArgumentException("Currency has no minor unit definition: " + currency);
        }
    }

    // ---------- factories ----------

    public static Money zero(String currency) {
        return new Money(0, currency);
    }

    public static Money ofMinor(long minorUnits, String currency) {
        return new Money(minorUnits, currency);
    }

    /** Parses an exact decimal string. Rejects values with more decimals than the currency allows. */
    public static Money of(String decimal, String currency) {
        return of(new BigDecimal(decimal), currency, RoundingMode.UNNECESSARY);
    }

    /** Converts a decimal, rounding to the currency's minor unit with the given mode. */
    public static Money of(BigDecimal amount, String currency, RoundingMode roundingMode) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(roundingMode, "roundingMode");
        String code = Currency.getInstance(currency.toUpperCase()).getCurrencyCode();
        BigDecimal scaled;
        try {
            scaled = amount.setScale(fractionDigits(code), roundingMode);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Amount " + amount.toPlainString() + " has more decimal places than " + code + " allows", e);
        }
        return new Money(scaled.unscaledValue().longValueExact(), code);
    }

    // ---------- arithmetic ----------

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(minorUnits), currency);
    }

    public Money abs() {
        return minorUnits < 0 ? negate() : this;
    }

    /** Multiplies by an exact factor (e.g. a tax rate). The rounding mode must always be stated. */
    public Money multiply(BigDecimal factor, RoundingMode roundingMode) {
        Objects.requireNonNull(factor, "factor");
        BigDecimal result = toBigDecimal().multiply(factor);
        return of(result, currency, roundingMode);
    }

    /**
     * Splits this amount into parts proportional to the ratios, using the largest-remainder method.
     * The parts always add up exactly to this amount. Ties go to the earlier part.
     */
    public List<Money> allocate(long... ratios) {
        if (ratios == null || ratios.length == 0) {
            throw new IllegalArgumentException("At least one ratio is required");
        }
        BigInteger total = BigInteger.ZERO;
        for (long ratio : ratios) {
            if (ratio < 0) {
                throw new IllegalArgumentException("Ratios must not be negative");
            }
            total = total.add(BigInteger.valueOf(ratio));
        }
        if (total.signum() == 0) {
            throw new IllegalArgumentException("Ratios must not all be zero");
        }

        BigInteger amount = BigInteger.valueOf(Math.abs(minorUnits));
        long[] parts = new long[ratios.length];
        BigInteger[] remainders = new BigInteger[ratios.length];
        long allocated = 0;
        for (int i = 0; i < ratios.length; i++) {
            BigInteger[] qr = amount.multiply(BigInteger.valueOf(ratios[i])).divideAndRemainder(total);
            parts[i] = qr[0].longValueExact();
            remainders[i] = qr[1];
            allocated += parts[i];
        }

        long leftover = Math.abs(minorUnits) - allocated;
        Integer[] order = new Integer[ratios.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        // Largest remainder first; stable sort keeps earlier parts first on ties.
        Arrays.sort(order, (a, b) -> remainders[b].compareTo(remainders[a]));
        for (int i = 0; i < leftover; i++) {
            parts[order[i]]++;
        }

        List<Money> result = new ArrayList<>(parts.length);
        for (long part : parts) {
            result.add(new Money(minorUnits < 0 ? -part : part, currency));
        }
        return List.copyOf(result);
    }

    // ---------- queries ----------

    public boolean isZero() {
        return minorUnits == 0;
    }

    public boolean isPositive() {
        return minorUnits > 0;
    }

    public boolean isNegative() {
        return minorUnits < 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minorUnits, other.minorUnits);
    }

    public BigDecimal toBigDecimal() {
        return BigDecimal.valueOf(minorUnits, fractionDigits(currency));
    }

    /** Plain decimal string with exactly the currency's fraction digits, e.g. "-12.30". */
    public String toDecimalString() {
        return toBigDecimal().toPlainString();
    }

    @Override
    public String toString() {
        return toDecimalString() + " " + currency;
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    private static int fractionDigits(String code) {
        return Currency.getInstance(code).getDefaultFractionDigits();
    }
}
