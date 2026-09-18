import { Link, useParams } from 'react-router-dom';
import { api, formatMoney } from '../api';
import { Card, ErrorMessage, Loading, useLoader } from '../components';

export default function DashboardPage() {
  const { orgId = '', entityId = '' } = useParams();
  const taxYear = new Date().getFullYear();
  const { value: report, error } = useLoader(() => api.taxLines(orgId, entityId, taxYear), [orgId, entityId, taxYear]);

  return (
    <main>
      <h1>Dashboard</h1>
      <ErrorMessage error={error} />
      {!report && !error && <Loading what="this year's numbers" />}

      {report && (
        <>
          <Card title={`Tax year ${report.taxYear} (${report.from} → ${report.to})`}>
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
          </Card>

          <Card title="Ready for your preparer?">
            {report.readiness.ready ? (
              <p className="notice">Everything is categorized and mapped. Download the tax-line report when you're ready.</p>
            ) : (
              <ul>
                {report.readiness.uncategorizedBankTransactions > 0 && (
                  <li>
                    {report.readiness.uncategorizedBankTransactions} bank transaction(s) still need a category —{' '}
                    <Link to={`/orgs/${orgId}/entities/${entityId}/bank`}>review them</Link>
                  </li>
                )}
                {report.readiness.draftEntries > 0 && <li>{report.readiness.draftEntries} draft journal entry(ies) not posted</li>}
                {report.readiness.unmappedAccounts > 0 && (
                  <li>
                    {report.readiness.unmappedAccounts} account(s) have no tax line —{' '}
                    <Link to={`/orgs/${orgId}/entities/${entityId}/accounts`}>map them</Link>
                  </li>
                )}
              </ul>
            )}
          </Card>

          <Card title="Next steps">
            <ul>
              <li><Link to={`/orgs/${orgId}/entities/${entityId}/accounts`}>Chart of accounts</Link></li>
              <li><Link to={`/orgs/${orgId}/entities/${entityId}/bank`}>Import and categorize bank activity</Link></li>
              <li><Link to={`/orgs/${orgId}/entities/${entityId}/reports`}>Reports</Link></li>
            </ul>
          </Card>
        </>
      )}
    </main>
  );
}
