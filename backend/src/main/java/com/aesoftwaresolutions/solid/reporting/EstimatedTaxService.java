package com.aesoftwaresolutions.solid.reporting;

import com.aesoftwaresolutions.solid.money.Money;
import com.aesoftwaresolutions.solid.org.LegalEntity;
import com.aesoftwaresolutions.solid.org.OrgService;
import com.aesoftwaresolutions.solid.tax.TaxFigures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;

/**
 * The self-employment-tax arithmetic behind the quarterly set-aside worksheet (spec 067).
 *
 * <p>What never changes by year — the 92.35% net-earnings factor, the 12.4% Social Security rate, the 2.9%
 * Medicare rate and the $400 filing threshold — is statute (IRC §1401, §1402, §6017), so it lives in the
 * rule file with its source. What does change every year — the Social Security wage base — is looked up per
 * tax year: a runtime-supplied figure (spec 049) wins, then the shipped file, then <em>unknown</em>, and the
 * worksheet says so rather than reusing last year's number.
 *
 * <p>This is an estimate for setting money aside. It is not filed with anyone: it does not model the
 * Additional Medicare Tax (which needs household income Solid does not have), itemized deductions, the QBI
 * deduction or state tax. The worksheet says all of that in words next to the numbers.
 */
@org.springframework.stereotype.Service
public class EstimatedTaxService {

    private final OrgService orgs;
    private final TaxLineReportService taxLines;
    private final TaxFigures figures;

    /** The part of the rule file that never changes by year, loaded once. */
    private final BigDecimal netEarningsFactor;
    private final BigDecimal socialSecurityRate;
    private final BigDecimal medicareRate;
    private final BigDecimal halfFactor = new BigDecimal("0.5");
    private final String constantsSource;
    private final long filingThresholdMinor;
    private final List<WageBase> wageBases = new ArrayList<>();

    private record WageBase(int taxYear, Money amount, String source) {
    }

    EstimatedTaxService(OrgService orgs, TaxLineReportService taxLines, TaxFigures figures) {
        this.orgs = orgs;
        this.taxLines = taxLines;
        this.figures = figures;
        JsonNode root = read("tax-rules/us-self-employment.json");
        this.netEarningsFactor = new BigDecimal(root.get("netEarningsFactor").asText());
        this.socialSecurityRate = new BigDecimal(root.get("socialSecurityRate").asText());
        this.medicareRate = new BigDecimal(root.get("medicareRate").asText());
        this.constantsSource = root.get("source").asText();
        this.filingThresholdMinor = root.get("seFilingThresholdMinor").asLong();
        for (JsonNode entry : root.get("wageBases")) {
            wageBases.add(new WageBase(entry.get("taxYear").asInt(),
                    Money.of(entry.get("amount").asText(), entry.get("currency").asText()),
                    entry.get("source").asText()));
        }
    }

