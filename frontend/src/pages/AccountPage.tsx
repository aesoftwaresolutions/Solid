import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import { Card, ErrorMessage, Loading, useLoader } from '../components';
import { useAuth } from '../auth';

/** Your own account: the one place to change your password. */
export default function AccountPage() {
  const { state } = useAuth();
  const email = state.status === 'ready' ? state.me.user.email : '';
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [done, setDone] = useState(false);
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);
  const sessions = useLoader(() => api.sessions(), []);
  const [sessionError, setSessionError] = useState<unknown>(undefined);
  const [signedOut, setSignedOut] = useState<number | null>(null);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(undefined);
    setDone(false);
    api
      .changePassword(currentPassword, newPassword)
      .then(() => {
        setDone(true);
        setCurrentPassword('');
        setNewPassword('');
      })
      .catch(setError)
      .finally(() => setBusy(false));
  };

  return (
    <main>
      <h1>Your account</h1>
      <p>
        <Link to="/">Back to organizations</Link>
      </p>

      <Card title={`Password for ${email}`}>
        <p className="muted">
          Changing it signs out every other session you have; the one you are using now stays. Twelve
          characters or more — a phrase of a few words beats a short tangle of symbols.
        </p>
        <ErrorMessage error={error} />
        <form onSubmit={submit}>
          <label>
            Current password
            <input
              type="password"
              value={currentPassword}
              required
              autoComplete="current-password"
              onChange={(e) => setCurrentPassword(e.target.value)}
            />
          </label>
          <label>
            New password
            <input
              type="password"
              value={newPassword}
              required
              minLength={12}
              autoComplete="new-password"
              onChange={(e) => setNewPassword(e.target.value)}
            />
          </label>
          <button type="submit" disabled={busy}>
            Change password
          </button>
          {done && <span role="status"> Changed. Your other sessions are signed out.</span>}
        </form>
      </Card>

      <Card title="Where you are signed in">
        <p className="muted">
          What was recorded when each session started: the address it came from and what the browser called
          itself. Nothing else — Solid does not know where you were.
        </p>
        <ErrorMessage error={sessions.error} />
        <ErrorMessage error={sessionError} />
        {!sessions.value && !sessions.error && <Loading what="your sessions" />}
        {sessions.value && (
          <>
            <table>
              <thead>
                <tr>
                  <th>Started</th>
                  <th>Last used</th>
                  <th>From</th>
                  <th>Browser</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {sessions.value.map((session) => (
                  <tr key={session.id}>
                    <td>{session.createdAt?.slice(0, 16).replace('T', ' ')}</td>
                    <td>{session.lastSeenAt?.slice(0, 16).replace('T', ' ')}</td>
                    <td>{session.ip ?? ''}</td>
                    <td className="muted">{(session.userAgent ?? '').slice(0, 60)}</td>
                    <td>
                      {session.current ? (
                        <span className="muted">this one</span>
                      ) : (
                        <button
                          type="button"
                          aria-label={`End the session from ${session.ip ?? 'unknown'}`}
                          onClick={() =>
                            api
                              .revokeSession(session.id)
                              .then(() => {
                                setSessionError(undefined);
                                sessions.reload();
                              })
                              .catch(setSessionError)
                          }
                        >
                          End it
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p>
              <button
                type="button"
                onClick={() =>
                  api
                    .revokeOtherSessions()
                    .then((result) => {
                      setSignedOut(result.revoked);
                      sessions.reload();
                    })
                    .catch(setSessionError)
                }
              >
                Sign out everywhere else
              </button>
              {signedOut !== null && <span role="status"> Ended {signedOut} other session(s).</span>}
            </p>
          </>
        )}
      </Card>

      <Card title="If you forget it">
        <p className="muted">
          There is no email on a server you run yourself, so there is no "forgot password" mail. An instance
          administrator can make you a one-time link from the installation page. If nobody can sign in at all,
          whoever runs the server starts it once with{' '}
          <code>--solid.reset-password=your@email</code>, which prints a link and exits.
        </p>
      </Card>
    </main>
  );
}
