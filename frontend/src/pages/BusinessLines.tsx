import { useState, type FormEvent } from 'react';
import { api, formatMoney, type BusinessLine, type ProfitAndLossByBusinessLine } from '../api';
import { Card, ErrorMessage, Loading, useLoader } from '../components';

/** Settings card: name the facets of the business, rename them, archive the ones that stop (spec 069). */
export function BusinessLinesCard({ orgId, entityId }: { orgId: string; entityId: string }) {
  const lines = useLoader(() => api.businessLines(orgId, entityId), [orgId, entityId]);
  const [name, setName] = useState('');
  const [editing, setEditing] = useState<{ id: string; name: string } | null>(null);
  const [error, setError] = useState<unknown>(undefined);

  const add = (event: FormEvent) => {
    event.preventDefault();
    setError(undefined);
    api
      .createBusinessLine(orgId, entityId, name.trim())
      .then(() => {
        setName('');
        lines.reload();
      })
      .catch(setError);
  };

  const change = (line: BusinessLine, changes: { name?: string; archived?: boolean }) => {
    setError(undefined);
    api
      .updateBusinessLine(orgId, entityId, line.id, changes)
      .then(() => {
        setEditing(null);
        lines.reload();
      })
      .catch(setError);
  };

  return (
    <Card title="Business lines">
      <p className="muted">
        The parts of the business you want to see separately — web design, automation, a product. Choose one on an
        invoice, a bill or a bank transaction and the profit &amp; loss by business line splits by it. Leave shared
        costs unassigned. This is for you, not the tax return: what your preparer receives does not change.
      </p>
      <ErrorMessage error={error} />
      <ErrorMessage error={lines.error} />
      {!lines.value && !lines.error && <Loading what="business lines" />}
      {lines.value && lines.value.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>Name</th>
              <th>State</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {lines.value.map((line) => (
              <tr key={line.id}>
                <td>
                  {editing?.id === line.id ? (
                    <input
                      aria-label={`New name for ${line.name}`}
                      value={editing.name}
                      maxLength={60}
                      onChange={(e) => setEditing({ id: line.id, name: e.target.value })}
                    />
                  ) : (
                    line.name
                  )}
                </td>
                <td className="muted">{line.isArchived ? 'Archived' : 'In use'}</td>
                <td>
                  {editing?.id === line.id ? (
                    <>
                      <button
                        type="button"
                        disabled={!editing.name.trim()}
                        onClick={() => change(line, { name: editing.name.trim() })}
                      >
                        Save
                      </button>{' '}
                      <button type="button" onClick={() => setEditing(null)}>
                        Cancel
                      </button>
                    </>
                  ) : (
                    <button type="button" onClick={() => setEditing({ id: line.id, name: line.name })}>
                      Rename
                    </button>
                  )}{' '}
                  <button type="button" onClick={() => change(line, { archived: !line.isArchived })}>
                    {line.isArchived ? 'Restore' : 'Archive'}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {lines.value && lines.value.length === 0 && <p className="muted">No business lines yet.</p>}
      <form onSubmit={add}>
        <label>
          New business line
          <input value={name} required maxLength={60} onChange={(e) => setName(e.target.value)} />
        </label>
        <button type="submit">Add</button>
      </form>
    </Card>
  );
}

/**
 * A select for choosing a business line on an invoice, a bill or a bank transaction. Archived lines are not
 * offered. Renders nothing until the entity has at least one line, so screens look as they always did.
 */
export function BusinessLineSelect({
  lines,
  value,
  onChange,
  label = 'Business line',
}: {
  lines: BusinessLine[] | undefined;
  value: string;
  onChange: (id: string) => void;
  label?: string;
}) {
  const active = (lines ?? []).filter((line) => !line.isArchived);
  if (active.length === 0) {
    return null;
  }
  return (
    <label>
      {label}
      <select value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">(shared / unassigned)</option>
        {active.map((line) => (
          <option key={line.id} value={line.id}>
            {line.name}
          </option>
        ))}
      </select>
    </label>
  );
}

/** Reports card: the year's profit & loss with one column per business line. */
export function ProfitAndLossByBusinessLineCard({
  orgId,
  entityId,
  from,
  to,
}: {
  orgId: string;
  entityId: string;
  from: string;
  to: string;
}) {
  const report = useLoader(
    () => api.profitAndLossByBusinessLine(orgId, entityId, from, to),
    [orgId, entityId, from, to],
  );
  return (
    <Card
      title="Profit & loss by business line"
      actions={
        <a href={api.profitAndLossByBusinessLineCsvUrl(orgId, entityId, from, to)} download>
          Download CSV
        </a>
      }
    >
      <ErrorMessage error={report.error} />
      {!report.value && !report.error && <Loading what="the business-line report" />}
      {report.value && <BusinessLineTable report={report.value} />}
    </Card>
  );
}

function BusinessLineTable({ report }: { report: ProfitAndLossByBusinessLine }) {
  if (report.columns.length === 1) {
    return (
      <p className="muted">
        Everything is shared so far. Add business lines in Settings and choose one on invoices, bills and bank
        transactions to see each part of the business on its own.
      </p>
    );
  }
  const sections: { key: 'income' | 'costOfGoodsSold' | 'expenses'; title: string }[] = [
    { key: 'income', title: 'Income' },
    { key: 'costOfGoodsSold', title: 'Cost of goods sold' },
    { key: 'expenses', title: 'Expenses' },
  ];
  const totals: { key: 'income' | 'costOfGoodsSold' | 'grossProfit' | 'expenses' | 'netIncome'; label: string }[] = [
    { key: 'income', label: 'Total income' },
    { key: 'costOfGoodsSold', label: 'Total cost of goods sold' },
    { key: 'grossProfit', label: 'Gross profit' },
    { key: 'expenses', label: 'Total expenses' },
    { key: 'netIncome', label: 'Net income' },
  ];
  return (
    <table>
        <thead>
          <tr>
            <th>Account</th>
            {report.columns.map((column) => (
              <th key={column.businessLineId ?? 'shared'} className="money">
                {column.name}
                {column.archived ? ' (archived)' : ''}
              </th>
            ))}
            <th className="money">Total</th>
          </tr>
        </thead>
        <tbody>
          {sections.map((section) => {
            const rows = report.rows.filter((row) => row.section === section.key);
            if (rows.length === 0) {
              return null;
            }
            return [
              <tr key={`${section.key}-head`}>
                <th colSpan={report.columns.length + 2}>{section.title}</th>
              </tr>,
              ...rows.map((row) => (
                <tr key={row.accountId}>
                  <td>
                    {row.code} {row.name}
                  </td>
                  {row.amounts.map((amount, index) => (
                    <td key={index} className="money">
                      {formatMoney(amount)}
                    </td>
                  ))}
                  <td className="money">{formatMoney(row.total)}</td>
                </tr>
              )),
            ];
          })}
          {totals.map((total) => (
            <tr key={total.key}>
              <td>
                <strong>{total.label}</strong>
              </td>
              {report.columns.map((column) => (
                <td key={column.businessLineId ?? 'shared'} className="money">
                  <strong>{formatMoney(column[total.key])}</strong>
                </td>
              ))}
              <td className="money">
                <strong>{formatMoney(report.total[total.key])}</strong>
              </td>
            </tr>
          ))}
        </tbody>
    </table>
  );
}
