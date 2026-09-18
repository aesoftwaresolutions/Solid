import { useState, type ChangeEvent } from 'react';
import { useParams } from 'react-router-dom';
import { api, type StoredDocument } from '../api';
import { Card, ErrorMessage, Loading, useLoader } from '../components';

const KINDS = ['receipt', 'bank_statement', 'w2', 'form_1099', 'invoice', 'bill', 'contract', 'other'];
const OBJECT_TYPES = ['journal_entry', 'bank_transaction', 'invoice', 'bill', 'asset'];

function size(bytes: number): string {
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

export default function DocumentsPage() {
  const { orgId = '', entityId = '' } = useParams();
  const [kindFilter, setKindFilter] = useState('');
  const documents = useLoader(
    () => api.documents(orgId, entityId, kindFilter || undefined),
    [orgId, entityId, kindFilter],
  );

  const [uploadKind, setUploadKind] = useState('receipt');
  const [note, setNote] = useState('');
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);
  const [linking, setLinking] = useState<StoredDocument | null>(null);

  const upload = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) {
      return;
    }
    setBusy(true);
    setError(undefined);
    api
      .uploadDocument(orgId, entityId, file, uploadKind, note.trim() || undefined)
      .then(() => {
        setNote('');
        documents.reload();
      })
      .catch(setError)
      .finally(() => setBusy(false));
  };

  const remove = (document: StoredDocument) => {
    setBusy(true);
    setError(undefined);
    api
      .deleteDocument(orgId, entityId, document.id)
      .then(documents.reload)
      .catch(setError)
      .finally(() => setBusy(false));
  };

  const unlink = (document: StoredDocument, objectType: string, objectId: string) => {
    setBusy(true);
    setError(undefined);
    api
      .unlinkDocument(orgId, entityId, document.id, objectType, objectId)
      .then(documents.reload)
      .catch(setError)
      .finally(() => setBusy(false));
  };

  return (
    <main>
      <h1>Documents</h1>
      <ErrorMessage error={documents.error} />
      <ErrorMessage error={error} />

      <Card title="Upload">
        <p className="muted">
          Receipts, statements and tax paperwork, up to 25 MB each. Files are encrypted on this server with your
          instance key.
        </p>
        <label>
          Kind
          <select value={uploadKind} onChange={(e) => setUploadKind(e.target.value)}>
            {KINDS.map((kind) => (
              <option key={kind} value={kind}>
                {kind.replace(/_/g, ' ')}
              </option>
            ))}
          </select>
        </label>
        <label>
          Note
          <input value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} />
        </label>
        <label>
          File
          <input type="file" onChange={upload} disabled={busy} />
        </label>
      </Card>

      <Card title="Filed documents">
        <label>
          Show
          <select value={kindFilter} onChange={(e) => setKindFilter(e.target.value)}>
            <option value="">everything</option>
            {KINDS.map((kind) => (
              <option key={kind} value={kind}>
                {kind.replace(/_/g, ' ')}
              </option>
            ))}
          </select>
        </label>
        {!documents.value && !documents.error && <Loading what="documents" />}
        {documents.value && documents.value.length === 0 && <p className="muted">Nothing filed yet.</p>}
        {documents.value && documents.value.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>File</th>
                <th>Kind</th>
                <th>Size</th>
                <th>Attached to</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {documents.value.map((document) => (
                <tr key={document.id}>
                  <td>
                    <a href={api.documentContentUrl(orgId, entityId, document.id)}>{document.filename}</a>
                    {document.note && <div className="muted">{document.note}</div>}
                  </td>
                  <td>{document.kind.replace(/_/g, ' ')}</td>
                  <td>{size(document.sizeBytes)}</td>
                  <td>
                    {document.links.length === 0 && <span className="muted">nothing</span>}
                    {document.links.map((link) => (
                      <div key={`${link.objectType}-${link.objectId}`}>
                        {link.objectType.replace(/_/g, ' ')}{' '}
                        <button
                          type="button"
                          className="secondary"
                          disabled={busy}
                          onClick={() => unlink(document, link.objectType, link.objectId)}
                        >
                          Unlink
                        </button>
                      </div>
                    ))}
                  </td>
                  <td>
                    <button type="button" disabled={busy} onClick={() => setLinking(document)}>
                      Attach
                    </button>{' '}
                    <button type="button" className="secondary" disabled={busy} onClick={() => remove(document)}>
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      {linking && (
        <Card title={`Attach ${linking.filename}`}>
          <LinkForm
            orgId={orgId}
            entityId={entityId}
            document={linking}
            onDone={() => {
              setLinking(null);
              documents.reload();
            }}
          />
        </Card>
      )}
    </main>
  );
}

function LinkForm({
  orgId,
  entityId,
  document,
  onDone,
}: {
  orgId: string;
  entityId: string;
  document: StoredDocument;
  onDone: () => void;
}) {
  const [objectType, setObjectType] = useState(OBJECT_TYPES[0]);
  const [objectId, setObjectId] = useState('');
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        setBusy(true);
        setError(undefined);
        api
          .linkDocument(orgId, entityId, document.id, objectType, objectId.trim())
          .then(onDone)
          .catch(setError)
          .finally(() => setBusy(false));
      }}
    >
      <ErrorMessage error={error} />
      <label>
        Record type
        <select value={objectType} onChange={(e) => setObjectType(e.target.value)}>
          {OBJECT_TYPES.map((type) => (
            <option key={type} value={type}>
              {type.replace(/_/g, ' ')}
            </option>
          ))}
        </select>
      </label>
      <label>
        Record id
        <input value={objectId} required onChange={(e) => setObjectId(e.target.value)} />
      </label>
      <button type="submit" disabled={busy}>
        Attach
      </button>
    </form>
  );
}
