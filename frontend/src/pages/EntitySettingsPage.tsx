import { useEffect, useState, type FormEvent } from 'react';
import { useParams } from 'react-router-dom';
import { api } from '../api';
import { Card, ErrorMessage, Loading, useLoader } from '../components';

const STATES = [
  'AL', 'AK', 'AZ', 'AR', 'CA', 'CO', 'CT', 'DE', 'DC', 'FL', 'GA', 'HI', 'ID', 'IL', 'IN', 'IA', 'KS', 'KY',
  'LA', 'ME', 'MD', 'MA', 'MI', 'MN', 'MS', 'MO', 'MT', 'NE', 'NV', 'NH', 'NJ', 'NM', 'NY', 'NC', 'ND', 'OH',
  'OK', 'OR', 'PA', 'RI', 'SC', 'SD', 'TN', 'TX', 'UT', 'VT', 'VA', 'WA', 'WV', 'WI', 'WY',
];

export default function EntitySettingsPage() {
  const { orgId = '', entityId = '' } = useParams();
  const entity = useLoader(() => api.entity(orgId, entityId), [orgId, entityId]);
  const integrity = useLoader(() => api.verifyJournal(orgId, entityId), [orgId, entityId]);

  const [legalName, setLegalName] = useState('');
  const [homeState, setHomeState] = useState('');
  const [accountingMethod, setAccountingMethod] = useState('cash');
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (entity.value) {
      setLegalName(entity.value.legalName);
      setHomeState(entity.value.homeState ?? '');
      setAccountingMethod(entity.value.accountingMethod);
    }
  }, [entity.value]);

  const save = (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(undefined);
    setSaved(false);
    api
      .updateEntity(orgId, entityId, {
        legalName: legalName.trim(),
        accountingMethod,
        homeState: homeState || undefined,
      })
      .then((updated) => {
        entity.setValue(updated);
        setSaved(true);
      })
      .catch(setError)
      .finally(() => setBusy(false));
  };

  return (
    <main>
      <h1>Entity settings</h1>
      <ErrorMessage error={entity.error} />
      {!entity.value && !entity.error && <Loading what="this entity" />}

      <Card title="Are the books intact?">
        <ErrorMessage error={integrity.error} />
        {!integrity.value && !integrity.error && <Loading what="the integrity check" />}
        {integrity.value && (
          <p role="status">
            {integrity.value.valid
              ? `Every one of the ${integrity.value.postedEntries} posted entries still hashes to the one before it,
                 so nothing has been altered or removed since it was posted.`
              : `The chain breaks at entry ${integrity.value.firstInvalidSeq}. Something changed the posted
                 entries outside Solid — restore from a backup and tell whoever administers this server.`}
          </p>
        )}
      </Card>

      {entity.value && (
        <Card title="How this entity keeps its books">
          <p className="muted">
            The kind of entity ({entity.value.kind}), its base currency ({entity.value.baseCurrency}) and its
            fiscal year end (month {entity.value.fiscalYearEnd}) are fixed here: changing any of them would
            change what entries already posted mean.
          </p>
          <ErrorMessage error={error} />
          <form onSubmit={save}>
            <label>
              Legal name
              <input value={legalName} required maxLength={200} onChange={(e) => setLegalName(e.target.value)} />
            </label>
            <label>
              Home state
              <select value={homeState} onChange={(e) => setHomeState(e.target.value)}>
                <option value="">(not said yet)</option>
                {STATES.map((code) => (
                  <option key={code} value={code}>
                    {code}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Accounting method
              <select value={accountingMethod} onChange={(e) => setAccountingMethod(e.target.value)}>
                <option value="cash">Cash — counted when the money moves</option>
                <option value="accrual">Accrual — counted when it is earned or owed</option>
              </select>
            </label>
            <button type="submit" disabled={busy}>
              Save
            </button>
            {saved && <span role="status"> Saved.</span>}
          </form>
        </Card>
      )}
    </main>
  );
}
