import { useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../api';
import { Card, ErrorMessage } from '../components';

/**
 * Where an invitation link lands. The person has no account yet, so this page is reachable signed out; the
 * token in the URL is the only credential, and the server decides from it which address and which books.
 */
export default function AcceptInvitationPage() {
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [done, setDone] = useState<{ created: boolean; email: string } | null>(null);
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(undefined);
    api
      .acceptInvitation(token, displayName.trim(), password)
      .then((result) => setDone({ created: result.created, email: result.email }))
      .catch(setError)
      .finally(() => setBusy(false));
  };

  if (!token) {
    return (
      <main>
        <h1>Accept an invitation</h1>
        <p role="alert">This link has no invitation in it. Ask whoever invited you to send it again.</p>
      </main>
    );
  }

  return (
    <main>
      <h1>Accept an invitation</h1>
      {done ? (
        <Card title="You are in">
          <p role="status">
            {done.created
              ? `Your account for ${done.email} is ready. Sign in, and you will be asked to set up two-factor
                 authentication before you can do anything — that is required for everyone.`
              : `${done.email} already had an account, so nothing about it changed. Sign in and you will find
                 the new books there.`}
          </p>
          <p>
            <Link to="/login">Sign in</Link>
          </p>
        </Card>
      ) : (
        <Card title="Choose a name and a password">
          <p className="muted">
            The invitation decides which email address this account has, so there is nothing to type for it.
            If that address already has an account, its password stays as it is.
          </p>
          <ErrorMessage error={error} />
          <form onSubmit={submit}>
            <label>
              Your name
              <input value={displayName} required maxLength={120} onChange={(e) => setDisplayName(e.target.value)} />
            </label>
            <label>
              Password
              <input
                type="password"
                value={password}
                required
                autoComplete="new-password"
                onChange={(e) => setPassword(e.target.value)}
              />
            </label>
            <button type="submit" disabled={busy}>
              Accept
            </button>
          </form>
        </Card>
      )}
    </main>
  );
}
