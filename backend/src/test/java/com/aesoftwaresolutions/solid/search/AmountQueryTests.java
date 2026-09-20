package com.aesoftwaresolutions.solid.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Spec 042: reading a query as an amount, without ever touching a double. */
class AmountQueryTests {

    @Test
    void readsTheAmountsPeopleActuallyType() {
        assertThat(SearchService.amountOf("420")).isEqualTo(42000L);
        assertThat(SearchService.amountOf("$420.00")).isEqualTo(42000L);
        assertThat(SearchService.amountOf("1,250.50")).isEqualTo(125050L);
        assertThat(SearchService.amountOf("0.1")).isEqualTo(10L);
        // The sign is dropped: a payment and a receipt of the same size are equally likely to be wanted.
        assertThat(SearchService.amountOf("-420.00")).isEqualTo(42000L);
    }

    @Test
    void anythingElseIsJustText() {
        assertThat(SearchService.amountOf("printer")).isNull();
        assertThat(SearchService.amountOf("420.000")).isNull();
        assertThat(SearchService.amountOf("4 2 0 x")).isNull();
        assertThat(SearchService.amountOf("")).isNull();
    }
}
