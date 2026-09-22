import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import { Card, ErrorMessage } from '../components';
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
