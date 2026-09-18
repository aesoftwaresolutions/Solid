import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ApiError, api, type AuditEvent } from '../api';
import { Card, ErrorMessage, Loading, useLoader } from '../components';

const ROLES: { value: string; what: string }[] = [
  { value: 'owner', what: 'everything, including adding other owners' },
  { value: 'admin', what: 'everything except adding owners' },
  { value: 'accountant', what: 'full access to the books and reports' },
  { value: 'bookkeeper', what: 'day-to-day entry: bank review, invoices, bills' },
  { value: 'viewer', what: 'read-only' },
];

/** A 403 here is an answer, not a bug: show it plainly instead of an empty panel. */
function Denied({ error, what }: { error: unknown; what: string }) {
  if (error instanceof ApiError && error.status === 403) {
    return <p className="muted">Only owners and admins can see {what}.</p>;
  }
  return <ErrorMessage error={error} />;
}

function detailsText(details: Record<string, unknown>): string {
  return Object.entries(details ?? {})
    .map(([key, value]) => `${key}=${typeof value === 'object' ? JSON.stringify(value) : String(value)}`)
    .join(' · ');
}

export default function OrganizationPage() {
  const { orgId = '' } = useParams();
  const [limit, setLimit] = useState(50);
  const members = useLoader(() => api.members(orgId), [orgId]);
  const events = useLoader(() => api.auditEvents(orgId, limit), [orgId, limit]);

  return (
    <main>
      <h1>Organization</h1>
      <p>
        <Link to={`/orgs/${orgId}`}>Back to entities</Link>
      </p>

      <Card title="People">
        <Denied error={members.error} what="who has access" />
        {!members.value && !members.error && <Loading what="members" />}
        {members.value && (
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Email</th>
                <th>Role</th>
              </tr>
            </thead>
            <tbody>
              {members.value.map((member) => (
                <tr key={member.userId}>
                  <td>{member.displayName}</td>
                  <td>{member.email}</td>
                  <td>{member.role}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {members.value && <AddMember orgId={orgId} onAdded={members.reload} />}
      </Card>

      <Card title="Activity">
        <Denied error={events.error} what="the activity log" />
        <label>
          Rows
          <select value={String(limit)} onChange={(e) => setLimit(Number(e.target.value))}>
            {[50, 200, 500].map((value) => (
              <option key={value} value={String(value)}>
                {value}
              </option>
            ))}
          </select>
        </label>
        {!events.value && !events.error && <Loading what="activity" />}
        {events.value && events.value.length === 0 && <p className="muted">Nothing recorded yet.</p>}
        {events.value && events.value.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>When</th>
                <th>Action</th>
                <th>Object</th>
                <th>Details</th>
              </tr>
            </thead>
            <tbody>
              {events.value.map((event: AuditEvent) => (
                <tr key={event.id}>
                  <td>{event.occurredAt}</td>
                  <td>{event.action}</td>
                  <td>
                    {event.objectType ?? ''} {event.objectId ? event.objectId.slice(0, 8) : ''}
                  </td>
                  <td className="muted">{detailsText(event.details)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </main>
  );
}

function AddMember({ orgId, onAdded }: { orgId: string; onAdded: () => void }) {
  const [email, setEmail] = useState('');
  const [role, setRole] = useState('bookkeeper');
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        setBusy(true);
        setError(undefined);
        api
          .addMember(orgId, email.trim(), role)
          .then(() => {
            setEmail('');
            onAdded();
          })
          .catch(setError)
          .finally(() => setBusy(false));
      }}
    >
      <ErrorMessage error={error} />
      <p className="muted">The person needs an account on this server already.</p>
      <label>
        Email
        <input type="email" value={email} required maxLength={254} onChange={(e) => setEmail(e.target.value)} />
      </label>
      <label>
        Role
        <select value={role} onChange={(e) => setRole(e.target.value)}>
          {ROLES.map((option) => (
            <option key={option.value} value={option.value}>
              {option.value}
            </option>
          ))}
        </select>
      </label>
      <p className="muted">{ROLES.find((option) => option.value === role)?.what}</p>
      <button type="submit" disabled={busy}>
        Add member
      </button>
    </form>
  );
}
