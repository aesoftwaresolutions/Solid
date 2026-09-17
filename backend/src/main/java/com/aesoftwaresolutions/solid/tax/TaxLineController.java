package com.aesoftwaresolutions.solid.tax;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tax-lines")
class TaxLineController {

    private final TaxLineCatalog catalog;

    TaxLineController(TaxLineCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    List<TaxLine> list(@RequestParam(required = false) String form) {
        return form == null ? catalog.all() : catalog.forForm(form);
    }
}
