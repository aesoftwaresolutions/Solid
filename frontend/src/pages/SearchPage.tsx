import { useEffect, useState, type FormEvent } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { api, formatMoney, type SearchResults } from '../api';
import { Card, ErrorMessage } from '../components';

const KIND_LABELS: Record<string, string> = {
  account: 'Accounts',
  bank_transaction: 'Bank transactions',
  bill: 'Bills',
  customer: 'Customers',
  document: 'Documents',
  invoice: 'Invoices',
  journal_entry: 'Journal entries',
  vendor: 'Vendors',
};

export default function SearchPage() {
  const { orgId = '', entityId = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const queryFromUrl = params.get('q') ?? '';
  const [text, setText] = useState(queryFromUrl);
  const [results, setResults] = useState<SearchResults | undefined>(undefined);
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  // The query lives in the URL, so a search can be shared or reopened.
  useEffect(() => {
    if (queryFromUrl.trim().length < 2) {
      setResults(undefined);
      return;
    }
    let cancelled = false;
    setBusy(true);
    setError(undefined);
    api
      .search(orgId, entityId, queryFromUrl)
      .then((r) => !cancelled && setResults(r))
      .catch((e) => !cancelled && setError(e))
      .finally(() => !cancelled && setBusy(false));
    return () => {
      cancelled = true;
    };
  }, [orgId, entityId, queryFromUrl]);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    setParams(text.trim() ? { q: text.trim() } : {});
  };

  const found = results?.groups.reduce((sum, group) => sum + group.total, 0) ?? 0;

  return (
    <main>
      <h1>Find it</h1>
      <form onSubmit={submit}>
        <label>
          Search this entity
          <input
            value={text}
            placeholder="a name, a memo, an invoice number, or an amount"
            onChange={(e) => setText(e.target.value)}
          />
        </label>
        <button type="submit" disabled={busy}>
          Search
        </button>
      </form>
      <ErrorMessage error={error} />

      {results && results.amountInterpreted && (
        <p className="muted">
          Read as the amount {formatMoney(results.amountInterpreted)}, so records of exactly that size are
          included whichever way the money went.
        </p>
      )}
      {results && found === 0 && <p role="status">Nothing matched “{results.query}”.</p>}

      {results?.groups.map((group) => (
        <Card key={group.kind} title={`${KIND_LABELS[group.kind] ?? group.kind} (${group.total})`}>
          {group.total > group.hits.length && (
            <p className="muted">Showing the {group.hits.length} most recent of {group.total}.</p>
          )}
          <table>
            <thead>
              <tr>
                <th>Date</th>
                <th>What</th>
                <th>Detail</th>
                <th>Amount</th>
              </tr>
            </thead>
            <tbody>
              {group.hits.map((hit) => (
                <tr key={hit.id}>
                  <td>{hit.date ?? ''}</td>
                  <td>
                    <Link to={`/orgs/${orgId}/entities/${entityId}/${hit.where}`}>{hit.label}</Link>
                  </td>
                  <td>{hit.detail ?? ''}</td>
                  <td className="right">{hit.amount ? formatMoney(hit.amount) : ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      ))}
    </main>
  );
}
