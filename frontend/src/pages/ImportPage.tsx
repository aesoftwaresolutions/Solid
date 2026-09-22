import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { api, type ImportKind, type ImportResult } from '../api';
import { Card, ErrorMessage } from '../components';

const KINDS: { kind: ImportKind; label: string; required: string; optional: string; sample: string }[] = [
  {
    kind: 'accounts',
    label: 'Chart of accounts',
    required: 'code, name, type',
    optional: 'subtype, parent, header, tax line',
    sample: 'code,name,type,subtype,parent,header\n1000,Assets,asset,,,yes\n1010,Checking,asset,bank,1000,no\n',
  },
  {
    kind: 'customers',
    label: 'Customers',
    required: 'name',
    optional: 'email, phone, billing address, notes',
    sample: 'name,email,phone\nAcme Inc,ap@acme.example,555-0100\n',
  },
  {
    kind: 'vendors',
    label: 'Vendors',
    required: 'name',
    optional: 'email, phone, address, tax classification, 1099, default expense account',
    sample: 'name,email,1099,default expense account\nJane Contractor,jane@example.test,yes,6220\n',
  },
];

export default function ImportPage() {
  const { orgId = '', entityId = '' } = useParams();
  const [kind, setKind] = useState<ImportKind>('accounts');
  const [csv, setCsv] = useState('');
  const [result, setResult] = useState<ImportResult | undefined>(undefined);
  const [committed, setCommitted] = useState(false);
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  const chosen = KINDS.find((k) => k.kind === kind)!;

  const run = (commit: boolean) => {
    setBusy(true);
    setError(undefined);
    const call = commit ? api.runImport : api.previewImport;
    call(orgId, entityId, kind, csv)
      .then((r) => {
        setResult(r);
        setCommitted(r.committed);
      })
      .catch((e) => {
        // The old preview no longer describes what would happen, so it goes: otherwise the Import button
        // stays lit on a file that has just proved it cannot be imported.
        setResult(undefined);
        setCommitted(false);
        setError(e);
      })
      .finally(() => setBusy(false));
  };

  const readFile = (file: File | undefined) => {
    if (!file) {
      return;
    }
    file
      .text()
      .then((text) => {
        setCsv(text);
        setResult(undefined);
        setCommitted(false);
      })
      .catch(setError);
  };

  return (
    <main>
      <h1>Bring your lists in</h1>
      <p className="muted">
        Paste or choose a CSV file exported from your old program. Nothing is written until you have seen what the
        import will do, and a file with any problem in it is not imported at all.
      </p>
      <p className="muted">
        Balances and transactions do not come in this way — enter those as opening balances, so the ledger can prove
        them.
      </p>

      <Card title="The file">
        <label>
          What is in it
          <select
            value={kind}
            onChange={(e) => {
              setKind(e.target.value as ImportKind);
              setResult(undefined);
              setCommitted(false);
            }}
          >
            {KINDS.map((k) => (
              <option key={k.kind} value={k.kind}>
                {k.label}
              </option>
            ))}
          </select>
        </label>
        <p className="muted">
          Needs a column for {chosen.required}. May also have: {chosen.optional}.
        </p>
        <p>
          <input
            type="file"
            accept=".csv,text/csv"
            aria-label="Choose a CSV file"
            onChange={(e) => readFile(e.target.files?.[0])}
          />
        </p>
        <textarea
          aria-label="CSV contents"
          rows={10}
          value={csv}
          placeholder={chosen.sample}
          onChange={(e) => {
            setCsv(e.target.value);
            setResult(undefined);
            setCommitted(false);
          }}
        />
        <p>
          <button type="button" disabled={busy || csv.trim() === ''} onClick={() => run(false)}>
            Check the file
          </button>{' '}
          <button
            type="button"
            disabled={busy || !result || !result.ready || committed}
            onClick={() => run(true)}
          >
            Import {result ? result.created : 0} row(s)
          </button>
        </p>
        <ErrorMessage error={error} />
      </Card>

      {result && (
        <Card title={committed ? 'Imported' : 'What this file would do'}>
          {committed ? (
            <p role="status">
              Added {result.created}, left {result.skipped} alone because they were already there.
            </p>
          ) : result.ready ? (
            <p role="status">
              {result.created} to add, {result.skipped} already there. Nothing has been written yet.
            </p>
          ) : (
            <p role="alert">
              {result.problemCount} row(s) need fixing first. Nothing was written — fix the file and check it again.
            </p>
          )}
          {result.ignoredColumns.length > 0 && (
            <p className="muted">Columns we do not use, and ignored: {result.ignoredColumns.join(', ')}.</p>
          )}
          <table>
            <thead>
              <tr>
                <th>Line</th>
                <th>What</th>
                <th>Action</th>
                <th>Detail</th>
              </tr>
            </thead>
            <tbody>
              {result.rows.map((row) => (
                <tr key={row.line} className={row.action === 'error' ? 'error' : undefined}>
                  <td>{row.line}</td>
                  <td>{row.key}</td>
                  <td>{row.action}</td>
                  <td>{row.detail}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
    </main>
  );
}
