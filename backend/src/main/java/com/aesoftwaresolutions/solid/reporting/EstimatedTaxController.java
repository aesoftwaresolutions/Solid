package com.aesoftwaresolutions.solid.reporting;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Quarterly set-aside worksheet: what to save toward self-employment and income tax (spec 067). */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/entities/{entityId}/reports")
class EstimatedTaxController {

    private final EstimatedTaxService service;

    EstimatedTaxController(EstimatedTaxService service) {
        this.service = service;
    }

    @GetMapping("/estimated-tax")
    EstimatedTaxService.Result worksheet(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                         @RequestParam int taxYear,
                                         @RequestParam(required = false) BigDecimal marginalRatePercent) {
        return service.worksheet(orgId, entityId, taxYear, marginalRatePercent);
    }
}
