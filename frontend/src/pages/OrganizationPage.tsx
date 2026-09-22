import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ApiError, api, type AuditEvent, type Invitation } from '../api';
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
  const invitations = useLoader(() => api.invitations(orgId), [orgId]);

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

      <Card title="Invitations">
        <Denied error={invitations.error} what="invitations" />
        <p className="muted">
          Sign-up is closed on a server of your own, so this is how someone else gets an account. Solid does
          not send the email: copy the link and send it however you already talk to that person. It works
          once, for that address, and stops working after seven days.
        </p>
        <Invite orgId={orgId} onInvited={invitations.reload} />
        {invitations.value && invitations.value.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Email</th>
                <th>Role</th>
                <th>Status</th>
                <th>Expires</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {invitations.value.map((invitation: Invitation) => (
                <tr key={invitation.id}>
                  <td>{invitation.email}</td>
                  <td>{invitation.role}</td>
                  <td>{invitation.status}</td>
                  <td className="muted">{invitation.expiresAt?.slice(0, 10)}</td>
                  <td>
                    {invitation.status === 'pending' && (
                      <button
                        type="button"
                        onClick={() => api.revokeInvitation(orgId, invitation.id).then(invitations.reload)}
                      >
                        Withdraw
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
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

function Invite({ orgId, onInvited }: { orgId: string; onInvited: () => void }) {
  const [email, setEmail] = useState('');
  const [role, setRole] = useState('bookkeeper');
  const [link, setLink] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  return (
    <>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          setBusy(true);
          setError(undefined);
          setLink(null);
          api
            .invite(orgId, email.trim(), role)
            .then((invitation) => {
              setLink(`${window.location.origin}/accept-invitation?token=${encodeURIComponent(invitation.token ?? '')}`);
              setEmail('');
              onInvited();
            })
            .catch(setError)
            .finally(() => setBusy(false));
        }}
      >
        <label>
          Email to invite
          <input type="email" value={email} required onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label>
          Role for the invitation
          <select value={role} onChange={(e) => setRole(e.target.value)}>
            {ROLES.map((r) => (
              <option key={r.value} value={r.value}>
                {r.value} — {r.what}
              </option>
            ))}
          </select>
        </label>
        <button type="submit" disabled={busy}>
          Create invitation
        </button>
      </form>
      <ErrorMessage error={error} />
      {link && (
        <p role="status">
          Copy this link now — it is shown once and cannot be looked up again:{' '}
          <input readOnly value={link} aria-label="Invitation link" size={60} onFocus={(e) => e.target.select()} />
        </p>
      )}
    </>
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
