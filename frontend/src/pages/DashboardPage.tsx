import { Link, useParams } from 'react-router-dom';
import { ApiError, api, formatMoney, type Money } from '../api';
import { Card, ErrorMessage, Loading, useLoader } from '../components';

const today = () => new Date().toISOString().slice(0, 10);
const thisMonth = () => new Date().toISOString().slice(0, 7);

/** Adds the buckets the server already returned; no money maths beyond a sum of equal-currency totals. */
function agingTotal(total: Money | undefined): string {
  return formatMoney(total);
}

export default function DashboardPage() {
  const { orgId = '', entityId = '' } = useParams();
  const taxYear = new Date().getFullYear();
  const month = thisMonth();

  const tax = useLoader(() => api.taxLines(orgId, entityId, taxYear), [orgId, entityId, taxYear]);
  const receivable = useLoader(() => api.arAging(orgId, entityId, today()), [orgId, entityId]);
  const payable = useLoader(() => api.apAging(orgId, entityId, today()), [orgId, entityId]);
  const coverage = useLoader(() => api.taxRuleCoverage(taxYear), [taxYear]);
  const budget = useLoader(
    () =>
      api
        .budgetVsActual(orgId, entityId, month)
        .then((result) => api.budget(orgId, entityId, month).then(() => result))
        // A month with no budget is a normal state, not an error.
        .catch((e) => (e instanceof ApiError && e.status === 404 ? undefined : Promise.reject(e))),
    [orgId, entityId, month],
  );

  const base = `/orgs/${orgId}/entities/${entityId}`;
  const report = tax.value;

  return (
    <main>
      <h1>Dashboard</h1>

      <Card title={`Tax year ${taxYear}`}>
        <ErrorMessage error={tax.error} />
        {!report && !tax.error && <Loading what="this year's numbers" />}
        {report && (
          <div className="stats">
            <div className="stat">
              <div className="muted">Income</div>
              <div className="value">{formatMoney(report.totals.income)}</div>
            </div>
            <div className="stat">
              <div className="muted">Expenses</div>
              <div className="value">{formatMoney(report.totals.expenses)}</div>
            </div>
            <div className="stat">
              <div className="muted">Net profit</div>
              <div className="value">{formatMoney(report.totals.netProfit)}</div>
            </div>
          </div>
        )}
      </Card>

      <Card title="Waiting for you">
        <ul>
          {report && report.readiness.uncategorizedBankTransactions > 0 && (
            <li>
              {report.readiness.uncategorizedBankTransactions} bank transaction(s) still need a category —{' '}
              <Link to={`${base}/bank`}>review them</Link>
            </li>
          )}
          {report && report.readiness.draftEntries > 0 && (
            <li>
              {report.readiness.draftEntries} draft journal entry(ies) not posted —{' '}
              <Link to={`${base}/journal`}>open the journal</Link>
            </li>
          )}
          {report && report.readiness.unmappedAccounts > 0 && (
            <li>
              {report.readiness.unmappedAccounts} account(s) have no tax line —{' '}
              <Link to={`${base}/accounts`}>map them</Link>
            </li>
          )}
          {report && report.readiness.ready && <li>Everything is categorized and mapped for {taxYear}.</li>}
        </ul>
      </Card>

      <Card title="Money">
        <ErrorMessage error={receivable.error} />
        <ErrorMessage error={payable.error} />
        <div className="stats">
          <div className="stat">
            <div className="muted">Owed to you</div>
            <div className="value">{agingTotal(receivable.value?.totals.total)}</div>
            <Link to={`${base}/sales`}>Sales</Link>
          </div>
          <div className="stat">
            <div className="muted">You owe</div>
            <div className="value">{agingTotal(payable.value?.totals.total)}</div>
            <Link to={`${base}/purchases`}>Purchases</Link>
          </div>
        </div>
      </Card>

      <Card title={`This month · ${month}`}>
        <ErrorMessage error={budget.error} />
        {budget.value ? (
          <div className="stats">
            <div className="stat">
              <div className="muted">Planned net</div>
              <div className="value">{formatMoney(budget.value.totals.budgetedNet)}</div>
            </div>
            <div className="stat">
              <div className="muted">Actual net</div>
              <div className="value">{formatMoney(budget.value.totals.actualNet)}</div>
              <Link to={`${base}/budget`}>Budget</Link>
            </div>
          </div>
        ) : (
          <p className="muted">
            No budget for this month yet — <Link to={`${base}/budget`}>plan one</Link>.
          </p>
        )}
      </Card>

      <Card title={`Tax figures on file for ${taxYear}`}>
        <ErrorMessage error={coverage.error} />
        {coverage.value && (
          <>
            <p>
              {coverage.value.covered} rule pack(s) cover {taxYear}; {coverage.value.missing} do not.
            </p>
            {coverage.value.missing > 0 && (
              <ul>
                {coverage.value.packs
                  .filter((pack) => !pack.covered)
                  .map((pack) => (
                    <li key={pack.id}>
                      {pack.title}: {pack.reason}
                    </li>
                  ))}
              </ul>
            )}
            <p className="muted">{coverage.value.note}</p>
          </>
        )}
      </Card>
    </main>
  );
}
