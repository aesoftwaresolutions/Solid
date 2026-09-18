import { useEffect, useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { ApiError, api, formatMoney, type Account, type ComparisonRow } from '../api';
import { Card, ErrorMessage, Loading, useLoader } from '../components';

const thisMonth = () => new Date().toISOString().slice(0, 7);

export default function BudgetPage() {
  const { orgId = '', entityId = '' } = useParams();
  const [month, setMonth] = useState(thisMonth());
  const accounts = useLoader(() => api.accounts(orgId, entityId), [orgId, entityId]);

  const [amounts, setAmounts] = useState<Record<string, string>>({});
  const [comparison, setComparison] = useState<Awaited<ReturnType<typeof api.budgetVsActual>> | undefined>(undefined);
  const [error, setError] = useState<unknown>(undefined);
  const [saved, setSaved] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const budgetable = useMemo(
    () => (accounts.value ?? []).filter((a: Account) => !a.isHeader && !a.isArchived && (a.type === 'income' || a.type === 'expense')),
    [accounts.value],
  );

  // A month with no budget yet is a normal, empty starting point — not an error to show the person.
  useEffect(() => {
    let cancelled = false;
    setSaved(null);
    setError(undefined);
    api
      .budget(orgId, entityId, month)
      .then((budget) => {
        if (cancelled) {
          return;
        }
        const next: Record<string, string> = {};
        budget.lines.forEach((line) => {
          next[line.accountId] = line.amount.amount;
        });
        setAmounts(next);
      })
      .catch((e) => {
        if (cancelled) {
          return;
        }
        setAmounts({});
        if (!(e instanceof ApiError && e.status === 404)) {
          setError(e);
        }
      });
    api
      .budgetVsActual(orgId, entityId, month)
      .then((result) => !cancelled && setComparison(result))
      .catch((e) => !cancelled && setError(e));
    return () => {
      cancelled = true;
    };
  }, [orgId, entityId, month]);

  const save = () => {
    const lines = Object.entries(amounts)
      .filter(([, amount]) => amount.trim() !== '' && Number(amount) !== 0)
      .map(([accountId, amount]) => ({ accountId, amount: { amount: amount.trim(), currency: 'USD' } }));
    setBusy(true);
    setError(undefined);
    api
      .saveBudget(orgId, entityId, month, lines)
      .then(() => {
        setSaved(`Saved the ${month} budget.`);
        return api.budgetVsActual(orgId, entityId, month).then(setComparison);
      })
      .catch(setError)
      .finally(() => setBusy(false));
  };

  return (
    <main>
      <h1>Budget</h1>
      <ErrorMessage error={accounts.error} />
      <ErrorMessage error={error} />

      <Card title="Plan the month">
        <label>
          Month
          <input type="month" value={month} onChange={(e) => setMonth(e.target.value)} />
        </label>
        {!accounts.value && !accounts.error && <Loading what="accounts" />}
        {accounts.value && budgetable.length === 0 && (
          <p className="muted">Apply a chart of accounts first — budgets cover income and expense accounts.</p>
        )}
        {budgetable.length > 0 && (
          <>
            <table>
              <thead>
                <tr>
                  <th>Account</th>
                  <th>Type</th>
                  <th className="money">Planned</th>
                </tr>
              </thead>
              <tbody>
                {budgetable.map((account) => (
                  <tr key={account.id}>
                    <td>
                      {account.code} {account.name}
                    </td>
                    <td>{account.type}</td>
                    <td className="money">
                      <label className="visually-hidden" htmlFor={`budget-${account.id}`}>
                        Planned amount for {account.name}
                      </label>
                      <input
                        id={`budget-${account.id}`}
                        inputMode="decimal"
                        placeholder="0.00"
                        value={amounts[account.id] ?? ''}
                        onChange={(e) => setAmounts({ ...amounts, [account.id]: e.target.value })}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {saved && <p className="notice">{saved}</p>}
            <button type="button" onClick={save} disabled={busy}>
              Save budget
            </button>
          </>
        )}
      </Card>

      <Card title={`Budget vs actual · ${month}`}>
        {!comparison && <Loading what="the comparison" />}
        {comparison && (
          <>
            <ComparisonTable title="Income" rows={comparison.income} />
            <ComparisonTable title="Expenses" rows={comparison.expenses} />
            <table>
              <tbody>
                <tr>
                  <td>Planned net</td>
                  <td className="money">{formatMoney(comparison.totals.budgetedNet)}</td>
                </tr>
                <tr>
                  <td>Actual net</td>
                  <td className="money">{formatMoney(comparison.totals.actualNet)}</td>
                </tr>
              </tbody>
            </table>
          </>
        )}
      </Card>
    </main>
  );
}

function ComparisonTable({ title, rows }: { title: string; rows: ComparisonRow[] }) {
  if (rows.length === 0) {
    return (
      <p className="muted">
        {title}: nothing planned or spent yet.
      </p>
    );
  }
  return (
    <table>
      <caption>{title}</caption>
      <thead>
        <tr>
          <th>Account</th>
          <th className="money">Planned</th>
          <th className="money">Actual</th>
          <th className="money">Variance</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.accountId}>
            <td>
              {row.code} {row.name}
              {row.overBudget && <span className="muted"> · over budget</span>}
            </td>
            <td className="money">{formatMoney(row.budget)}</td>
            <td className="money">{formatMoney(row.actual)}</td>
            <td className="money">{formatMoney(row.variance)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
