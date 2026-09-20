package com.aesoftwaresolutions.solid.closing;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/entities/{entityId}")
class YearEndController {

    private final YearEndService yearEnd;

    YearEndController(YearEndService yearEnd) {
        this.yearEnd = yearEnd;
    }

    @GetMapping("/reports/year-end-checklist")
    YearEndService.Checklist checklist(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                       @RequestParam int taxYear) {
        return yearEnd.checklist(orgId, entityId, taxYear);
    }
}
