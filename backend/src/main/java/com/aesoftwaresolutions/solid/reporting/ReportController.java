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
    private final TaxLineReportService taxLines;
    private final CashFlowService cashFlow;
    private final WhatsComingService whatsComing;
    private final BusinessLineReportService byBusinessLine;

    ReportController(ReportService reports, TaxLineReportService taxLines, CashFlowService cashFlow,
                     WhatsComingService whatsComing, BusinessLineReportService byBusinessLine) {
        this.whatsComing = whatsComing;
        this.byBusinessLine = byBusinessLine;
        this.reports = reports;
        this.taxLines = taxLines;
        this.cashFlow = cashFlow;
    }

    @GetMapping("/cash-flow")
    Reports.CashFlow cashFlow(@PathVariable UUID orgId, @PathVariable UUID entityId,
                              @RequestParam @org.springframework.format.annotation.DateTimeFormat(
                                      iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                              LocalDate from,
                              @RequestParam @org.springframework.format.annotation.DateTimeFormat(
                                      iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                              LocalDate to) {
        return cashFlow.cashFlow(orgId, entityId, from, to);
    }

    @GetMapping("/whats-coming")
    WhatsComingService.WhatsComing whatsComing(
            @PathVariable UUID orgId, @PathVariable UUID entityId,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate start = from == null ? LocalDate.now() : from;
        LocalDate end = to == null ? start.plusDays(90) : to;
        return whatsComing.whatsComing(orgId, entityId, start, end);
    }

    @GetMapping("/tax-lines")
    TaxLineReport taxLines(@PathVariable UUID orgId, @PathVariable UUID entityId, @RequestParam int taxYear) {
        return taxLines.report(orgId, entityId, taxYear);
    }

    @GetMapping(path = "/tax-lines.csv", produces = "text/csv")
    org.springframework.http.ResponseEntity<String> taxLinesCsv(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                                                @RequestParam int taxYear) {
        TaxLineReport report = taxLines.report(orgId, entityId, taxYear);
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"solid-tax-lines-" + taxYear + ".csv\"")
                .body(taxLines.csv(report));
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

    @GetMapping("/profit-and-loss-by-business-line")
    Reports.ProfitAndLossByBusinessLine profitAndLossByBusinessLine(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                                                    @RequestParam LocalDate from,
                                                                    @RequestParam LocalDate to) {
        return byBusinessLine.profitAndLoss(orgId, entityId, from, to);
    }

    @GetMapping(path = "/profit-and-loss-by-business-line.csv", produces = "text/csv")
    org.springframework.http.ResponseEntity<String> profitAndLossByBusinessLineCsv(
            @PathVariable UUID orgId, @PathVariable UUID entityId, @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        Reports.ProfitAndLossByBusinessLine report = byBusinessLine.profitAndLoss(orgId, entityId, from, to);
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition",
                        "attachment; filename=\"solid-pl-by-business-line-" + from + "-to-" + to + ".csv\"")
                .body(byBusinessLine.csv(report));
    }

    @GetMapping("/balance-sheet")
    Reports.BalanceSheet balanceSheet(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                      @RequestParam LocalDate asOf) {
        return reports.balanceSheet(orgId, entityId, asOf);
    }
}
