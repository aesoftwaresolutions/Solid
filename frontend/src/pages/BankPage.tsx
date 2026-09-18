import { useMemo, useState, type ChangeEvent } from 'react';
import { useParams } from 'react-router-dom';
import { api, formatMoney, type Account, type BankTransaction } from '../api';
import { Card, ErrorMessage, Loading, useLoader } from '../components';

export default function BankPage() {
  const { orgId = '', entityId = '' } = useParams();
  const accounts = useLoader(() => api.accounts(orgId, entityId), [orgId, entityId]);
  const bankAccounts = useLoader(() => api.bankAccounts(orgId, entityId), [orgId, entityId]);
  const queue = useLoader(() => api.bankTransactions(orgId, entityId, 'new'), [orgId, entityId]);

  const [selectedBankAccount, setSelectedBankAccount] = useState('');
  const [importMessage, setImportMessage] = useState<string | null>(null);
  const [actionError, setActionError] = useState<unknown>(undefined);
  const [confirmation, setConfirmation] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [chosen, setChosen] = useState<Record<string, string>>({});

  const postable: Account[] = useMemo(
    () => (accounts.value ?? []).filter((a) => !a.isHeader && !a.isArchived),
    [accounts.value],
  );
  const bankAccountId = selectedBankAccount || bankAccounts.value?.[0]?.id || '';

  const upload = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file || !bankAccountId) {
      return;
    }
    setBusy(true);
    setActionError(undefined);
    setImportMessage(null);
    api
      .importStatement(orgId, entityId, bankAccountId, file)
      .then((result) => {
        setImportMessage(
          `Imported ${result.imported} of ${result.parsed} ${result.format.toUpperCase()} transaction(s)` +
            (result.duplicates > 0 ? `; skipped ${result.duplicates} already imported.` : '.'),
        );
        queue.reload();
      })
      .catch(setActionError)
      .finally(() => setBusy(false));
  };

  const categorize = (txn: BankTransaction) => {
    const accountId = chosen[txn.id] ?? txn.suggestedAccountId ?? '';
    if (!accountId) {
      setActionError(new Error('Pick a category first'));
      return;
    }
    setBusy(true);
    setActionError(undefined);
    api
      .categorize(orgId, entityId, txn.id, accountId)
      .then(() => {
        setConfirmation(`Recorded ${txn.postedDate} · ${formatMoney(txn.amount)} · ${txn.description}`);
        queue.reload();
      })
      .catch(setActionError)
      .finally(() => setBusy(false));
  };

  const exclude = (txn: BankTransaction) => {
    setBusy(true);
    api
      .excludeTransaction(orgId, entityId, txn.id)
      .then(() => {
        setConfirmation(`Excluded ${txn.description}`);
        queue.reload();
      })
      .catch(setActionError)
      .finally(() => setBusy(false));
  };

  return (
    <main>
      <h1>Bank activity</h1>
      <ErrorMessage error={accounts.error} />
      <ErrorMessage error={bankAccounts.error} />
      <ErrorMessage error={queue.error} />
      <ErrorMessage error={actionError} />

      <Card title="Import a statement">
        {bankAccounts.value && bankAccounts.value.length === 0 ? (
          <NewBankAccount orgId={orgId} entityId={entityId} accounts={postable} onCreated={bankAccounts.reload} />
        ) : (
          <>
            <label>
              Bank account
              <select value={bankAccountId} onChange={(e) => setSelectedBankAccount(e.target.value)}>
                {(bankAccounts.value ?? []).map((account) => (
                  <option key={account.id} value={account.id}>
                    {account.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              CSV, OFX or QFX file
              <input type="file" accept=".csv,.ofx,.qfx,text/csv" onChange={upload} disabled={busy} />
            </label>
            {importMessage && <p className="notice">{importMessage}</p>}
          </>
        )}
      </Card>

      <Card title="Review queue">
        {confirmation && <p className="notice">{confirmation}</p>}
        {!queue.value && !queue.error && <Loading what="transactions" />}
        {queue.value && queue.value.length === 0 && <p className="muted">Nothing to review. Nice.</p>}
        {queue.value && queue.value.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Date</th>
                <th>Description</th>
                <th className="money">Amount</th>
                <th>Category</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {queue.value.map((txn) => (
                <tr key={txn.id}>
                  <td>{txn.postedDate}</td>
                  <td>{txn.description}</td>
                  <td className="money">{formatMoney(txn.amount)}</td>
                  <td>
                    <label className="visually-hidden" htmlFor={`cat-${txn.id}`}>
                      Category for {txn.description}
                    </label>
                    <select
                      id={`cat-${txn.id}`}
                      value={chosen[txn.id] ?? txn.suggestedAccountId ?? ''}
                      onChange={(e) => setChosen({ ...chosen, [txn.id]: e.target.value })}
                    >
                      <option value="">Choose…</option>
                      {postable.map((account) => (
                        <option key={account.id} value={account.id}>
                          {account.code} {account.name}
                        </option>
                      ))}
                    </select>
                    {txn.suggestionSource && <div className="muted">suggested from {txn.suggestionSource}</div>}
                  </td>
                  <td>
                    <button type="button" onClick={() => categorize(txn)} disabled={busy}>
                      Save
                    </button>{' '}
                    <button type="button" className="secondary" onClick={() => exclude(txn)} disabled={busy}>
                      Exclude
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </main>
  );
}

function NewBankAccount({
  orgId,
  entityId,
  accounts,
  onCreated,
}: {
  orgId: string;
  entityId: string;
  accounts: Account[];
  onCreated: () => void;
}) {
  const candidates = accounts.filter(
    (a) => (a.type === 'asset' && a.subtype === 'bank') || (a.type === 'liability' && a.subtype === 'credit_card'),
  );
  const [name, setName] = useState('');
  const [glAccountId, setGlAccountId] = useState(candidates[0]?.id ?? '');
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  if (candidates.length === 0) {
    return <p className="muted">Create a chart of accounts first — a bank account links to a bank or credit-card ledger account.</p>;
  }

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        setBusy(true);
        setError(undefined);
        api
          .createBankAccount(orgId, entityId, name.trim(), glAccountId)
          .then(onCreated)
          .catch(setError)
          .finally(() => setBusy(false));
      }}
    >
      <ErrorMessage error={error} />
      <label>
        Account name
        <input value={name} required maxLength={120} onChange={(e) => setName(e.target.value)} />
      </label>
      <label>
        Ledger account
        <select value={glAccountId} onChange={(e) => setGlAccountId(e.target.value)}>
          {candidates.map((account) => (
            <option key={account.id} value={account.id}>
              {account.code} {account.name}
            </option>
          ))}
        </select>
      </label>
      <button type="submit" disabled={busy}>Add bank account</button>
    </form>
  );
}
