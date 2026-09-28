import { useState } from 'react';

/** Money-shaped response bodies, matching the API's { amount: string, currency: string } convention. */
type Money = { amount: string; currency: string };

interface WorksheetStep {
  line: number;
  label: string;
  amount: Money | null;
  note: string;
}

interface Worksheet {
  taxYear: number;
  currency: string;
  steps: WorksheetStep[];
  selfEmploymentTax: Money;
  incomeTaxIncluded: boolean;
  incomeTaxEstimate: Money;
  annualSetAside: Money;
  quarterlyPayment: Money;
  wageBaseKnown: boolean;
  quarterlyDueDates: string[];
  caveats: string[];
}

function fmt(money: Money): string {
  const negative = money.amount.startsWith('-');
  const digits = negative ? money.amount.slice(1) : money.amount;
  const [whole, fraction] = digits.split('.');
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const text = fraction ? `${grouped}.${fraction}` : grouped;
  return negative ? `(${text})` : text;
}

/**
 * The quarterly set-aside worksheet (spec 067/068), as a card. Worksheet-style: numbered lines you can check
 * by hand, headline answer on top, caveats underneath. Self-contained on purpose — it does its own GET
 * (cookie-authenticated, read-only) so a host page only has to render it with org, entity and year.
 */
export default function EstimatedTaxCard({
  orgId,
  entityId,
  year,
}: {
  orgId: string;
  entityId: string;
  year: number;
}) {
  const [rate, setRate] = useState('');
  const [worksheet, setWorksheet] = useState<Worksheet | undefined>(undefined);
  const [error, setError] = useState<string | undefined>(undefined);
  const [busy, setBusy] = useState(false);

  const load = (marginalRate: string) => {
    setBusy(true);
    setError(undefined);
    const query = marginalRate.trim() === '' ? '' : `&marginalRatePercent=${encodeURIComponent(marginalRate.trim())}`;
    fetch(`/api/v1/orgs/${orgId}/entities/${entityId}/reports/estimated-tax?taxYear=${year}${query}`, {
      credentials: 'same-origin',
    })
      .then(async (response) => {
        const body = await response.json().catch(() => undefined);
        if (!response.ok) {
          throw new Error(body?.detail ?? `Request failed (${response.status})`);
        }
        setWorksheet(body as Worksheet);
      })
      .catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setBusy(false));
  };

  return (
    <section className="card" aria-label={`Estimated tax set-aside for ${year}`}>
      <header className="card-header">
        <h2>How much should I set aside? ({year})</h2>
      </header>
      <p className="muted">
        A worksheet, not a tax return: it takes this year's profit from the books and walks the maths line by
        line, so every figure can be checked by hand.
      </p>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          load(rate);
        }}
      >
        <label>
          Your marginal income-tax rate (%) — leave blank for self-employment tax only
          <input value={rate} inputMode="decimal" placeholder="e.g. 24" onChange={(e) => setRate(e.target.value)} />
        </label>
        <button type="submit" disabled={busy}>
          {worksheet ? 'Recalculate' : 'Work it out'}
        </button>
      </form>
      {error && <p role="alert" className="error">{error}</p>}
      {worksheet && (
        <>
          <p className="stats" style={{ alignItems: 'baseline' }}>
            <span className="stat">
              <span className="muted">Set aside per quarter</span>
              <br />
              <span className="value">
                {fmt(worksheet.quarterlyPayment)} {worksheet.currency}
              </span>
            </span>
            <span className="stat">
              <span className="muted">Per year</span>
              <br />
              <span className="value">
                {fmt(worksheet.annualSetAside)}
              </span>
            </span>
          </p>
          <table>
            <thead>
              <tr>
                <th>Step</th>
                <th className="money">Amount</th>
                <th>The arithmetic</th>
              </tr>
            </thead>
            <tbody>
              {worksheet.steps.map((step) => (
                <tr key={step.line}>
                  <td>
                    {step.line}. {step.label}
                  </td>
                  <td className="money">{step.amount ? fmt(step.amount) : '—'}</td>
                  <td className="muted">{step.note}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="muted">
            Due quarterly: {worksheet.quarterlyDueDates.join(' · ')}
          </p>
          <ul className="muted">
            {worksheet.caveats.map((caveat, index) => (
              <li key={index}>{caveat}</li>
            ))}
          </ul>
          {!worksheet.wageBaseKnown && (
            <p role="status" className="error">
              No Social Security wage base is on file for {worksheet.taxYear}, so that part is uncapped. An
              instance administrator can add the year's figure from the instance page.
            </p>
          )}
        </>
      )}
    </section>
  );
}
