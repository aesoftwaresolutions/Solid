import { useEffect, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../api';
import { Card, ErrorMessage } from '../components';

/**
 * Where a reset link lands. Reachable signed out — the whole point is that the person cannot sign in — and it
 * grants nothing on its own: they still log in afterwards, and still pass their second factor.
 */
export default function ResetPasswordPage() {
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const [password, setPassword] = useState('');
  const [done, setDone] = useState(false);
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  // The token is a credential. Take it out of the address bar as soon as it has been read, so it does not
  // sit in browser history or leak through a Referer header.
  useEffect(() => {
    if (token) {
      window.history.replaceState({}, '', '/reset-password');
    }
  }, [token]);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(undefined);
    api
      .resetPassword(token, password)
      .then(() => setDone(true))
      .catch(setError)
      .finally(() => setBusy(false));
  };

  if (!token) {
    return (
      <main>
        <h1>Set a new password</h1>
        <p role="alert">This link has no reset token in it. Ask your administrator for a new link.</p>
      </main>
    );
  }

  return (
    <main>
      <h1>Set a new password</h1>
      {done ? (
        <Card title="Done">
          <p role="status">
            Your password is set, and every session you had is signed out. Sign in with the new password — you
            will still be asked for your authenticator code.
          </p>
          <p>
            <Link to="/login">Sign in</Link>
          </p>
        </Card>
      ) : (
        <Card title="Choose a password">
          <p className="muted">
            The link works once and expires an hour after it was made. Twelve characters or more; a phrase of
            a few words is easier to remember and harder to guess than a short tangle of symbols.
          </p>
          <ErrorMessage error={error} />
          <form onSubmit={submit}>
            <label>
              New password
              <input
                type="password"
                value={password}
                required
                minLength={12}
                autoComplete="new-password"
                onChange={(e) => setPassword(e.target.value)}
              />
            </label>
            <button type="submit" disabled={busy}>
              Set password
            </button>
          </form>
        </Card>
      )}
    </main>
  );
}
