import { Link } from 'react-router-dom';
import { ApiError, api } from '../api';
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

  const forbidden = backup.error instanceof ApiError && backup.error.status === 403;

  return (
    <main>
      <h1>This installation</h1>
      <p>
        <Link to="/">Back to organizations</Link>
      </p>

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
