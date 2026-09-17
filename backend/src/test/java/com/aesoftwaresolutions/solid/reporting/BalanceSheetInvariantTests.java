package com.aesoftwaresolutions.solid.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.ledger.Account;
import com.aesoftwaresolutions.solid.ledger.AccountService;
import com.aesoftwaresolutions.solid.ledger.JournalService;
import com.aesoftwaresolutions.solid.money.Money;
import com.aesoftwaresolutions.solid.org.OrgService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Spec 006 AC 1, 4, 6: invariants that must hold for any balanced ledger. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BalanceSheetInvariantTests {

    @Autowired
    OrgService orgs;
    @Autowired
    AccountService accounts;
    @Autowired
    JournalService journal;
    @Autowired
    ReportService reports;

    @Test
    void randomBalancedLedgersAlwaysProduceBalancedReports() {
        Random random = new Random(20260916L); // fixed seed = reproducible
        UUID org = orgs.createOrganization("Random ledgers", "business").id();

        for (int ledger = 0; ledger < 8; ledger++) {
            int fyEnd = 1 + random.nextInt(12);
            UUID entity = orgs.createEntity(org, "smllc", "Random " + ledger, fyEnd, null, null, null).id();
            List<Account> postable = accounts.applyTemplate(org, entity, "schedule_c").stream()
                    .filter(a -> !a.isHeader()).toList();

            for (int i = 0; i < 40; i++) {
                LocalDate date = LocalDate.of(2025, 1, 1).plusDays(random.nextInt(730));
                int lineCount = 2 + random.nextInt(3);
                List<JournalService.NewLine> lines = new ArrayList<>();
                long sum = 0;
                for (int l = 0; l < lineCount - 1; l++) {
                    long minor = (random.nextInt(2_000_000) + 1) * (random.nextBoolean() ? 1L : -1L);
                    sum += minor;
                    lines.add(new JournalService.NewLine(postable.get(random.nextInt(postable.size())).id(),
                            Money.ofMinor(minor, "USD"), null));
                }
                if (sum == 0) {
                    continue;
                }
                lines.add(new JournalService.NewLine(postable.get(random.nextInt(postable.size())).id(),
                        Money.ofMinor(-sum, "USD"), null));
                journal.create(org, entity, date, "random", true, lines, null);
            }

            for (LocalDate asOf : List.of(LocalDate.of(2025, 6, 30), LocalDate.of(2026, 3, 15), LocalDate.of(2026, 12, 31))) {
                Reports.TrialBalance tb = reports.trialBalance(org, entity, asOf);
                assertThat(tb.totalDebit()).as("TB ledger %d as of %s", ledger, asOf).isEqualTo(tb.totalCredit());

                Reports.BalanceSheet bs = reports.balanceSheet(org, entity, asOf);
                assertThat(bs.balanced()).as("BS ledger %d (FY end %d) as of %s", ledger, fyEnd, asOf).isTrue();

                // Current-year earnings on the balance sheet equal P&L for the same fiscal-year-to-date period.
                Reports.ProfitAndLoss ytd = reports.profitAndLoss(org, entity, bs.fiscalYearStart(), asOf);
                Money currentEarnings = bs.equity().rows().stream()
                        .filter(r -> r.name().equals("Current Year Earnings"))
                        .map(Reports.Row::amount).findFirst().orElse(Money.zero("USD"));
                assertThat(currentEarnings).isEqualTo(ytd.netIncome());
            }
        }
    }

    @Test
    void fiscalYearStartFollowsFiscalYearEndMonth() {
        assertThat(ReportService.fiscalYearStart(LocalDate.of(2026, 9, 30), 6)).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(ReportService.fiscalYearStart(LocalDate.of(2026, 6, 30), 6)).isEqualTo(LocalDate.of(2025, 7, 1));
        assertThat(ReportService.fiscalYearStart(LocalDate.of(2026, 7, 1), 6)).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(ReportService.fiscalYearStart(LocalDate.of(2026, 12, 31), 12)).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(ReportService.fiscalYearStart(LocalDate.of(2026, 1, 1), 12)).isEqualTo(LocalDate.of(2026, 1, 1));
    }
}
