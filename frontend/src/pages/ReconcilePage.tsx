import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { api, formatMoney, type Reconciliation, type ReconciliationCandidate } from '../api';
import { Card, ErrorMessage, Loading, useLoader } from '../components';

const today = () => new Date().toISOString().slice(0, 10);

export default function ReconcilePage() {
  const { orgId = '', entityId = '' } = useParams();
  const bankAccounts = useLoader(() => api.bankAccounts(orgId, entityId), [orgId, entityId]);
  const [selected, setSelected] = useState('');
  const bankAccountId = selected || bankAccounts.value?.[0]?.id || '';

  const history = useLoader(
    () => (bankAccountId ? api.reconciliations(orgId, entityId, bankAccountId) : Promise.resolve([])),
    [orgId, entityId, bankAccountId],
  );

  const [current, setCurrent] = useState<Reconciliation | undefined>(undefined);
  const [candidates, setCandidates] = useState<ReconciliationCandidate[]>([]);
  const [statementDate, setStatementDate] = useState(today());
  const [endingBalance, setEndingBalance] = useState('');
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  // The open reconciliation, if there is one, is what the page is about.
  useEffect(() => {
    const open = (history.value ?? []).find((row) => row.status === 'open');
    setCurrent(open);
    setError(undefined);
    if (open && bankAccountId) {
      api
        .reconciliationCandidates(orgId, entityId, bankAccountId, open.id)
        .then(setCandidates)
        .catch(setError);
    } else {
      setCandidates([]);
    }
  }, [history.value, orgId, entityId, bankAccountId]);

  const completed = (history.value ?? []).filter((row) => row.status !== 'open');
  const difference = current ? Number(current.difference.amount) : 0;

  const start = () => {
    setBusy(true);
    setError(undefined);
    api
      .startReconciliation(orgId, entityId, bankAccountId, statementDate, {
        amount: endingBalance.trim(),
        currency: 'USD',
      })
      .then(() => history.reload())
      .catch(setError)
      .finally(() => setBusy(false));
  };

  const toggle = (candidate: ReconciliationCandidate) => {
    if (!current) {
      return;
    }
    setBusy(true);
    setError(undefined);
    api
      .setReconciliationCleared(orgId, entityId, bankAccountId, current.id, [candidate.lineId], !candidate.cleared)
      .then((status) => {
        // The server's figures replace ours; nothing about the difference is computed in the browser.
        setCurrent(status);
        setCandidates(candidates.map((row) =>
          row.lineId === candidate.lineId ? { ...row, cleared: !candidate.cleared } : row));
      })
      .catch(setError)
      .finally(() => setBusy(false));
  };

  return (
    <main>
      <h1>Reconcile</h1>
      <ErrorMessage error={bankAccounts.error} />
      <ErrorMessage error={history.error} />
      <ErrorMessage error={error} />

      <Card title="Account">
        {!bankAccounts.value && !bankAccounts.error && <Loading what="bank accounts" />}
        {bankAccounts.value && bankAccounts.value.length === 0 && (
          <p className="muted">Add a bank account on the Bank page first.</p>
        )}
        {bankAccounts.value && bankAccounts.value.length > 0 && (
          <label>
            Bank account
            <select value={bankAccountId} onChange={(e) => setSelected(e.target.value)}>
              {bankAccounts.value.map((account) => (
                <option key={account.id} value={account.id}>
                  {account.name}
                </option>
              ))}
            </select>
          </label>
        )}
      </Card>

      {bankAccountId && !current && (
        <Card title="Start a reconciliation">
          <p className="muted">Take the closing date and closing balance from the statement in front of you.</p>
          <label>
            Statement date
            <input type="date" value={statementDate} onChange={(e) => setStatementDate(e.target.value)} />
          </label>
          <label>
            Statement ending balance
            <input
              inputMode="decimal"
              placeholder="0.00"
              value={endingBalance}
              onChange={(e) => setEndingBalance(e.target.value)}
            />
          </label>
          <button type="button" onClick={start} disabled={busy || endingBalance.trim() === ''}>
            Start
          </button>
        </Card>
      )}

      {current && (
        <>
          <Card title={`Statement to ${current.statementDate}`}>
            <div className="stats">
              <div className="stat">
                <div className="muted">Beginning</div>
                <div className="value">{formatMoney(current.beginningBalance)}</div>
              </div>
              <div className="stat">
                <div className="muted">Cleared ({current.clearedCount})</div>
                <div className="value">{formatMoney(current.clearedBalance)}</div>
              </div>
              <div className="stat">
                <div className="muted">Statement</div>
                <div className="value">{formatMoney(current.statementEndingBalance)}</div>
              </div>
              <div className="stat">
                <div className="muted">Difference</div>
                <div className="value">{formatMoney(current.difference)}</div>
              </div>
            </div>
            {difference !== 0 && (
              <p className="notice">
                Tick everything that appears on the statement. The difference has to reach 0.00 before this can be
                finished.
              </p>
            )}
            <button
              type="button"
              disabled={busy || difference !== 0}
              onClick={() => {
                setBusy(true);
                setError(undefined);
                api
                  .completeReconciliation(orgId, entityId, bankAccountId, current.id)
                  .then(() => history.reload())
                  .catch(setError)
                  .finally(() => setBusy(false));
              }}
            >
              Finish
            </button>
          </Card>

          <Card title="Lines on this account">
            {candidates.length === 0 && <p className="muted">Nothing to reconcile in this period.</p>}
            {candidates.length > 0 && (
              <table>
                <thead>
                  <tr>
                    <th>Cleared</th>
                    <th>Date</th>
                    <th>Memo</th>
                    <th className="money">Amount</th>
                  </tr>
                </thead>
                <tbody>
                  {candidates.map((candidate) => (
                    <tr key={candidate.lineId}>
                      <td>
                        <label className="visually-hidden" htmlFor={`cleared-${candidate.lineId}`}>
                          Cleared: {candidate.entryDate} {candidate.memo ?? ''} {candidate.amount.amount}
                        </label>
                        <input
                          id={`cleared-${candidate.lineId}`}
                          type="checkbox"
                          checked={candidate.cleared}
                          disabled={busy}
                          onChange={() => toggle(candidate)}
                        />
                      </td>
                      <td>{candidate.entryDate}</td>
                      <td>{candidate.memo ?? ''}</td>
                      <td className="money">{formatMoney(candidate.amount)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Card>
        </>
      )}

      <Card title="Finished reconciliations">
        {completed.length === 0 && <p className="muted">None yet.</p>}
        {completed.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Statement date</th>
                <th className="money">Ending balance</th>
                <th>Completed</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {completed.map((row) => (
                <tr key={row.id}>
                  <td>{row.statementDate}</td>
                  <td className="money">{formatMoney(row.statementEndingBalance)}</td>
                  <td>{row.completedAt ?? ''}</td>
                  <td>
                    <button
                      type="button"
                      className="secondary"
                      disabled={busy}
                      onClick={() => {
                        setBusy(true);
                        setError(undefined);
                        api
                          .undoReconciliation(orgId, entityId, bankAccountId, row.id)
                          .then(() => history.reload())
                          .catch(setError)
                          .finally(() => setBusy(false));
                      }}
                    >
                      Undo
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
