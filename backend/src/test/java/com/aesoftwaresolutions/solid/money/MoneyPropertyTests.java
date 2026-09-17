package com.aesoftwaresolutions.solid.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;

/** Property-based tests for spec 002, AC 6. */
class MoneyPropertyTests {

    @Property(tries = 2000)
    void allocationAlwaysSumsToOriginal(@ForAll @LongRange(min = -1_000_000_000_000L, max = 1_000_000_000_000L) long minor,
                                        @ForAll("ratios") long[] ratios) {
        Money original = Money.ofMinor(minor, "USD");
        List<Money> parts = original.allocate(ratios);

        long sum = parts.stream().mapToLong(Money::minorUnits).sum();
        assertThat(sum).isEqualTo(minor);
    }

    @Property(tries = 2000)
    void eachPartIsWithinOneMinorUnitOfExactShare(@ForAll @LongRange(min = -1_000_000_000L, max = 1_000_000_000L) long minor,
                                                  @ForAll("ratios") long[] ratios) {
        List<Money> parts = Money.ofMinor(minor, "USD").allocate(ratios);
        BigInteger total = BigInteger.ZERO;
        for (long r : ratios) {
            total = total.add(BigInteger.valueOf(r));
        }
        for (int i = 0; i < ratios.length; i++) {
            BigDecimal exact = new BigDecimal(BigInteger.valueOf(minor).multiply(BigInteger.valueOf(ratios[i])))
                    .divide(new BigDecimal(total), 10, java.math.RoundingMode.HALF_EVEN);
            BigDecimal diff = exact.subtract(BigDecimal.valueOf(parts.get(i).minorUnits())).abs();
            assertThat(diff).isLessThan(BigDecimal.ONE);
        }
    }

    @Property(tries = 2000)
    void addThenSubtractIsIdentity(@ForAll @LongRange(min = -1_000_000_000_000L, max = 1_000_000_000_000L) long a,
                                   @ForAll @LongRange(min = -1_000_000_000_000L, max = 1_000_000_000_000L) long b) {
        Money x = Money.ofMinor(a, "USD");
        Money y = Money.ofMinor(b, "USD");
        assertThat(x.add(y).subtract(y)).isEqualTo(x);
    }

    @Property(tries = 1000)
    void decimalStringRoundTrips(@ForAll @LongRange(min = -1_000_000_000_000L, max = 1_000_000_000_000L) long minor) {
        Money m = Money.ofMinor(minor, "USD");
        assertThat(Money.of(m.toDecimalString(), "USD")).isEqualTo(m);
    }

    @Provide
    Arbitrary<long[]> ratios() {
        return Arbitraries.longs().between(0, 1_000_000).array(long[].class).ofMinSize(1).ofMaxSize(12)
                .filter(arr -> java.util.Arrays.stream(arr).anyMatch(r -> r > 0));
    }
}
