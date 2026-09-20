package com.aesoftwaresolutions.solid.reporting;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}")
class OverviewController {

    private final OverviewService overview;

    OverviewController(OverviewService overview) {
        this.overview = overview;
    }

    /** Defaults to the year so far, which is what someone asking "how are we doing?" usually means. */
    @GetMapping("/overview")
    OverviewService.Overview overview(
            @PathVariable UUID orgId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.withDayOfYear(1);
        return overview.overview(orgId, start, end);
    }
}
