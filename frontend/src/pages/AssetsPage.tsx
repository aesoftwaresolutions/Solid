import { useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { api, formatMoney, type Account, type Asset } from '../api';
import { Card, ErrorMessage, Loading, useLoader } from '../components';

const today = () => new Date().toISOString().slice(0, 10);
const thisMonth = () => new Date().toISOString().slice(0, 7);

export default function AssetsPage() {
  const { orgId = '', entityId = '' } = useParams();
  const accounts = useLoader(() => api.accounts(orgId, entityId), [orgId, entityId]);
  const assets = useLoader(() => api.assets(orgId, entityId), [orgId, entityId]);
  const report = useLoader(() => api.fixedAssetReport(orgId, entityId, today()), [orgId, entityId]);

  const [runMonth, setRunMonth] = useState(thisMonth());
  const [runSummary, setRunSummary] = useState<string | null>(null);
  const [disposing, setDisposing] = useState<Asset | null>(null);
  const [showSchedule, setShowSchedule] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  const postable = useMemo(
    () => (accounts.value ?? []).filter((a: Account) => !a.isHeader && !a.isArchived),
    [accounts.value],
  );
  const assetAccounts = postable.filter((a) => a.type === 'asset');
  const expenseAccounts = postable.filter((a) => a.type === 'expense');

  return (
    <main>
      <h1>Fixed assets</h1>
      <ErrorMessage error={accounts.error} />
      <ErrorMessage error={assets.error} />
      <ErrorMessage error={error} />

      <Card title="Assets">
        {!assets.value && !assets.error && <Loading what="assets" />}
        {assets.value && assets.value.length === 0 && <p className="muted">Nothing capitalised yet.</p>}
        {assets.value && assets.value.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>In service</th>
                <th className="money">Cost</th>
                <th className="money">Depreciated</th>
                <th className="money">Book value</th>
                <th>Status</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {assets.value.map((asset) => (
                <tr key={asset.id}>
                  <td>{asset.name}</td>
                  <td>{asset.placedInServiceDate}</td>
                  <td className="money">{formatMoney(asset.cost)}</td>
                  <td className="money">{formatMoney(asset.accumulatedDepreciation)}</td>
                  <td className="money">{formatMoney(asset.netBookValue)}</td>
                  <td>{asset.status}</td>
                  <td>
                    <button
                      type="button"
                      className="secondary"
                      onClick={() => setShowSchedule(showSchedule === asset.id ? null : asset.id)}
                    >
                      Schedule
                    </button>{' '}
                    {asset.status !== 'disposed' && (
                      <button type="button" disabled={busy} onClick={() => setDisposing(asset)}>
                        Dispose
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {report.value && <p className="muted">{report.value.taxNote}</p>}
      </Card>

      {showSchedule && assets.value && (
        <Card title="Depreciation schedule">
          <table>
            <thead>
              <tr>
                <th>Month</th>
                <th className="money">Amount</th>
                <th>Posted</th>
              </tr>
            </thead>
            <tbody>
              {assets.value
                .find((asset) => asset.id === showSchedule)
                ?.monthlySchedule.map((month) => (
                  <tr key={month.month}>
                    <td>{month.month}</td>
                    <td className="money">{formatMoney(month.amount)}</td>
                    <td>{month.posted ? 'yes' : 'not yet'}</td>
                  </tr>
                ))}
            </tbody>
          </table>
        </Card>
      )}

      <Card title="Run depreciation">
        <p className="muted">
          This posts a journal entry for every month up to and including the one you choose. Months already posted
          are left alone.
        </p>
        <label>
          Through month
          <input type="month" value={runMonth} onChange={(e) => setRunMonth(e.target.value)} />
        </label>
        <button
          type="button"
          disabled={busy}
          onClick={() => {
            setBusy(true);
            setError(undefined);
            api
              .runDepreciation(orgId, entityId, runMonth)
              .then((run) => {
                setRunSummary(`Posted ${run.months.length} month(s), ${formatMoney(run.totalPosted)} in total.`);
                assets.reload();
                report.reload();
              })
              .catch(setError)
              .finally(() => setBusy(false));
          }}
        >
          Run now
        </button>
        {runSummary && <p className="notice">{runSummary}</p>}
      </Card>

      {disposing && (
        <Card title={`Dispose of ${disposing.name}`}>
          <DisposeForm
            orgId={orgId}
            entityId={entityId}
            asset={disposing}
            assetAccounts={assetAccounts}
            gainLossAccounts={[...expenseAccounts, ...postable.filter((a) => a.type === 'income')]}
            onDone={() => {
              setDisposing(null);
              assets.reload();
              report.reload();
            }}
          />
        </Card>
      )}

      <Card title="Add an asset">
        {assetAccounts.length < 2 || expenseAccounts.length === 0 ? (
          <p className="muted">
            You need an asset account, an accumulated-depreciation account and a depreciation expense account first.
          </p>
        ) : (
          <NewAsset
            orgId={orgId}
            entityId={entityId}
            assetAccounts={assetAccounts}
            expenseAccounts={expenseAccounts}
            onCreated={() => {
              assets.reload();
              report.reload();
            }}
          />
        )}
      </Card>
    </main>
  );
}

function NewAsset({
  orgId,
  entityId,
  assetAccounts,
  expenseAccounts,
  onCreated,
}: {
  orgId: string;
  entityId: string;
  assetAccounts: Account[];
  expenseAccounts: Account[];
  onCreated: () => void;
}) {
  const [name, setName] = useState('');
  const [placedInServiceDate, setPlacedInServiceDate] = useState(today());
  const [cost, setCost] = useState('');
  const [usefulLifeMonths, setUsefulLifeMonths] = useState('36');
  const [assetAccountId, setAssetAccountId] = useState(assetAccounts[0]?.id ?? '');
  const [accumulatedAccountId, setAccumulatedAccountId] = useState(assetAccounts[1]?.id ?? '');
  const [depreciationExpenseAccountId, setDepreciationExpenseAccountId] = useState(expenseAccounts[0]?.id ?? '');
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        setBusy(true);
        setError(undefined);
        api
          .createAsset(orgId, entityId, {
            name: name.trim(),
            placedInServiceDate,
            cost: { amount: cost.trim(), currency: 'USD' },
            usefulLifeMonths: Number(usefulLifeMonths),
            assetAccountId,
            accumulatedAccountId,
            depreciationExpenseAccountId,
          })
          .then(() => {
            setName('');
            setCost('');
            onCreated();
          })
          .catch(setError)
          .finally(() => setBusy(false));
      }}
    >
      <ErrorMessage error={error} />
      <label>
        Name
        <input value={name} required maxLength={200} onChange={(e) => setName(e.target.value)} />
      </label>
      <label>
        Placed in service
        <input type="date" value={placedInServiceDate} required onChange={(e) => setPlacedInServiceDate(e.target.value)} />
      </label>
      <label>
        Cost
        <input value={cost} required inputMode="decimal" placeholder="0.00" onChange={(e) => setCost(e.target.value)} />
      </label>
      <label>
        Useful life (months)
        <input value={usefulLifeMonths} required inputMode="numeric" onChange={(e) => setUsefulLifeMonths(e.target.value)} />
      </label>
      <label>
        Asset account
        <select value={assetAccountId} onChange={(e) => setAssetAccountId(e.target.value)}>
          {assetAccounts.map((account) => (
            <option key={account.id} value={account.id}>
              {account.code} {account.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        Accumulated depreciation account
        <select value={accumulatedAccountId} onChange={(e) => setAccumulatedAccountId(e.target.value)}>
          {assetAccounts.map((account) => (
            <option key={account.id} value={account.id}>
              {account.code} {account.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        Depreciation expense account
        <select
          value={depreciationExpenseAccountId}
          onChange={(e) => setDepreciationExpenseAccountId(e.target.value)}
        >
          {expenseAccounts.map((account) => (
            <option key={account.id} value={account.id}>
              {account.code} {account.name}
            </option>
          ))}
        </select>
      </label>
      <button type="submit" disabled={busy}>
        Add asset
      </button>
    </form>
  );
}

function DisposeForm({
  orgId,
  entityId,
  asset,
  assetAccounts,
  gainLossAccounts,
  onDone,
}: {
  orgId: string;
  entityId: string;
  asset: Asset;
  assetAccounts: Account[];
  gainLossAccounts: Account[];
  onDone: () => void;
}) {
  const [disposalDate, setDisposalDate] = useState(today());
  const [proceeds, setProceeds] = useState('');
  const [depositAccountId, setDepositAccountId] = useState(assetAccounts[0]?.id ?? '');
  const [gainLossAccountId, setGainLossAccountId] = useState(gainLossAccounts[0]?.id ?? '');
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        setBusy(true);
        setError(undefined);
        const amount = proceeds.trim();
        api
          .disposeAsset(orgId, entityId, asset.id, {
            disposalDate,
            proceeds: amount === '' ? undefined : { amount, currency: asset.cost.currency },
            depositAccountId: amount === '' ? undefined : depositAccountId,
            gainLossAccountId,
          })
          .then(onDone)
          .catch(setError)
          .finally(() => setBusy(false));
      }}
    >
      <ErrorMessage error={error} />
      <label>
        Disposal date
        <input type="date" value={disposalDate} required onChange={(e) => setDisposalDate(e.target.value)} />
      </label>
      <label>
        Proceeds
        <input value={proceeds} inputMode="decimal" placeholder="0.00" onChange={(e) => setProceeds(e.target.value)} />
      </label>
      <label>
        Deposit to
        <select value={depositAccountId} onChange={(e) => setDepositAccountId(e.target.value)}>
          {assetAccounts.map((account) => (
            <option key={account.id} value={account.id}>
              {account.code} {account.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        Gain or loss account
        <select value={gainLossAccountId} onChange={(e) => setGainLossAccountId(e.target.value)}>
          {gainLossAccounts.map((account) => (
            <option key={account.id} value={account.id}>
              {account.code} {account.name}
            </option>
          ))}
        </select>
      </label>
      <button type="submit" disabled={busy}>
        Record disposal
      </button>
    </form>
  );
}
