import { useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { api, formatMoney, type Account, type JournalEntry } from '../api';
import { Card, ErrorMessage, Loading, useLoader } from '../components';

const today = () => new Date().toISOString().slice(0, 10);
const startOfYear = () => `${new Date().getFullYear()}-01-01`;

/** A row in the entry form: an account plus one of the two columns filled in. */
interface DraftLine {
  accountId: string;
  debit: string;
  credit: string;
  memo: string;
}

const emptyLine = (): DraftLine => ({ accountId: '', debit: '', credit: '', memo: '' });

/** Reads a typed amount as whole cents, so nothing is ever compared as a float. */
function cents(value: string): number {
  const trimmed = value.trim();
  if (trimmed === '') {
    return 0;
  }
  const match = /^-?\d*(\.\d{0,2})?$/.exec(trimmed);
  if (!match) {
    return Number.NaN;
  }
  const [whole, fraction = ''] = trimmed.split('.');
  const sign = whole.startsWith('-') ? -1 : 1;
  const wholeCents = Math.abs(Number(whole || '0')) * 100;
  return sign * (wholeCents + Number(fraction.padEnd(2, '0')));
}

function format(centsValue: number): string {
  const sign = centsValue < 0 ? '-' : '';
  const absolute = Math.abs(centsValue);
  return `${sign}${Math.floor(absolute / 100)}.${String(absolute % 100).padStart(2, '0')}`;
}

export default function JournalPage() {
  const { orgId = '', entityId = '' } = useParams();
  const [from, setFrom] = useState(startOfYear());
  const [to, setTo] = useState(today());
  const accounts = useLoader(() => api.accounts(orgId, entityId), [orgId, entityId]);
  const entries = useLoader(() => api.journalEntries(orgId, entityId, from, to), [orgId, entityId, from, to]);
  const lock = useLoader(() => api.periodLock(orgId, entityId), [orgId, entityId]);
  const recurring = useLoader(() => api.recurringEntries(orgId, entityId), [orgId, entityId]);

  const [entryDate, setEntryDate] = useState(today());
  const [memo, setMemo] = useState('');
  const [lines, setLines] = useState<DraftLine[]>([emptyLine(), emptyLine()]);
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);
  const [lockThrough, setLockThrough] = useState('');
  const [runThrough, setRunThrough] = useState(today());
  const [runSummary, setRunSummary] = useState<string | null>(null);

  const postable = useMemo(
    () => (accounts.value ?? []).filter((a: Account) => !a.isHeader && !a.isArchived),
    [accounts.value],
  );
  const accountName = (id: string) => {
    const account = postable.find((a) => a.id === id);
    return account ? `${account.code} ${account.name}` : id;
  };

  const debits = lines.reduce((sum, line) => sum + cents(line.debit), 0);
  const credits = lines.reduce((sum, line) => sum + cents(line.credit), 0);
  const difference = debits - credits;
  const usable = lines.filter((line) => line.accountId && (cents(line.debit) !== 0 || cents(line.credit) !== 0));
  const balanced = difference === 0 && usable.length >= 2 && !Number.isNaN(difference);

  const update = (index: number, patch: Partial<DraftLine>) =>
    setLines(lines.map((line, i) => (i === index ? { ...line, ...patch } : line)));

  const submit = (post: boolean) => {
    setBusy(true);
    setError(undefined);
    api
      .createJournalEntry(orgId, entityId, {
        entryDate,
        memo: memo.trim() || undefined,
        post,
        // The API takes one signed amount per line: debit positive, credit negative.
        lines: usable.map((line) => ({
          accountId: line.accountId,
          amount: { amount: format(cents(line.debit) - cents(line.credit)), currency: 'USD' },
          memo: line.memo.trim() || undefined,
        })),
      })
      .then(() => {
        setLines([emptyLine(), emptyLine()]);
        setMemo('');
        entries.reload();
      })
      .catch(setError)
      .finally(() => setBusy(false));
  };

  const act = (work: Promise<unknown>) => {
    setBusy(true);
    setError(undefined);
    work.then(() => entries.reload()).catch(setError).finally(() => setBusy(false));
  };

  return (
    <main>
      <h1>Journal</h1>
      <ErrorMessage error={accounts.error} />
      <ErrorMessage error={entries.error} />
      <ErrorMessage error={error} />

      <Card title="Period lock">
        <p className="muted">
          {lock.value?.lockedThrough
            ? `Books are closed through ${lock.value.lockedThrough}. Entries on or before that date are refused.`
            : 'No period is locked yet.'}
        </p>
        <label>
          Lock through
          <input type="date" value={lockThrough} onChange={(e) => setLockThrough(e.target.value)} />
        </label>
        <button
          type="button"
          disabled={busy || !lockThrough}
          onClick={() => {
            setBusy(true);
            setError(undefined);
            api
              .setPeriodLock(orgId, entityId, lockThrough)
              .then(() => lock.reload())
              .catch(setError)
              .finally(() => setBusy(false));
          }}
        >
          Close the books
        </button>
      </Card>

      <Card title="Recurring entries">
        <ErrorMessage error={recurring.error} />
        <p className="muted">
          Templates post only when you run them — nothing is posted on a timer. Running the same range twice is
          harmless: an occurrence is posted once.
        </p>
        {recurring.value && recurring.value.length === 0 && <p className="muted">No recurring entries yet.</p>}
        {recurring.value && recurring.value.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Every</th>
                <th>Next</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {recurring.value.map((template) => (
                <tr key={template.id}>
                  <td>{template.name}</td>
                  <td>{template.frequency}</td>
                  <td>{template.active ? (template.nextDate ?? 'finished') : 'stopped'}</td>
                  <td>
                    {template.active && (
                      <button
                        type="button"
                        className="secondary"
                        disabled={busy}
                        onClick={() => {
                          setBusy(true);
                          setError(undefined);
                          api
                            .deactivateRecurringEntry(orgId, entityId, template.id)
                            .then(() => recurring.reload())
                            .catch(setError)
                            .finally(() => setBusy(false));
                        }}
                      >
                        Stop
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <label>
          Post everything due through
          <input type="date" value={runThrough} onChange={(e) => setRunThrough(e.target.value)} />
        </label>
        <button
          type="button"
          disabled={busy}
          onClick={() => {
            setBusy(true);
            setError(undefined);
            api
              .runRecurringEntries(orgId, entityId, runThrough)
              .then((result) => {
                setRunSummary(
                  `Posted ${result.posted.length}; skipped ${result.skipped.length}` +
                    (result.skipped.length > 0 ? ` (${result.skipped[0].reason})` : '.'),
                );
                entries.reload();
                recurring.reload();
              })
              .catch(setError)
              .finally(() => setBusy(false));
          }}
        >
          Run now
        </button>
        {runSummary && <p className="notice">{runSummary}</p>}
      </Card>

      <Card title="New entry">
        <label>
          Date
          <input type="date" value={entryDate} required onChange={(e) => setEntryDate(e.target.value)} />
        </label>
        <label>
          Memo
          <input value={memo} maxLength={500} onChange={(e) => setMemo(e.target.value)} />
        </label>
        <table>
          <thead>
            <tr>
              <th>Account</th>
              <th className="money">Debit</th>
              <th className="money">Credit</th>
              <th>Line memo</th>
            </tr>
          </thead>
          <tbody>
            {lines.map((line, index) => (
              <tr key={index}>
                <td>
                  <label className="visually-hidden" htmlFor={`account-${index}`}>
                    Account for line {index + 1}
                  </label>
                  <select
                    id={`account-${index}`}
                    value={line.accountId}
                    onChange={(e) => update(index, { accountId: e.target.value })}
                  >
                    <option value="">Choose…</option>
                    {postable.map((account) => (
                      <option key={account.id} value={account.id}>
                        {account.code} {account.name}
                      </option>
                    ))}
                  </select>
                </td>
                <td className="money">
                  <label className="visually-hidden" htmlFor={`debit-${index}`}>
                    Debit for line {index + 1}
                  </label>
                  <input
                    id={`debit-${index}`}
                    inputMode="decimal"
                    value={line.debit}
                    onChange={(e) => update(index, { debit: e.target.value, credit: '' })}
                  />
                </td>
                <td className="money">
                  <label className="visually-hidden" htmlFor={`credit-${index}`}>
                    Credit for line {index + 1}
                  </label>
                  <input
                    id={`credit-${index}`}
                    inputMode="decimal"
                    value={line.credit}
                    onChange={(e) => update(index, { credit: e.target.value, debit: '' })}
                  />
                </td>
                <td>
                  <label className="visually-hidden" htmlFor={`memo-${index}`}>
                    Memo for line {index + 1}
                  </label>
                  <input id={`memo-${index}`} value={line.memo} onChange={(e) => update(index, { memo: e.target.value })} />
                </td>
              </tr>
            ))}
            <tr>
              <td>Totals</td>
              <td className="money">{format(debits)}</td>
              <td className="money">{format(credits)}</td>
              <td />
            </tr>
          </tbody>
        </table>
        <button type="button" className="secondary" onClick={() => setLines([...lines, emptyLine()])}>
          Add line
        </button>{' '}
        <button type="button" onClick={() => submit(false)} disabled={busy || !balanced}>
          Save draft
        </button>{' '}
        <button type="button" onClick={() => submit(true)} disabled={busy || !balanced}>
          Post entry
        </button>
        {!balanced && (
          <p className="notice">
            {difference === 0
              ? 'Add at least two lines with an account and an amount.'
              : `Debits and credits differ by ${format(Math.abs(difference))}.`}
          </p>
        )}
      </Card>

      <Card title="Entries">
        <label>
          From
          <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label>
          To
          <input type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        </label>
        {!entries.value && !entries.error && <Loading what="entries" />}
        {entries.value && entries.value.length === 0 && <p className="muted">No entries in this range.</p>}
        {entries.value && entries.value.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Date</th>
                <th>Memo</th>
                <th>Source</th>
                <th>Status</th>
                <th>Lines</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {entries.value.map((entry: JournalEntry) => (
                <tr key={entry.id}>
                  <td>{entry.entryDate}</td>
                  <td>{entry.memo ?? ''}</td>
                  <td>{entry.source}</td>
                  <td>{entry.status}</td>
                  <td>
                    {entry.lines.map((line) => (
                      <div key={line.lineNo}>
                        {accountName(line.accountId)} {formatMoney(line.amount)}
                      </div>
                    ))}
                  </td>
                  <td>
                    {entry.status === 'draft' ? (
                      <>
                        <button
                          type="button"
                          disabled={busy}
                          onClick={() => act(api.postJournalEntry(orgId, entityId, entry.id))}
                        >
                          Post
                        </button>{' '}
                        <button
                          type="button"
                          className="secondary"
                          disabled={busy}
                          onClick={() => act(api.deleteJournalEntry(orgId, entityId, entry.id))}
                        >
                          Delete
                        </button>
                      </>
                    ) : (
                      <button
                        type="button"
                        className="secondary"
                        disabled={busy}
                        onClick={() => act(api.reverseJournalEntry(orgId, entityId, entry.id))}
                      >
                        Reverse
                      </button>
                    )}
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
