package com.aesoftwaresolutions.solid.tax;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** What this installation knows about US tax figures, and what it does not. */
@RestController
@RequestMapping("/api/v1/tax")
class TaxRulePackController {

    private final TaxRulePacks packs;

    TaxRulePackController(TaxRulePacks packs) {
        this.packs = packs;
    }

    @GetMapping("/rule-packs")
    List<TaxRulePacks.RulePack> list() {
        return packs.all();
    }

    @GetMapping("/rule-coverage")
    TaxRulePacks.CoverageReport coverage(@RequestParam int taxYear) {
        return packs.coverage(taxYear);
    }
}