    private static JsonNode read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("Missing or unreadable rule file: " + path, e);
        }
    }

    public record Result(int taxYear, String currency,
                         Money netProfit, Money netSelfEmploymentEarnings, boolean seTaxApplies,
                         Money socialSecurityPart, Money medicarePart, Money selfEmploymentTax,
                         Money deductibleHalfOfSeTax, Money wageBase, boolean wageBaseKnown,
                         String wageBaseSource, Money incomeTaxEstimate, BigDecimal marginalRatePercent,
                         boolean incomeTaxEstimated, Money annualSetAside, Money quarterlyPayment,
                         List<String> quarterlyDueDates, List<String> notes) {
    }

    /**
     * @param marginalRatePercent the person's chosen marginal income-tax rate. Null means "no income-tax leg" —
     *                            Solid never supplies a rate itself, because the honest rate depends on the rest
     *                            of the household's income, which the books of one entity cannot know.
     */
    public Result worksheet(UUID orgId, UUID entityId, int taxYear, BigDecimal marginalRatePercent) {
        if (marginalRatePercent != null
                && (marginalRatePercent.signum() < 0 || marginalRatePercent.compareTo(new BigDecimal("100")) > 0)) {
            throw new com.aesoftwaresolutions.solid.common.ApiProblemException(400, "BAD_RATE",
                    "The marginal rate is a percentage between 0 and 100");
        }
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        String ccy = entity.baseCurrency();
        TaxLineReport report = taxLines.report(orgId, entityId, taxYear);
        Money netProfit = report.totals().netProfit();
        List<String> notes = new ArrayList<>();

        // Schedule SE: only 92.35% of net profit is self-employment earnings (§1402(a)).
        Money seEarnings = netProfit.isPositive()
                ? netProfit.multiply(netEarningsFactor, RoundingMode.HALF_UP)
                : Money.zero(ccy);
        boolean seApplies = seEarnings.minorUnits() >= filingThresholdMinor;
        if (!seApplies) {
            notes.add("Net self-employment earnings are under the $400 filing threshold (IRC §6017), so no "
                    + "self-employment tax is due.");
        }

        // The wage base for the year: a runtime-supplied figure wins, then the shipped file, then unknown.
        Optional<TaxFigures.Figure> runtimeFigure = figures.inForce(TaxFigures.Key.se_wage_base, taxYear);
        Optional<Money> wageBase = Optional.empty();
        String wageBaseSource = null;
        if (runtimeFigure.isPresent()) {
            TaxFigures.Figure figure = runtimeFigure.get();
            wageBase = Optional.of(Money.of(figure.value().toPlainString(), ccy));
            wageBaseSource = "Added on this installation: " + figure.source();
        } else {
            Optional<WageBase> fromFile = wageBases.stream().filter(w -> w.taxYear() == taxYear).findFirst();
            if (fromFile.isPresent()) {
                wageBase = Optional.of(fromFile.get().amount());
                wageBaseSource = fromFile.get().source();
            }
        }

        Money ssPart;
        if (!seApplies) {
            ssPart = Money.zero(ccy);
        } else if (wageBase.isPresent()) {
            Money capped = seEarnings.compareTo(wageBase.get()) > 0 ? wageBase.get() : seEarnings;
            ssPart = capped.multiply(socialSecurityRate, RoundingMode.HALF_UP);
        } else {
            // Never reuse last year's cap: compute uncapped and say clearly what is missing (the rule the
            // whole project follows — unknown is unknown, not interpolated).
            ssPart = seEarnings.multiply(socialSecurityRate, RoundingMode.HALF_UP);
            notes.add("No Social Security wage base for " + taxYear + " is on file, so the Social Security "
                    + "part is computed WITHOUT the cap. If the earnings above are large, this overstates the "
                    + "estimate. An administrator can add the year's wage base on the installation page.");
        }
        Money medicarePart = seApplies ? seEarnings.multiply(medicareRate, RoundingMode.HALF_UP)
                : Money.zero(ccy);
        Money seTax = ssPart.add(medicarePart);
        // Half of self-employment tax is itself a deduction (IRC §164(f)).
        Money deductibleHalf = seTax.multiply(halfFactor, RoundingMode.HALF_UP);

        boolean incomeTaxEstimated = marginalRatePercent != null;
        Money incomeTaxPart = Money.zero(ccy);
        if (incomeTaxEstimated) {
            if (netProfit.isPositive()) {
                incomeTaxPart = netProfit.subtract(deductibleHalf)
                        .multiply(marginalRatePercent.movePointLeft(2), RoundingMode.HALF_UP);
            }
            notes.add("The income-tax part uses the marginal rate you supplied (" + marginalRatePercent
                    + "%). Solid did not choose it, and it is applied flat — no brackets, no deductions other "
                    + "than half of SE tax. It is a set-aside target, not a return.");
        } else {
            notes.add("No income-tax estimate: pass marginalRatePercent to include one. Solid will not pick a "
                    + "rate for you, because the honest rate depends on the rest of your household's income.");
        }

        if (wageBase.isPresent() && seEarnings.compareTo(wageBase.get()) > 0) {
            notes.add("Self-employment earnings exceed the " + taxYear + " Social Security wage base, so only "
                    + wageBase.get().toDecimalString() + " is taxed at 12.4%. Medicare still has no cap.");
        }
        notes.add("Not included: the Additional Medicare Tax (0.9% over household thresholds), the QBI "
                + "deduction, itemized deductions, and state tax — all of which need information the entity's "
                + "books do not have. Rates: " + constantsSource);

        Money annual = seTax.add(incomeTaxPart);
        Money quarterly = annual.multiply(new BigDecimal("0.25"), RoundingMode.HALF_UP);
        List<String> dueDates = List.of(
                "April 15, " + taxYear + " (for income earned Jan–Mar)",
                "June 15, " + taxYear + " (for income earned Apr–May)",
                "September 15, " + taxYear + " (for income earned Jun–Aug)",
                "January 15, " + (taxYear + 1) + " (for income earned Sep–Dec)");

        return new Result(taxYear, ccy, netProfit, seEarnings, seApplies, ssPart, medicarePart, seTax,
                deductibleHalf, wageBase.orElse(null), wageBase.isPresent(), wageBaseSource, incomeTaxPart,
                marginalRatePercent, incomeTaxEstimated, annual, quarterly, dueDates, List.copyOf(notes));
    }
}
