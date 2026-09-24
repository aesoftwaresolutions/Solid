import { useEffect, useState, type FormEvent } from 'react';
import { ApiError, api } from '../api';
import { useAuth } from '../auth';
import { ErrorMessage } from '../components';

type Step = 'credentials' | 'enroll' | 'recoveryCodes' | 'verify';

/** Long enough to matter, and the server enforces the same: see IamService.validatePassword. */
const MIN_PASSWORD = 12;

export default function LoginPage() {
  const { refresh } = useAuth();
  const [step, setStep] = useState<Step>('credentials');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [useRecoveryCode, setUseRecoveryCode] = useState(false);
  const [enrollment, setEnrollment] = useState<{ secret: string; otpauthUri: string } | null>(null);
  const [recoveryCodes, setRecoveryCodes] = useState<string[]>([]);
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);
  // undefined until the server has told us; there is no point guessing.
  const [setupNeeded, setSetupNeeded] = useState<boolean | undefined>(undefined);
  const [displayName, setDisplayName] = useState('');

  useEffect(() => {
    api
      .setupState()
      .then((state) => setSetupNeeded(state.setupNeeded))
      .catch(() => setSetupNeeded(false));
  }, []);

  async function run(work: () => Promise<void>) {
    setBusy(true);
    setError(undefined);
    try {
      await work();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  const submitCredentials = (event: FormEvent) => {
    event.preventDefault();
    void run(async () => {
      const result = await api.login(email, password);
      if (result.mfaEnrolled) {
        setStep('verify');
      } else {
        setEnrollment(await api.enrollMfa());
        setStep('enroll');
      }
    });
  };

  /** Makes the first account and carries straight on into two-factor setup. */
  const submitFirstAccount = (event: FormEvent) => {
    event.preventDefault();
    void run(async () => {
      await api.signup(email.trim(), password, displayName.trim());
      await api.login(email.trim(), password);
      setSetupNeeded(false);
      setEnrollment(await api.enrollMfa());
      setStep('enroll');
    });
  };

  const submitActivation = (event: FormEvent) => {
    event.preventDefault();
    void run(async () => {
      const result = await api.activateMfa(code.trim());
      setRecoveryCodes(result.recoveryCodes);
      setCode('');
      setStep('recoveryCodes');
    });
  };

  const submitVerification = (event: FormEvent) => {
    event.preventDefault();
    void run(async () => {
      if (useRecoveryCode) {
        await api.verifyMfa('', code.trim());
      } else {
        await api.verifyMfa(code.trim());
      }
      await refresh();
    });
  };

  return (
    <main>
      <h1>{setupNeeded === true && step === 'credentials' ? 'Set up Solid' : 'Sign in to Solid'}</h1>
      <ErrorMessage error={error} />

      {step === 'credentials' && setupNeeded === true && (
        <form onSubmit={submitFirstAccount} className="card">
          <h2>Create the first account</h2>
          <p>
            Nobody has an account on this copy of Solid yet, so there is no password to be told. The account
            you make here is the administrator: it can invite other people and change what the whole instance
            does. Two-factor authentication comes next, and is not optional.
          </p>
          <label>
            Your name
            <input value={displayName} required maxLength={100} autoComplete="name"
                   onChange={(e) => setDisplayName(e.target.value)} />
          </label>
          <label>
            Email
            <input type="email" value={email} required autoComplete="username"
                   onChange={(e) => setEmail(e.target.value)} />
          </label>
          <label>
            Password
            <input type="password" value={password} required minLength={MIN_PASSWORD}
                   autoComplete="new-password" onChange={(e) => setPassword(e.target.value)} />
          </label>
          <p className="muted">
            At least {MIN_PASSWORD} characters. A few unrelated words are easier to remember and harder to
            guess than something short and clever.
          </p>
          <button type="submit" disabled={busy}>Create the account</button>
        </form>
      )}

      {step === 'credentials' && setupNeeded !== true && (
        <form onSubmit={submitCredentials} className="card">
          <label>
            Email
            <input type="email" value={email} autoComplete="username" required
                   onChange={(e) => setEmail(e.target.value)} />
          </label>
          <label>
            Password
            <input type="password" value={password} autoComplete="current-password" required
                   onChange={(e) => setPassword(e.target.value)} />
          </label>
          <button type="submit" disabled={busy}>Continue</button>
        </form>
      )}

      {step === 'enroll' && enrollment && (
        <form onSubmit={submitActivation} className="card">
          <h2>Set up two-factor authentication</h2>
          <p>Add this secret to your authenticator app (1Password, Google Authenticator, Authy…), then enter the 6-digit code it shows.</p>
          <p>
            Secret: <code>{enrollment.secret}</code>
          </p>
          <p className="muted">
            Or use this link: <code>{enrollment.otpauthUri}</code>
          </p>
          <label>
            6-digit code
            <input value={code} inputMode="numeric" pattern="[0-9]{6}" required autoFocus
                   onChange={(e) => setCode(e.target.value)} />
          </label>
          <button type="submit" disabled={busy}>Turn on two-factor</button>
        </form>
      )}

      {step === 'recoveryCodes' && (
        <div className="card">
          <h2>Save your recovery codes</h2>
          <p>Each code works once if you lose your authenticator. Store them somewhere safe — they are not shown again.</p>
          <ul>
            {recoveryCodes.map((rc) => (
              <li key={rc}><code>{rc}</code></li>
            ))}
          </ul>
          <button type="button" onClick={() => void run(refresh)} disabled={busy}>
            I've saved them, continue
          </button>
        </div>
      )}

      {step === 'verify' && (
        <form onSubmit={submitVerification} className="card">
          <h2>Two-factor code</h2>
          <label>
            {useRecoveryCode ? 'Recovery code' : '6-digit code'}
            <input value={code} required autoFocus
                   inputMode={useRecoveryCode ? 'text' : 'numeric'}
                   onChange={(e) => setCode(e.target.value)} />
          </label>
          <button type="submit" disabled={busy}>Verify</button>{' '}
          <button type="button" className="secondary" onClick={() => setUseRecoveryCode(!useRecoveryCode)}>
            {useRecoveryCode ? 'Use authenticator code' : 'Use a recovery code'}
          </button>
          {error instanceof ApiError && error.code === 'SESSION_REVOKED' && (
            <p className="muted">Too many wrong codes. Reload the page and sign in again.</p>
          )}
        </form>
      )}
    </main>
  );
}
