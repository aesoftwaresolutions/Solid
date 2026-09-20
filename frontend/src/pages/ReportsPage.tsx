import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, formatMoney, type Section } from '../api';
import { Card, ErrorMessage, Loading, useLoader } from '../components';

function SectionTable({ title, section }: { title: string; section: Section }) {
  return (
    <table>
      <thead>
        <tr>
          <th colSpan={2}>{title}</th>
          <th className="money">Amount</th>
        </tr>
      </thead>
      <tbody>
        {section.rows.map((row, index) => (
          <tr key={row.accountId ?? `${row.name}-${index}`}>
            <td>{row.code ?? ''}</td>
            <td>{row.name}</td>
            <td className="money">{formatMoney(row.amount)}</td>
          </tr>
        ))}
        <tr>
          <td colSpan={2}>
            <strong>Total {title.toLowerCase()}</strong>
          </td>
          <td className="money">
            <strong>{formatMoney(section.total)}</strong>
          </td>
        </tr>
      </tbody>
    </table>
  );
}

export default function ReportsPage() {
  const { orgId = '', entityId = '' } = useParams();
  const thisYear = new Date().getFullYear();
  const [year, setYear] = useState(thisYear);
  const from = `${year}-01-01`;
  const to = `${year}-12-31`;

  const pl = useLoader(() => api.profitAndLoss(orgId, entityId, from, to), [orgId, entityId, from, to]);
  const bs = useLoader(() => api.balanceSheet(orgId, entityId, to), [orgId, entityId, to]);
  const tb = useLoader(() => api.trialBalance(orgId, entityId, to), [orgId, entityId, to]);
  const tax = useLoader(() => api.taxLines(orgId, entityId, year), [orgId, entityId, year]);
  const checklist = useLoader(() => api.yearEndChecklist(orgId, entityId, year), [orgId, entityId, year]);
  const cash = useLoader(() => api.cashFlow(orgId, entityId, from, to), [orgId, entityId, from, to]);

  return (
    <main>
      <h1>Reports</h1>
      <label>
        Year
        <select value={year} onChange={(e) => setYear(Number(e.target.value))}>
          {[thisYear, thisYear - 1, thisYear - 2].map((option) => (
            <option key={option} value={option}>
              {option}
            </option>
          ))}
        </select>
      </label>

      <Card title={`Profit & loss ${from} → ${to}`}>
        <ErrorMessage error={pl.error} />
        {!pl.value && !pl.error && <Loading what="profit & loss" />}
        {pl.value && (
          <>
            <SectionTable title="Income" section={pl.value.income} />
            {pl.value.costOfGoodsSold.rows.length > 0 && (
              <SectionTable title="Cost of goods sold" section={pl.value.costOfGoodsSold} />
            )}
            <SectionTable title="Expenses" section={pl.value.expenses} />
            <p>
              <strong>Net income: {formatMoney(pl.value.netIncome)}</strong>
            </p>
          </>
        )}
      </Card>

      <Card title={`Balance sheet as of ${to}`}>
        <ErrorMessage error={bs.error} />
        {bs.value && (
          <>
            <SectionTable title="Assets" section={bs.value.assets} />
            <SectionTable title="Liabilities" section={bs.value.liabilities} />
            <SectionTable title="Equity" section={bs.value.equity} />
            <p className={bs.value.balanced ? 'notice' : 'error'}>
              {bs.value.balanced
                ? `Balanced: assets equal liabilities plus equity (${formatMoney(bs.value.totalLiabilitiesAndEquity)})`
                : 'Not balanced — this is a bug, please report it'}
            </p>
          </>
        )}
      </Card>

      <Card title={`Trial balance as of ${to}`}>
        <ErrorMessage error={tb.error} />
        {tb.value && (
          <table>
            <thead>
              <tr>
                <th>Code</th>
                <th>Account</th>
                <th className="money">Debit</th>
                <th className="money">Credit</th>
              </tr>
            </thead>
            <tbody>
              {tb.value.rows.map((row) => (
                <tr key={row.accountId}>
                  <td>{row.code}</td>
                  <td>{row.name}</td>
                  <td className="money">{formatMoney(row.debit)}</td>
                  <td className="money">{formatMoney(row.credit)}</td>
                </tr>
              ))}
              <tr>
                <td colSpan={2}>
                  <strong>Totals</strong>
                </td>
                <td className="money">
                  <strong>{formatMoney(tb.value.totalDebit)}</strong>
                </td>
                <td className="money">
                  <strong>{formatMoney(tb.value.totalCredit)}</strong>
                </td>
              </tr>
            </tbody>
          </table>
        )}
      </Card>

      <Card title={`Cash flow ${year}`}>
        <ErrorMessage error={cash.error} />
        {cash.value && (
          <>
            <table>
              <tbody>
                <tr>
                  <td>Cash at the start</td>
                  <td className="money">{formatMoney(cash.value.openingCash)}</td>
                </tr>
                <tr>
                  <td>Operating</td>
                  <td className="money">{formatMoney(cash.value.operating.total)}</td>
                </tr>
                <tr>
                  <td>Investing</td>
                  <td className="money">{formatMoney(cash.value.investing.total)}</td>
                </tr>
                <tr>
                  <td>Financing</td>
                  <td className="money">{formatMoney(cash.value.financing.total)}</td>
                </tr>
                {cash.value.unclassified.rows.length > 0 && (
                  <tr>
                    <td>Unclassified</td>
                    <td className="money">{formatMoney(cash.value.unclassified.total)}</td>
                  </tr>
                )}
                <tr>
                  <td>
                    <strong>Cash at the end</strong>
                  </td>
                  <td className="money">
                    <strong>{formatMoney(cash.value.closingCash)}</strong>
                  </td>
                </tr>
              </tbody>
            </table>
            <p className="muted">{cash.value.note}</p>
          </>
        )}
      </Card>

      <Card title={`Is ${year} finished?`}>
        <ErrorMessage error={checklist.error} />
        {checklist.value && (
          <>
            <p className={checklist.value.ready ? 'notice' : 'muted'}>
              {checklist.value.ready
                ? 'The bookkeeping steps for this year are done. That is not the same as a correct return — your preparer decides that.'
                : 'Still to do before this year can be handed over:'}
            </p>
            <table>
              <thead>
                <tr>
                  <th>Step</th>
                  <th>State</th>
                  <th>What is left</th>
                </tr>
              </thead>
              <tbody>
                {checklist.value.items.map((item) => (
                  <tr key={item.key}>
                    <td>
                      <Link to={`/orgs/${orgId}/entities/${entityId}/${item.where}`}>{item.title}</Link>
                    </td>
                    <td>{item.status}</td>
                    <td className="muted">{item.detail}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
      </Card>

      <Card
        title="Your data"
        actions={
          <a href={api.entityExportUrl(orgId, entityId)} download>
            Export everything (ZIP)
          </a>
        }
      >
        <p className="muted">
          Every account, journal entry, bank transaction, invoice, bill and document record as CSV files anyone can
          open. The uploaded files themselves come out of a server backup — see docs/operations.md.
        </p>
      </Card>

      <Card
        title={`Tax lines ${year}`}
        actions={
          <a href={api.taxLinesCsvUrl(orgId, entityId, year)} download>
            Download CSV
          </a>
        }
      >
        <ErrorMessage error={tax.error} />
        {tax.value && (
          <>
            <table>
              <thead>
                <tr>
                  <th>Line</th>
                  <th>Label</th>
                  <th className="money">Amount</th>
                </tr>
              </thead>
              <tbody>
                {tax.value.lines.map((line) => (
                  <tr key={line.code}>
                    <td>{line.line}</td>
                    <td>
                      {line.label}
                      <div className="muted">{line.accounts.map((a) => a.code).join(', ')}</div>
                    </td>
                    <td className="money">{formatMoney(line.amount)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {tax.value.unmapped.length > 0 && (
              <p className="error">
                {tax.value.unmapped.length} account(s) have no tax line: {tax.value.unmapped.map((a) => a.code).join(', ')}
              </p>
            )}
            <p>
              <strong>Net profit: {formatMoney(tax.value.totals.netProfit)}</strong>
            </p>
          </>
        )}
      </Card>
    </main>
  );
}
