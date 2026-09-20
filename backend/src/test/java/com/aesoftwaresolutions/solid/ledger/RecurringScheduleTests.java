package com.aesoftwaresolutions.solid.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Spec 026, the schedule itself. These are the cases the API tests cannot reach cheaply: a template that has been
 * running for decades, and what the per-run cap really means.
 */
class RecurringScheduleTests {

    private static RecurringModels.Recurring template(String frequency, String start, String end, int dayOfMonth) {
        return new RecurringModels.Recurring(UUID.randomUUID(), UUID.randomUUID(), "Rent", null, frequency,
                LocalDate.parse(start), end == null ? null : LocalDate.parse(end), dayOfMonth, true, null, List.of());
    }

    @Test
    void theCapIsPerRunNotPerLifetime() {
        RecurringModels.Recurring monthly = template("monthly", "1996-01-15", null, 15);

        List<LocalDate> first = RecurringService.dueDates(monthly, LocalDate.parse("2026-09-30"), null);
        assertThat(first).hasSize(120);
        assertThat(first.get(0)).isEqualTo(LocalDate.parse("1996-01-15"));

        // Once those months are posted, the next run must continue rather than stop forever.
        LocalDate lastPosted = first.get(119);
        List<LocalDate> next = RecurringService.dueDates(monthly, LocalDate.parse("2026-09-30"), lastPosted);
        assertThat(next).isNotEmpty();
        assertThat(next.get(0)).isEqualTo(lastPosted.plusMonths(1));
        assertThat(next).allMatch(date -> date.isAfter(lastPosted));
    }

    @Test
    void quarterlyStepsStayOnTheirOwnQuarters() {
        RecurringModels.Recurring quarterly = template("quarterly", "2026-01-10", null, 10);

        assertThat(RecurringService.dueDates(quarterly, LocalDate.parse("2027-01-31"), LocalDate.parse("2026-04-10")))
                .containsExactly(LocalDate.parse("2026-07-10"), LocalDate.parse("2026-10-10"),
                        LocalDate.parse("2027-01-10"));
    }

    @Test
    void anEndDateStopsIt() {
        RecurringModels.Recurring lease = template("monthly", "2026-01-01", "2026-03-01", 1);

        assertThat(RecurringService.dueDates(lease, LocalDate.parse("2026-12-31"), null))
                .containsExactly(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-01"),
                        LocalDate.parse("2026-03-01"));
    }

    @Test
    void shortMonthsClampRatherThanSpillOver() {
        RecurringModels.Recurring monthly = template("monthly", "2026-01-31", null, 31);

        assertThat(RecurringService.dueDates(monthly, LocalDate.parse("2026-04-30"), null))
                .containsExactly(LocalDate.parse("2026-01-31"), LocalDate.parse("2026-02-28"),
                        LocalDate.parse("2026-03-31"), LocalDate.parse("2026-04-30"));
    }
}
