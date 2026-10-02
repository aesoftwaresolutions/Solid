import { useMemo, useState, type ChangeEvent } from 'react';
import { useParams } from 'react-router-dom';
import { api, formatMoney, type Account, type BankTransaction, type StoredDocument } from '../api';
import { Card, ErrorMessage, Loading, useLoader } from '../components';

export default function BankPage() {
  const { orgId = '', entityId = '' } = useParams();
  const accounts = useLoader(() => api.accounts(orgId, entityId), [orgId, entityId]);
  const bankAccounts = useLoader(() => api.bankAccounts(orgId, entityId), [orgId, entityId]);
  const queue = useLoader(() => api.bankTransactions(orgId, entityId, 'new'), [orgId, entityId]);
  const rules = useLoader(() => api.categorizationRules(orgId, entityId), [orgId, entityId]);
  const documents = useLoader(() => api.documents(orgId, entityId), [orgId, entityId]);
  const businessLines = useLoader(() => api.businessLines(orgId, entityId), [orgId, entityId]);
  const activeLines = (businessLines.value ?? []).filter((line) => !line.isArchived);

  const [selectedBankAccount, setSelectedBankAccount] = useState('');
  const [importMessage, setImportMessage] = useState<string | null>(null);
  const [actionError, setActionError] = useState<unknown>(undefined);
  const [confirmation, setConfirmation] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [chosen, setChosen] = useState<Record<string, string>>({});
  /** The business line picked per row (spec 069); absent means shared / unassigned. */
  const [chosenLine, setChosenLine] = useState<Record<string, string>>({});

  const postable: Account[] = useMemo(
    () => (accounts.value ?? []).filter((a) => !a.isHeader && !a.isArchived),
    [accounts.value],
  );
  const bankAccountId = selectedBankAccount || bankAccounts.value?.[0]?.id || '';

  const accountLabel = (id: string) => {
    const account = postable.find((a) => a.id === id);
    return account ? `${account.code} ${account.name}` : id;
  };

  const attachedTo = (txnId: string): StoredDocument[] =>
    (documents.value ?? []).filter((document) =>
      document.links.some((link) => link.objectType === 'bank_transaction' && link.objectId === txnId),
    );

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
      .categorize(orgId, entityId, txn.id, accountId, chosenLine[txn.id])
      .then(() => {
        setConfirmation(`Recorded ${txn.postedDate} · ${formatMoney(txn.amount)} · ${txn.description}`);
        queue.reload();
      })
      .catch(setActionError)
      .finally(() => setBusy(false));
  };

  /** Everything reviewed on screen, saved in one request instead of one per row. */
  const saveAllReviewed = () => {
    const items = (queue.value ?? [])
      .map((txn) => ({
        id: txn.id,
        accountId: chosen[txn.id] ?? txn.suggestedAccountId ?? '',
        businessLineId: chosenLine[txn.id] || undefined,
      }))
      .filter((item) => item.accountId !== '');
    if (items.length === 0) {
      setActionError(new Error('Pick a category on at least one row first'));
      return;
    }
    setBusy(true);
    setActionError(undefined);
    api
      .categorizeAll(orgId, entityId, items)
      .then(() => {
        setConfirmation(`Recorded ${items.length} transaction(s).`);
        setChosen({});
        setChosenLine({});
        queue.reload();
      })
      .catch(setActionError)
      .finally(() => setBusy(false));
  };

  /** Uploads the receipt and staples it to this transaction before the row leaves the queue. */
  const attachReceipt = (txn: BankTransaction, event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) {
      return;
    }
    setBusy(true);
    setActionError(undefined);
    api
      .uploadDocument(orgId, entityId, file, 'receipt', txn.description)
      .then((document) => api.linkDocument(orgId, entityId, document.id, 'bank_transaction', txn.id))
      .then(() => {
        setConfirmation(`Attached ${file.name} to ${txn.description}`);
        documents.reload();
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

      <Card title="Rules">
        <ErrorMessage error={rules.error} />
        <p className="muted">
          When a description contains the text, Solid suggests that account. The lowest priority number wins.
        </p>
        {postable.length > 0 && (
          <NewRule orgId={orgId} entityId={entityId} accounts={postable} onCreated={rules.reload} />
        )}
        {rules.value && rules.value.length === 0 && <p className="muted">No rules yet.</p>}
        {rules.value && rules.value.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>When the description contains</th>
                <th>Category</th>
                <th>Priority</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {[...rules.value]
                .sort((a, b) => a.priority - b.priority || a.contains.localeCompare(b.contains))
                .map((rule) => (
                  <tr key={rule.id}>
                    <td>{rule.contains}</td>
                    <td>{accountLabel(rule.accountId)}</td>
                    <td>{rule.priority}</td>
                    <td>
                      <button
                        type="button"
                        className="secondary"
                        disabled={busy}
                        onClick={() => {
                          setBusy(true);
                          setActionError(undefined);
                          api
                            .deleteCategorizationRule(orgId, entityId, rule.id)
                            .then(rules.reload)
                            .catch(setActionError)
                            .finally(() => setBusy(false));
                        }}
                      >
                        Delete
                      </button>
                    </td>
                  </tr>
                ))}
            </tbody>
          </table>
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
                <th>Receipt</th>
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
                    {activeLines.length > 0 && (
                      <>
                        <label className="visually-hidden" htmlFor={`line-${txn.id}`}>
                          Business line for {txn.description}
                        </label>
                        <select
                          id={`line-${txn.id}`}
                          value={chosenLine[txn.id] ?? ''}
                          onChange={(e) => setChosenLine({ ...chosenLine, [txn.id]: e.target.value })}
                        >
                          <option value="">(shared / unassigned)</option>
                          {activeLines.map((line) => (
                            <option key={line.id} value={line.id}>
                              {line.name}
                            </option>
                          ))}
                        </select>
                      </>
                    )}
                    {txn.suggestionSource && <div className="muted">suggested from {txn.suggestionSource}</div>}
                    <button
                      type="button"
                      className="secondary"
                      disabled={busy}
                      onClick={() => {
                        setBusy(true);
                        setActionError(undefined);
                        api
                          .suggestCategory(orgId, entityId, txn.id)
                          .then((suggestion) => {
                            if (suggestion.accountId) {
                              setChosen({ ...chosen, [txn.id]: suggestion.accountId });
                              setConfirmation(
                                `${suggestion.model} suggests ${suggestion.code} ${suggestion.name} — check it before saving.`,
                              );
                            } else {
                              setConfirmation(suggestion.reason ?? 'No suggestion.');
                            }
                          })
                          .catch(setActionError)
                          .finally(() => setBusy(false));
                      }}
                    >
                      Ask the model
                    </button>
                  </td>
                  <td>
                    {attachedTo(txn.id).map((document) => (
                      <div key={document.id}>
                        <a href={api.documentContentUrl(orgId, entityId, document.id)}>{document.filename}</a>
                      </div>
                    ))}
                    <label className="visually-hidden" htmlFor={`receipt-${txn.id}`}>
                      Receipt for {txn.description}
                    </label>
                    <input
                      id={`receipt-${txn.id}`}
                      type="file"
                      disabled={busy}
                      onChange={(event) => attachReceipt(txn, event)}
                    />
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
        {queue.value && queue.value.length > 0 && (
          <button type="button" onClick={saveAllReviewed} disabled={busy}>
            Save all reviewed
          </button>
        )}
      </Card>
    </main>
  );
}

function NewRule({
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
  const [contains, setContains] = useState('');
  const [accountId, setAccountId] = useState(accounts[0]?.id ?? '');
  const [priority, setPriority] = useState('100');
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        setBusy(true);
        setError(undefined);
        api
          .createCategorizationRule(orgId, entityId, contains.trim(), accountId, Number(priority))
          .then(() => {
            setContains('');
            onCreated();
          })
          .catch(setError)
          .finally(() => setBusy(false));
      }}
    >
      <ErrorMessage error={error} />
      <label>
        Description contains
        <input value={contains} required minLength={2} maxLength={100} onChange={(e) => setContains(e.target.value)} />
      </label>
      <label>
        Category
        <select value={accountId} onChange={(e) => setAccountId(e.target.value)}>
          {accounts.map((account) => (
            <option key={account.id} value={account.id}>
              {account.code} {account.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        Priority
        <input value={priority} inputMode="numeric" onChange={(e) => setPriority(e.target.value)} />
      </label>
      <button type="submit" disabled={busy}>
        Add rule
      </button>
    </form>
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
