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
 * The quarterly set-aside worksheet (spec 067), shaped the way a person reads it: an ordered list of
 * worksheet lines, like Form 1040-ES, where every line names itself, shows the arithmetic that produced its
 * amount, and says nothing else. The headline answer — what to set aside this quarter — is one field at the
 * top; the steps are the audit trail under it.
 *
 * <p>What never changes by year — the 92.35% net-earnings factor, the 12.4% Social Security rate, the 2.9%
 * Medicare rate and the $400 filing threshold — is statute (IRC §1401, §1402, §6017), so it lives in the
 * rule file with its source. What does change every year — the Social Security wage base — is looked up per
 * tax year: a runtime-supplied figure (spec 049) wins, then the shipped file, then <em>unknown</em>, and the
 * worksheet says so instead of carrying last year's number forward.
 *
 * <p>This is an estimate for setting money aside, not a return and not advice: it does not model the
 * Additional Medicare Tax (which needs household income), itemized deductions, QBI, or state tax.
 */
@org.springframework.stereotype.Service
public class EstimatedTaxService {

    private final OrgService orgs;
    private final TaxLineReportService taxLines;
    private final TaxFigures figures;

    private final BigDecimal netEarningsFactor;
    private final BigDecimal socialSecurityRate;
    private final BigDecimal medicareRate;
    private final BigDecimal halfFactor = new BigDecimal("0.5");
    private final BigDecimal quarterFactor = new BigDecimal("0.25");
    private final String source;
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
        this.source = root.get("source").asText();
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

    /**
     * One line of the worksheet. {@code amount} is null for narrative lines ("nothing due"); {@code note}
     * carries the plain-language arithmetic — "2,000.00 × 92.35%" — so nobody has to take a number on faith.
     */
    public record Step(int line, String label, Money amount, String note) {
    }

    public record Result(int taxYear, String currency, List<Step> steps,
                         Money selfEmploymentTax, Money incomeTaxEstimate, boolean incomeTaxIncluded,
                         BigDecimal marginalRatePercent, Money annualSetAside, Money quarterlyPayment,
                         boolean wageBaseKnown, String wageBaseSource,
                         List<String> quarterlyDueDates, List<String> caveats) {
    }

