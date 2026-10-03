import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, formatMoney, type Section } from '../api';
import { Card, ErrorMessage, Loading, useLoader } from '../components';
import { ProfitAndLossByBusinessLineCard } from './BusinessLines';

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
  const coming = useLoader(() => api.whatsComing(orgId, entityId), [orgId, entityId]);

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

      <ProfitAndLossByBusinessLineCard orgId={orgId} entityId={entityId} from={from} to={to} />

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

      <Card title="What is coming (next 90 days)">
        <ErrorMessage error={coming.error} />
        {!coming.value && !coming.error && <Loading what="what is scheduled" />}
        {coming.value && (
          <>
            <p className="muted">{coming.value.note}</p>
            <div className="stats">
              <div className="stat">
                <div className="muted">Cash today</div>
                <div className="value">{formatMoney(coming.value.openingCash)}</div>
              </div>
              <div className="stat">
                <div className="muted">Scheduled in</div>
                <div className="value">{formatMoney(coming.value.totals.in)}</div>
              </div>
              <div className="stat">
                <div className="muted">Scheduled out</div>
                <div className="value">{formatMoney(coming.value.totals.out)}</div>
              </div>
              <div className="stat">
                <div className="muted">Lowest point</div>
                <div className="value">
                  {coming.value.lowestPoint ? formatMoney(coming.value.lowestPoint.projectedBalance) : '—'}
                </div>
                {coming.value.lowestPoint && (
                  <span className="muted">on {coming.value.lowestPoint.date}</span>
                )}
              </div>
            </div>
            {coming.value.items.length === 0 ? (
              <p className="muted">Nothing is scheduled in the next 90 days.</p>
            ) : (
              <table>
                <thead>
                  <tr>
                    <th>Date</th>
                    <th>What</th>
                    <th className="money">In</th>
                    <th className="money">Out</th>
                    <th className="money">Balance after</th>
                  </tr>
                </thead>
                <tbody>
                  {coming.value.items.map((item, index) => (
                    <tr key={`${item.date}-${item.kind}-${index}`}>
                      <td>{item.date}</td>
                      <td>{item.description}</td>
                      <td className="money">
                        {item.amountIn.amount === '0.00' ? '' : formatMoney(item.amountIn)}
                      </td>
                      <td className="money">
                        {item.amountOut.amount === '0.00' ? '' : formatMoney(item.amountOut)}
                      </td>
                      <td className="money">{formatMoney(item.projectedBalance)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </>
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
