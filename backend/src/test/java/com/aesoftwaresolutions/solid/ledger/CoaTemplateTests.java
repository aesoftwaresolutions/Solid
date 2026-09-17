package com.aesoftwaresolutions.solid.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.tax.TaxLine;
import com.aesoftwaresolutions.solid.tax.TaxLineCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Spec 004, AC 5: the template is internally consistent with the tax line catalog. No database needed. */
class CoaTemplateTests {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TaxLineCatalog catalog = new TaxLineCatalog(mapper);
    private final CoaTemplates templates = new CoaTemplates(mapper);

    @Test
    void scheduleCTemplateUsesOnlyKnownCompatibleTaxLines() {
        CoaTemplates.Template template = templates.find("schedule_c").orElseThrow();
        Set<String> codes = new HashSet<>();

        for (CoaTemplates.TemplateAccount a : template.accounts()) {
            assertThat(codes.add(a.code())).as("duplicate code " + a.code()).isTrue();
            if (a.parentCode() != null) {
                assertThat(codes).as(a.code() + " parent must appear earlier").contains(a.parentCode());
            }
            if (a.taxLineCode() != null) {
                TaxLine line = catalog.find(a.taxLineCode()).orElseThrow(
                        () -> new AssertionError(a.code() + " uses unknown tax line " + a.taxLineCode()));
                AccountType expected = line.kind() == TaxLine.Kind.income ? AccountType.income : AccountType.expense;
                assertThat(a.type()).as(a.code()).isEqualTo(expected);
            }
        }
    }

    @Test
    void unknownOrUnsafeTemplateNamesAreNotFound() {
        assertThat(templates.find("nope")).isEmpty();
        assertThat(templates.find("../application")).isEmpty();
    }
}