    /**
     * @param marginalRatePercent the person's chosen marginal income-tax rate. Null means "worksheet stops
     *                            at self-employment tax" — Solid never supplies a rate itself, because the
     *                            honest rate depends on the rest of the household's income, which the books
     *                            of one entity cannot know.
     */
    public Result worksheet(UUID orgId, UUID entityId, int taxYear, BigDecimal marginalRatePercent) {
        if (marginalRatePercent != null
                && (marginalRatePercent.signum() < 0 || marginalRatePercent.compareTo(new BigDecimal("100")) > 0)) {
            throw new com.aesoftwaresolutions.solid.common.ApiProblemException(400, "BAD_RATE",
                    "The marginal rate is a percentage between 0 and 100");
        }
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        String ccy = entity.baseCurrency();
        Money netProfit = taxLines.report(orgId, entityId, taxYear).totals().netProfit();

        List<Step> steps = new ArrayList<>();
        List<String> caveats = new ArrayList<>();
        caveats.add("Not included: the Additional Medicare Tax (0.9% over household thresholds), the QBI "
                + "deduction, itemized deductions, and state tax — all of which need information the entity's "
                + "books do not have.");
        caveats.add("Rates and rules: " + source);
        int line = 1;
        Money zero = Money.zero(ccy);

        // 1. Start from the books.
        steps.add(new Step(line++, "Net profit this year, from your books", netProfit,
                "Your posted income minus your posted expenses for " + taxYear + "."));

        if (!netProfit.isPositive()) {
            steps.add(new Step(line, "Nothing to set aside for self-employment tax", zero,
                    "No profit, no self-employment tax. If this is still early in the year, look again "
                            + "after a few more sales."));
            return new Result(taxYear, ccy, List.copyOf(steps), zero, zero, marginalRatePercent != null,
                    marginalRatePercent, zero, zero, true, null, dueDates(taxYear), List.copyOf(caveats));
        }

        // 2. Schedule SE's own haircut.
        Money seEarnings = netProfit.multiply(netEarningsFactor, RoundingMode.HALF_UP);
        steps.add(new Step(line++, "Self-employment earnings", seEarnings,
                netProfit.toDecimalString() + " × 92.35% — the law taxes only that share of profit "
                        + "(IRC §1402(a))."));

        boolean seApplies = seEarnings.minorUnits() >= filingThresholdMinor;
        if (!seApplies) {
            steps.add(new Step(line, "Self-employment tax", zero,
                    "Under the $400 filing threshold (IRC §6017), nothing is due."));
            return new Result(taxYear, ccy, List.copyOf(steps), zero, zero, marginalRatePercent != null,
                    marginalRatePercent, zero, zero, true, null, dueDates(taxYear), List.copyOf(caveats));
        }

        // 3. The wage base for THIS year — runtime figure, shipped file, or unknown.
        Optional<TaxFigures.Figure> runtimeFigure = figures.inForce(TaxFigures.Key.se_wage_base, taxYear);
        Optional<Money> wageBase = runtimeFigure.map(f -> Money.of(f.value().toPlainString(), ccy));
        String wageBaseSource = runtimeFigure.map(f -> "Added on this installation: " + f.source()).orElse(null);
        if (wageBase.isEmpty()) {
            Optional<WageBase> fromFile = wageBases.stream().filter(w -> w.taxYear() == taxYear).findFirst();
            if (fromFile.isPresent()) {
                wageBase = Optional.of(fromFile.get().amount());
                wageBaseSource = fromFile.get().source();
            }
        }

        // 4. Social Security part.
        Money ssTaxable;
        String ssNote;
        if (wageBase.isPresent()) {
            boolean capped = seEarnings.compareTo(wageBase.get()) > 0;
            ssTaxable = capped ? wageBase.get() : seEarnings;
            ssNote = ssTaxable.toDecimalString() + " × 12.4%"
                    + (capped ? " — earnings are over the " + wageBase.get().toDecimalString() + " cap for "
                            + taxYear + ", so only the cap is taxed here" : "");
        } else {
            // Unknown is unknown: compute without a cap and say so, rather than reuse last year's number.
            ssTaxable = seEarnings;
            ssNote = ssTaxable.toDecimalString() + " × 12.4% — NO wage-base cap applied: none is on file for "
                    + taxYear + ". This overstates the estimate if earnings are large; an administrator can "
                    + "add the year's wage base on the installation page.";
        }
        Money ssPart = ssTaxable.multiply(socialSecurityRate, RoundingMode.HALF_UP);
        steps.add(new Step(line++, "Social Security part", ssPart, ssNote));

        // 5. Medicare part, no cap.
        Money medicarePart = seEarnings.multiply(medicareRate, RoundingMode.HALF_UP);
        steps.add(new Step(line++, "Medicare part", medicarePart,
                seEarnings.toDecimalString() + " × 2.9% — Medicare has no cap."));

        // 6. The SE tax itself, and its deductible half.
        Money seTax = ssPart.add(medicarePart);
        steps.add(new Step(line++, "Self-employment tax", seTax,
                "Social Security " + ssPart.toDecimalString() + " + Medicare " + medicarePart.toDecimalString()));
        Money deductibleHalf = seTax.multiply(halfFactor, RoundingMode.HALF_UP);
        steps.add(new Step(line++, "Half of that is deductible", deductibleHalf,
                "Half of self-employment tax comes off before income tax (IRC §164(f)): "
                        + seTax.toDecimalString() + " × 50%."));

        // 7. The income-tax leg — only at the caller's own rate.
        boolean incomeTaxIncluded = marginalRatePercent != null;
        Money incomeTaxPart = zero;
        if (incomeTaxIncluded) {
            incomeTaxPart = netProfit.subtract(deductibleHalf)
                    .multiply(marginalRatePercent.movePointLeft(2), RoundingMode.HALF_UP);
            steps.add(new Step(line++, "Income tax at the rate you chose", incomeTaxPart,
                    "(" + netProfit.toDecimalString() + " profit − " + deductibleHalf.toDecimalString()
                            + " deduction) × " + marginalRatePercent + "% — a flat rate you typed in, not one "
                            + "Solid chose."));
        } else {
            steps.add(new Step(line, "Income tax", null,
                    "Skipped. Solid will not pick a marginal rate for you — the honest one depends on the "
                            + "rest of your household's income. Pass one to include this step."));
        }

        Money annual = seTax.add(incomeTaxPart);
        Money quarterly = annual.multiply(quarterFactor, RoundingMode.HALF_UP);

        return new Result(taxYear, ccy, List.copyOf(steps), seTax, incomeTaxPart, incomeTaxIncluded,
                marginalRatePercent, annual, quarterly, wageBase.isPresent(), wageBaseSource,
                dueDates(taxYear), List.copyOf(caveats));
    }

    private static List<String> dueDates(int taxYear) {
        return List.of(
                "April 15, " + taxYear + " (income earned Jan–Mar)",
                "June 15, " + taxYear + " (income earned Apr–May)",
                "September 15, " + taxYear + " (income earned Jun–Aug)",
                "January 15, " + (taxYear + 1) + " (income earned Sep–Dec)");
    }
}
