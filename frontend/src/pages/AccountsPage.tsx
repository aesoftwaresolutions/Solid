import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { api } from '../api';
import { Card, ErrorMessage, Loading, useLoader } from '../components';

export default function AccountsPage() {
  const { orgId = '', entityId = '' } = useParams();
  const { value: accounts, error, reload } = useLoader(() => api.accounts(orgId, entityId), [orgId, entityId]);
  const [actionError, setActionError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  const applyTemplate = () => {
    setBusy(true);
    setActionError(undefined);
    api
      .applyTemplate(orgId, entityId, 'schedule_c')
      .then(() => reload())
      .catch(setActionError)
      .finally(() => setBusy(false));
  };

  return (
    <main>
      <h1>Chart of accounts</h1>
      <ErrorMessage error={error} />
      <ErrorMessage error={actionError} />
      {!accounts && !error && <Loading what="accounts" />}

      {accounts && (
        <Card
          title={`${accounts.length} account(s)`}
          actions={
            accounts.length === 0 ? (
              <button type="button" onClick={applyTemplate} disabled={busy}>
                Use the Schedule C template
              </button>
            ) : undefined
          }
        >
          {accounts.length === 0 ? (
            <p className="muted">
              No accounts yet. The Schedule C template creates a ready-made chart for a sole proprietor, with each
              expense already mapped to its tax line.
            </p>
          ) : (
            <table>
              <thead>
                <tr>
                  <th>Code</th>
                  <th>Name</th>
                  <th>Type</th>
                  <th>Tax line</th>
                </tr>
              </thead>
              <tbody>
                {accounts.map((account) => (
                  <tr key={account.id}>
                    <td>{account.code}</td>
                    <td style={{ paddingLeft: account.parentId ? 24 : 8 }}>
                      {account.isHeader ? <strong>{account.name}</strong> : account.name}
                    </td>
                    <td>{account.type}</td>
                    <td className="muted">{account.taxLineCode ?? (account.isHeader ? '' : '—')}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
      )}
    </main>
  );
}
