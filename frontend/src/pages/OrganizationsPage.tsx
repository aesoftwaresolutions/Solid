import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import { Card, ErrorMessage, Loading, useLoader } from '../components';

export default function OrganizationsPage() {
  const { value: organizations, error, reload } = useLoader(() => api.organizations(), []);
  const [name, setName] = useState('');
  const [kind, setKind] = useState('business');
  const [createError, setCreateError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  const create = (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setCreateError(undefined);
    api
      .createOrganization(name.trim(), kind)
      .then(() => {
        setName('');
        reload();
      })
      .catch(setCreateError)
      .finally(() => setBusy(false));
  };

  return (
    <main>
      <h1>Organizations</h1>
      <p>
        <Link to="/instance">This installation</Link>
      </p>
      <ErrorMessage error={error} />
      {!organizations && !error && <Loading what="organizations" />}

      {organizations && (
        <Card title="Your organizations">
          {organizations.length === 0 ? (
            <p className="muted">No organizations yet. Create one below to get started.</p>
          ) : (
            <ul>
              {organizations.map((org) => (
                <li key={org.id}>
                  <Link to={`/orgs/${org.id}`}>{org.name}</Link> <span className="muted">({org.kind})</span>
                </li>
              ))}
            </ul>
          )}
        </Card>
      )}

      <Card title="New organization">
        <ErrorMessage error={createError} />
        <form onSubmit={create}>
          <label>
            Name
            <input value={name} required maxLength={200} onChange={(e) => setName(e.target.value)} />
          </label>
          <label>
            Kind
            <select value={kind} onChange={(e) => setKind(e.target.value)}>
              <option value="business">Business</option>
              <option value="household">Household</option>
              <option value="firm_client">Firm client</option>
            </select>
          </label>
          <button type="submit" disabled={busy}>Create organization</button>
        </form>
      </Card>
    </main>
  );
}
