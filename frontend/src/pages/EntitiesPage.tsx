import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, formatMoney } from '../api';
import { Card, ErrorMessage, Loading, MoneyCell, useLoader } from '../components';

const ENTITY_KINDS = [
  ['sole_prop', 'Sole proprietor (Schedule C)'],
  ['smllc', 'Single-member LLC'],
  ['individual', 'Individual / household'],
  ['partnership', 'Partnership'],
  ['s_corp', 'S corporation'],
  ['c_corp', 'C corporation'],
  ['trust', 'Trust'],
];

export default function EntitiesPage() {
  const { orgId = '' } = useParams();
  const { value: entities, error, reload } = useLoader(() => api.entities(orgId), [orgId]);
  const overview = useLoader(() => api.overview(orgId), [orgId]);
  const [legalName, setLegalName] = useState('');
  const [kind, setKind] = useState('sole_prop');
  const [createError, setCreateError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  const create = (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setCreateError(undefined);
    api
      .createEntity(orgId, kind, legalName.trim())
      .then(() => {
        setLegalName('');
        reload();
      })
      .catch(setCreateError)
      .finally(() => setBusy(false));
  };

  return (
    <main>
      <h1>Entities</h1>
      <p className="muted">
        An entity is a person or business that keeps its own books — for example you personally and your LLC.
      </p>
      <p>
        <Link to={`/orgs/${orgId}/settings`}>People and activity</Link>
      </p>
      <ErrorMessage error={error} />
      {!entities && !error && <Loading what="entities" />}

      {entities && (
        <Card title="Entities in this organization">
          {entities.length === 0 ? (
            <p className="muted">No entities yet.</p>
          ) : (
            <table>
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Kind</th>
                  <th>Method</th>
                </tr>
              </thead>
              <tbody>
                {entities.map((entity) => (
                  <tr key={entity.id}>
                    <td>
                      <Link to={`/orgs/${orgId}/entities/${entity.id}`}>{entity.legalName}</Link>
                    </td>
                    <td>{entity.kind}</td>
                    <td>{entity.accountingMethod}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
      )}

      {overview.value && overview.value.entities.length > 0 && (
        <Card title="How the year is going">
          <table>
            <thead>
              <tr>
                <th>Entity</th>
                <th>Cash</th>
                <th>Net income</th>
                <th>Waiting for you</th>
              </tr>
            </thead>
            <tbody>
              {overview.value.entities.map((line) => (
                <tr key={line.entityId}>
                  <td>
                    <Link to={`/orgs/${orgId}/entities/${line.entityId}`}>{line.legalName}</Link>
                  </td>
                  <td>
                    {line.setUp ? <MoneyCell>{formatMoney(line.cash)}</MoneyCell> : <span className="muted">not set up yet</span>}
                  </td>
                  <td>{line.setUp ? <MoneyCell>{formatMoney(line.netIncome)}</MoneyCell> : ''}</td>
                  <td>
                    {line.needsAttention
                      ? [
                          line.draftEntries > 0 ? `${line.draftEntries} draft entry(s)` : null,
                          line.uncategorizedBankTransactions > 0
                            ? `${line.uncategorizedBankTransactions} bank transaction(s) to categorize`
                            : null,
                        ]
                          .filter(Boolean)
                          .join(', ')
                      : 'Nothing'}
                  </td>
                </tr>
              ))}
            </tbody>
            {overview.value.totals && (
              <tfoot>
                <tr>
                  <th>All entities ({overview.value.totals.currency})</th>
                  <th>
                    <MoneyCell>{formatMoney(overview.value.totals.cash)}</MoneyCell>
                  </th>
                  <th>
                    <MoneyCell>{formatMoney(overview.value.totals.netIncome)}</MoneyCell>
                  </th>
                  <th />
                </tr>
              </tfoot>
            )}
          </table>
          {overview.value.mixedCurrencies && (
            <p className="muted">
              These entities keep their books in different currencies, so there is no total — Solid holds no
              exchange rates and will not invent one.
            </p>
          )}
          <p className="muted">{overview.value.note}</p>
        </Card>
      )}

      <Card title="New entity">
        <ErrorMessage error={createError} />
        <form onSubmit={create}>
          <label>
            Legal name
            <input value={legalName} required maxLength={200} onChange={(e) => setLegalName(e.target.value)} />
          </label>
          <label>
            Kind
            <select value={kind} onChange={(e) => setKind(e.target.value)}>
              {ENTITY_KINDS.map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          <button type="submit" disabled={busy}>Create entity</button>
        </form>
      </Card>
    </main>
  );
}
