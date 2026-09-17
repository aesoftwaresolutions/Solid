package com.aesoftwaresolutions.solid.reporting;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/entities/{entityId}/reports")
class ReportController {

    private final ReportService reports;

    ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/trial-balance")
    Reports.TrialBalance trialBalance(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                      @RequestParam LocalDate asOf) {
        return reports.trialBalance(orgId, entityId, asOf);
    }

    @GetMapping("/profit-and-loss")
    Reports.ProfitAndLoss profitAndLoss(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                        @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return reports.profitAndLoss(orgId, entityId, from, to);
    }

    @GetMapping("/balance-sheet")
    Reports.BalanceSheet balanceSheet(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                      @RequestParam LocalDate asOf) {
        return reports.balanceSheet(orgId, entityId, asOf);
    }
}
