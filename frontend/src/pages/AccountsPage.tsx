import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { ApiError, api, type Account, type Money } from '../api';
import { Card, ErrorMessage, Loading, useLoader } from '../components';

export default function AccountsPage() {
  const { orgId = '', entityId = '' } = useParams();
  const { value: accounts, error, reload } = useLoader(() => api.accounts(orgId, entityId), [orgId, entityId]);
  const [actionError, setActionError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  const applyTemplate = () => {
    setBusy(true);
    setActionError(undefined);
    api
      .applyTemplate(orgId, entityId, 'schedule_c')
      .then(() => reload())
      .catch(setActionError)
      .finally(() => setBusy(false));
  };

  return (
    <main>
      <h1>Chart of accounts</h1>
      <ErrorMessage error={error} />
      <ErrorMessage error={actionError} />
      {!accounts && !error && <Loading what="accounts" />}

      {accounts && (
        <Card
          title={`${accounts.length} account(s)`}
          actions={
            accounts.length === 0 ? (
              <button type="button" onClick={applyTemplate} disabled={busy}>
                Use the Schedule C template
              </button>
            ) : undefined
          }
        >
          {accounts.length === 0 ? (
            <p className="muted">
              No accounts yet. The Schedule C template creates a ready-made chart for a sole proprietor, with each
              expense already mapped to its tax line.
            </p>
          ) : (
            <table>
              <thead>
                <tr>
                  <th>Code</th>
                  <th>Name</th>
                  <th>Type</th>
                  <th>Tax line</th>
                </tr>
              </thead>
              <tbody>
                {accounts.map((account) => (
                  <tr key={account.id}>
                    <td>{account.code}</td>
                    <td style={{ paddingLeft: account.parentId ? 24 : 8 }}>
                      {account.isHeader ? <strong>{account.name}</strong> : account.name}
                    </td>
                    <td>{account.type}</td>
                    <td className="muted">{account.taxLineCode ?? (account.isHeader ? '' : '—')}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
      )}

      {accounts && accounts.length > 0 && (
        <OpeningBalances orgId={orgId} entityId={entityId} accounts={accounts} />
      )}
    </main>
  );
}

/**
 * Starting balances, typed the way they appear on a statement: a positive number is the account's normal
 * balance. The server signs them and puts the difference into opening equity.
 */
function OpeningBalances({ orgId, entityId, accounts }: { orgId: string; entityId: string; accounts: Account[] }) {
  const existing = useLoader(() => api.openingBalances(orgId, entityId), [orgId, entityId]);
  const [asOfDate, setAsOfDate] = useState(`${new Date().getFullYear()}-01-01`);
  const [amounts, setAmounts] = useState<Record<string, string>>({});
  const [equityAccountId, setEquityAccountId] = useState('');
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  const postable = accounts.filter((a) => !a.isHeader && !a.isArchived);
  const balanceSheet = postable.filter((a) => a.type === 'asset' || a.type === 'liability');
  const equityAccounts = postable.filter((a) => a.type === 'equity');
  const alreadySet = existing.value !== undefined;
  const missing = existing.error instanceof ApiError && existing.error.status === 404;

  if (alreadySet) {
    return (
      <Card title="Opening balances">
        <p className="muted">
          Set on {existing.value?.entryDate} (entry {existing.value?.id.slice(0, 8)}). To change them, reverse that
          entry in the journal and enter them again.
        </p>
      </Card>
    );
  }

  return (
    <Card title="Opening balances">
      {!missing && <ErrorMessage error={existing.error} />}
      <ErrorMessage error={error} />
      <p className="muted">
        What you had on the day you started. Type each balance as a positive number the way your statement shows it —
        the difference becomes your opening equity.
      </p>
      <label>
        As of
        <input type="date" value={asOfDate} onChange={(e) => setAsOfDate(e.target.value)} />
      </label>
      <label>
        Balancing equity account
        <select value={equityAccountId} onChange={(e) => setEquityAccountId(e.target.value)}>
          <option value="">Use the opening-balance account</option>
          {equityAccounts.map((account) => (
            <option key={account.id} value={account.id}>
              {account.code} {account.name}
            </option>
          ))}
        </select>
      </label>
      <table>
        <thead>
          <tr>
            <th>Account</th>
            <th>Type</th>
            <th className="money">Balance</th>
          </tr>
        </thead>
        <tbody>
          {balanceSheet.map((account) => (
            <tr key={account.id}>
              <td>{account.code} {account.name}</td>
              <td>{account.type}</td>
              <td className="money">
                <label className="visually-hidden" htmlFor={`opening-${account.id}`}>
                  Opening balance for {account.name}
                </label>
                <input
                  id={`opening-${account.id}`}
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
      <button
        type="button"
        disabled={busy}
        onClick={() => {
          const balances: { accountId: string; amount: Money }[] = Object.entries(amounts)
            .filter(([, amount]) => amount.trim() !== '' && Number(amount) !== 0)
            .map(([accountId, amount]) => ({ accountId, amount: { amount: amount.trim(), currency: 'USD' } }));
          setBusy(true);
          setError(undefined);
          api
            .createOpeningBalances(orgId, entityId, {
              asOfDate,
              equityAccountId: equityAccountId || undefined,
              balances,
            })
            .then(() => existing.reload())
            .catch(setError)
            .finally(() => setBusy(false));
        }}
      >
        Save opening balances
      </button>
    </Card>
  );
}
