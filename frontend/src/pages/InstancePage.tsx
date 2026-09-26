import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, DatabaseSslMode, api } from '../api';
import { Card, ErrorMessage, Loading, useLoader } from '../components';

/** Bytes for reading; the exact number stays available on hover. */
function bytes(value: number): string {
  if (value < 1024 * 1024) {
    return `${Math.max(1, Math.round(value / 1024))} KB`;
  }
  if (value < 1024 * 1024 * 1024) {
    return `${(value / 1024 / 1024).toFixed(1)} MB`;
  }
  return `${(value / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

export default function InstancePage() {
  const info = useLoader(() => api.systemInfo(), []);
  const backup = useLoader(() => api.backupStatus(), []);
  const packs = useLoader(() => api.taxRulePacks(), []);
  const users = useLoader(() => api.instanceUsers(), []);
  const [resetLink, setResetLink] = useState<{ email: string; link: string } | null>(null);
  const [resetError, setResetError] = useState<unknown>(undefined);
  const figures = useLoader(() => api.taxFigures(), []);
  const figureKeys = useLoader(() => api.taxFigureKeys(), []);

  const forbidden = backup.error instanceof ApiError && backup.error.status === 403;

  return (
    <main>
      <h1>This installation</h1>
      <p>
        <Link to="/">Back to organizations</Link>
      </p>

      <Card title="People on this installation">
        <ErrorMessage error={users.error instanceof ApiError && users.error.status === 403 ? undefined : users.error} />
        {users.error instanceof ApiError && users.error.status === 403 && (
          <p className="muted">Only an instance administrator can see who has an account here.</p>
        )}
        {users.value && (
          <>
            <p className="muted">
              Solid sends no email, so this is how someone who has forgotten their password gets back in: make
              a link and pass it to them. It lasts an hour, works once, and does not turn off their
              authenticator.
            </p>
            <ErrorMessage error={resetError} />
            <table>
              <thead>
                <tr>
                  <th>Email</th>
                  <th>Name</th>
                  <th>MFA</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {users.value.map((user) => (
                  <tr key={user.id}>
                    <td>{user.email}</td>
                    <td>{user.displayName}{user.isInstanceAdmin ? ' (administrator)' : ''}</td>
                    <td>{user.mfaEnabled ? 'on' : 'not set up'}</td>
                    <td>
                      <button
                        type="button"
                        aria-label={`Reset password for ${user.email}`}
                        onClick={() => {
                          setResetError(undefined);
                          api
                            .issuePasswordReset(user.id)
                            .then((reset) =>
                              setResetLink({
                                email: reset.email,
                                link: `${window.location.origin}${reset.resetPath}`,
                              }),
                            )
                            .catch(setResetError);
                        }}
                      >
                        Make a reset link
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {resetLink && (
              <p role="status">
                Link for {resetLink.email} — copy it now, it is shown once:{' '}
                <input readOnly value={resetLink.link} aria-label="Reset link" size={60} onFocus={(e) => e.target.select()} />{' '}
                <button type="button" onClick={() => setResetLink(null)}>
                  Done — hide it
                </button>
              </p>
            )}
          </>
        )}
      </Card>

      {figures.value && figureKeys.value && (
        <Card title="Tax figures added here">
          <p className="muted">
            Solid ships the figures it knows and refuses to guess the rest. When the IRS publishes a new
            year's rate, add it here with the notice it came from — no rebuild, and the source is shown
            wherever the figure is used. Solid does not check that a figure is right; that is on whoever
            enters it.
          </p>
          <AddTaxFigure keys={figureKeys.value} onAdded={figures.reload} />
          {figures.value.length === 0 ? (
            <p className="muted">Nothing added yet: the built-in figures are in use.</p>
          ) : (
            <table>
              <thead>
                <tr>
                  <th>Figure</th>
                  <th>Year</th>
                  <th>Value</th>
                  <th>Status</th>
                  <th>Source</th>
                </tr>
              </thead>
              <tbody>
                {figures.value.map((figure) => (
                  <tr key={figure.id}>
                    <td>{figure.key}</td>
                    <td>{figure.taxYear}</td>
                    <td>
                      {figure.value} <span className="muted">{figure.unit}</span>
                    </td>
                    <td>{figure.inUse ? 'in use' : 'superseded'}</td>
                    <td className="muted">{figure.source}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
      )}

      <Card title="Version">
        <ErrorMessage error={info.error} />
        {!info.value && !info.error && <Loading what="version" />}
        {info.value && (
          <ul>
            <li>{info.value.name} {info.value.version}</li>
            <li>Database schema {info.value.databaseSchemaVersion}</li>
          </ul>
        )}
      </Card>

      {info.value?.desktopMode && <DatabaseConnectionSettings />}

      <Card title="Backup readiness">
        {forbidden ? (
          <p className="muted">Only an instance administrator can see the backup status.</p>
        ) : (
          <ErrorMessage error={backup.error} />
        )}
        {!backup.value && !backup.error && <Loading what="backup status" />}
        {backup.value && (
          <>
            <table>
              <tbody>
                <tr>
                  <td>Instance id</td>
                  <td>{backup.value.instanceId}</td>
                </tr>
                <tr>
                  <td>Master key fingerprint</td>
                  <td>{backup.value.keyFingerprint}</td>
                </tr>
                <tr>
                  <td>Database</td>
                  <td title={`${backup.value.databaseBytes} bytes`}>{bytes(backup.value.databaseBytes)}</td>
                </tr>
                <tr>
                  <td>Documents</td>
                  <td title={`${backup.value.documents.bytes} bytes`}>
                    {backup.value.documents.count} file(s), {bytes(backup.value.documents.bytes)}
                  </td>
                </tr>
                <tr>
                  <td>Document folder</td>
                  <td>{backup.value.documentsRoot}</td>
                </tr>
              </tbody>
            </table>
            <p className="notice">
              A backup of this instance can only be restored on a server whose SOLID_MASTER_KEY has this same
              fingerprint. Keep that key somewhere other than the backup — see docs/operations.md.
            </p>
            <table>
              <thead>
                <tr>
                  <th>Table</th>
                  <th className="money">Rows (estimate)</th>
                </tr>
              </thead>
              <tbody>
                {Object.entries(backup.value.tableEstimates).map(([table, rows]) => (
                  <tr key={table}>
                    <td>{table}</td>
                    <td className="money">{rows}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
      </Card>

      <Card title="Tax figures on file">
        <ErrorMessage error={packs.error} />
        {!packs.value && !packs.error && <Loading what="rule packs" />}
        {packs.value && (
          <table>
            <thead>
              <tr>
                <th>Pack</th>
                <th>Years</th>
                <th>Source</th>
                <th>Known gaps</th>
              </tr>
            </thead>
            <tbody>
              {packs.value.map((pack) => (
                <tr key={pack.id}>
                  <td>{pack.title}</td>
                  <td>
                    {pack.taxYears.join(', ')}
                    {pack.appliesFromTaxYear ? `from ${pack.appliesFromTaxYear}` : ''}
                  </td>
                  <td className="muted">{pack.source}</td>
                  <td className="muted">{pack.todos.join(' ')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </main>
  );
}

function AddTaxFigure({
  keys,
  onAdded,
}: {
  keys: { key: string; unit: string; decimalPlaces: number }[];
  onAdded: () => void;
}) {
  const [key, setKey] = useState(keys[0]?.key ?? '');
  const [taxYear, setTaxYear] = useState(String(new Date().getFullYear()));
  const [value, setValue] = useState('');
  const [source, setSource] = useState('');
  const [supersede, setSupersede] = useState(false);
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  const chosen = keys.find((k) => k.key === key);

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        setBusy(true);
        setError(undefined);
        api
          .addTaxFigure({ key, taxYear: Number(taxYear), value: value.trim(), source: source.trim(), supersede })
          .then(() => {
            setValue('');
            setSource('');
            setSupersede(false);
            onAdded();
          })
          .catch(setError)
          .finally(() => setBusy(false));
      }}
    >
      <ErrorMessage error={error} />
      <label>
        Figure
        <select value={key} onChange={(e) => setKey(e.target.value)}>
          {keys.map((k) => (
            <option key={k.key} value={k.key}>
              {k.key} ({k.unit})
            </option>
          ))}
        </select>
      </label>
      <label>
        Tax year
        <input value={taxYear} required inputMode="numeric" onChange={(e) => setTaxYear(e.target.value)} />
      </label>
      <label>
        Value {chosen ? `(${chosen.unit}, ${chosen.decimalPlaces} decimal places)` : ''}
        <input value={value} required onChange={(e) => setValue(e.target.value)} />
      </label>
      <label>
        Where it comes from
        <textarea
          value={source}
          required
          minLength={30}
          rows={2}
          placeholder="IRS Notice 2026-03, standard mileage rates for 2026, read on irs.gov"
          onChange={(e) => setSource(e.target.value)}
        />
      </label>
      <label>
        <input type="checkbox" checked={supersede} onChange={(e) => setSupersede(e.target.checked)} />
        Replace the figure already on file for that year
      </label>
      <button type="submit" disabled={busy}>
        Add figure
      </button>
    </form>
  );
}

/** Desktop builds only: point Solid at a PostgreSQL server other than the bundled one (spec 066). */
function DatabaseConnectionSettings() {
  const status = useLoader(() => api.desktopDatabaseStatus(), []);
  const [host, setHost] = useState('');
  const [port, setPort] = useState('5432');
  const [database, setDatabase] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [sslMode, setSslMode] = useState<DatabaseSslMode>('require');
  const [testResult, setTestResult] = useState<{ ok: boolean; message?: string } | undefined>(undefined);
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);
  const [restartNotice, setRestartNotice] = useState<string | undefined>(undefined);

  useEffect(() => {
    if (status.value?.current) {
      setHost(status.value.current.host);
      setPort(String(status.value.current.port));
      setDatabase(status.value.current.database);
      setUsername(status.value.current.username);
      setSslMode(status.value.current.sslMode);
    }
  }, [status.value]);

  function input(): { host: string; port: number; database: string; username: string; password: string; sslMode: DatabaseSslMode } {
    return { host, port: Number(port) || 5432, database, username, password, sslMode };
  }

  return (
    <Card title="Database connection">
      <p className="muted">
        By default Solid runs its own PostgreSQL server on this computer. If you already have a PostgreSQL
        server elsewhere — a small office server, a NAS — Solid can use that instead. A change here takes
        effect the next time Solid starts, not immediately.
      </p>
      <ErrorMessage error={status.error} />
      {!status.value && !status.error && <Loading what="database settings" />}
      {status.value && (
        <>
          <p>
            Currently using: <strong>{status.value.mode === 'remote' ? 'a server you configured' : "Solid's built-in database"}</strong>
          </p>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              setBusy(true);
              setError(undefined);
              setTestResult(undefined);
              api
                .testDesktopDatabase(input())
                .then(setTestResult)
                .catch(setError)
                .finally(() => setBusy(false));
            }}
          >
            <label>
              Host
              <input value={host} required onChange={(e) => setHost(e.target.value)} placeholder="192.168.1.50" />
            </label>
            <label>
              Port
              <input value={port} required inputMode="numeric" onChange={(e) => setPort(e.target.value)} />
            </label>
            <label>
              Database name
              <input value={database} required onChange={(e) => setDatabase(e.target.value)} />
            </label>
            <label>
              Username
              <input value={username} required onChange={(e) => setUsername(e.target.value)} />
            </label>
            <label>
              Password
              <input
                type="password"
                value={password}
                required
                onChange={(e) => setPassword(e.target.value)}
                placeholder={status.value.mode === 'remote' ? 'enter to change or confirm' : ''}
              />
            </label>
            <label>
              Encryption
              <select value={sslMode} onChange={(e) => setSslMode(e.target.value as DatabaseSslMode)}>
                <option value="require">Required (recommended)</option>
                <option value="verify-full">Required, and verify the server's certificate</option>
                <option value="disable">None — only for a trusted local network</option>
              </select>
            </label>
            <ErrorMessage error={error} />
            {testResult && (
              <p role="status" className={testResult.ok ? undefined : 'error'}>
                {testResult.ok ? 'Connected successfully.' : `Could not connect: ${testResult.message}`}
              </p>
            )}
            {restartNotice && <p role="status">{restartNotice}</p>}
            <button type="submit" disabled={busy}>
              Test connection
            </button>{' '}
            <button
              type="button"
              disabled={busy}
              onClick={() => {
                setBusy(true);
                setError(undefined);
                setRestartNotice(undefined);
                api
                  .saveDesktopDatabase(input())
                  .then(() =>
                    setRestartNotice('Saved. Quit and reopen Solid for the new database to take effect.'),
                  )
                  .catch(setError)
                  .finally(() => setBusy(false));
              }}
            >
              Save and use this database
            </button>{' '}
            {status.value.mode === 'remote' && (
              <button
                type="button"
                disabled={busy}
                onClick={() => {
                  setBusy(true);
                  setError(undefined);
                  setRestartNotice(undefined);
                  api
                    .resetDesktopDatabase()
                    .then(() =>
                      setRestartNotice(
                        'Saved. Quit and reopen Solid to go back to the built-in database.',
                      ),
                    )
                    .catch(setError)
                    .finally(() => setBusy(false));
                }}
              >
                Use the built-in database instead
              </button>
            )}
          </form>
        </>
      )}
    </Card>
  );
}
