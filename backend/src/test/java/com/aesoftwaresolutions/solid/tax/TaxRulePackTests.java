package com.aesoftwaresolutions.solid.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Spec 021, AC 3-5 and 7: the rules that keep invented tax figures out of the codebase. */
class TaxRulePackTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static TaxRulePacks.RulePack parse(String json) throws Exception {
        return TaxRulePacks.parse("test-pack", MAPPER.readTree(json));
    }

    @Test
    void ac3_aFileWithoutASourceIsRejected() {
        assertThatThrownBy(() -> parse("{\"thresholds\": [{\"taxYear\": 2026, \"amount\": \"2000.00\"}]}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test-pack")
                .hasMessageContaining("source");

        assertThatThrownBy(() -> parse("{\"source\": \"   \"}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("source");
    }

    @Test
    void ac4_duplicateOrImpossibleYearsAreRejected() {
        assertThatThrownBy(() -> parse("""
                {"source": "IRS Publication 1", "rates": [{"taxYear": 2025, "rate": "1"}, {"taxYear": 2025, "rate": "2"}]}"""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2025")
                .hasMessageContaining("twice");

        assertThatThrownBy(() -> parse("""
                {"source": "IRS Publication 1", "rates": [{"taxYear": 20255, "rate": "1"}]}"""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("typo");
    }

    @Test
    void ac5_appliesFromCoversThatYearAndEveryLaterOne() throws Exception {
        TaxRulePacks.RulePack pack = parse("""
                {"deduction": "Something", "source": "Rev. Proc. 2013-13", "appliesFromTaxYear": 2013}""");

        assertThat(pack.title()).isEqualTo("Something");
        assertThat(pack.covers(2012)).isFalse();
        assertThat(pack.covers(2013)).isTrue();
        assertThat(pack.covers(2030)).isTrue();
    }

    @Test
    void ac7_listedYearsAndTodosComeStraightFromTheFile() throws Exception {
        TaxRulePacks.RulePack pack = parse("""
                {"form": "1099-NEC", "source": "IRS instructions",
                 "thresholds": [{"taxYear": 2025, "amount": "600.00"}, {"taxYear": 2026, "amount": "2000.00"}],
                 "todo": ["2027 is indexed and not published yet"]}""");

        assertThat(pack.taxYears()).containsExactly(2025, 2026);
        assertThat(pack.covers(2027)).as("never interpolated").isFalse();
        assertThat(pack.todos()).containsExactly("2027 is indexed and not published yet");
        assertThat(pack.appliesFromTaxYear()).isNull();
    }

    @Test
    void separateListsMayEachMentionTheSameYear() throws Exception {
        TaxRulePacks.RulePack pack = parse("""
                {"source": "IRS Publication 1",
                 "federal": [{"taxYear": 2025, "rate": "1"}],
                 "state": [{"taxYear": 2025, "rate": "2"}]}""");

        assertThat(pack.taxYears()).containsExactly(2025);
        assertThat(List.of(pack.covers(2025), pack.covers(2024))).containsExactly(true, false);
    }
}
