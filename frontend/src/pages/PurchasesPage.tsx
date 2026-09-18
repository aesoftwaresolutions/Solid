import { useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { api, formatMoney, type Account, type Bill } from '../api';
import { Card, ErrorMessage, Loading, useLoader } from '../components';

const TERMS = ['due_on_receipt', 'net_15', 'net_30', 'net_60'];

const today = () => new Date().toISOString().slice(0, 10);

export default function PurchasesPage() {
  const { orgId = '', entityId = '' } = useParams();
  const taxYear = new Date().getFullYear() - 1;
  const accounts = useLoader(() => api.accounts(orgId, entityId), [orgId, entityId]);
  const vendors = useLoader(() => api.vendors(orgId, entityId), [orgId, entityId]);
  const bills = useLoader(() => api.bills(orgId, entityId), [orgId, entityId]);
  const aging = useLoader(() => api.apAging(orgId, entityId, today()), [orgId, entityId]);
  const form1099 = useLoader(() => api.form1099Candidates(orgId, entityId, taxYear), [orgId, entityId, taxYear]);

  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);
  const [payingBill, setPayingBill] = useState<Bill | null>(null);

  const postable = useMemo(
    () => (accounts.value ?? []).filter((a: Account) => !a.isHeader && !a.isArchived),
    [accounts.value],
  );
  const expenseAccounts = postable.filter((a) => a.type === 'expense');
  const paymentAccounts = postable.filter(
    (a) => (a.type === 'asset' && a.subtype === 'bank') || (a.type === 'liability' && a.subtype === 'credit_card'),
  );
  const vendorName = (id: string) => vendors.value?.find((v) => v.id === id)?.name ?? '—';

  const act = (work: Promise<unknown>) => {
    setBusy(true);
    setError(undefined);
    work
      .then(() => {
        bills.reload();
        aging.reload();
      })
      .catch(setError)
      .finally(() => setBusy(false));
  };

  return (
    <main>
      <h1>Purchases</h1>
      <ErrorMessage error={accounts.error} />
      <ErrorMessage error={vendors.error} />
      <ErrorMessage error={bills.error} />
      <ErrorMessage error={error} />

      <Card title="Vendors">
        <NewVendor orgId={orgId} entityId={entityId} onCreated={vendors.reload} />
        {!vendors.value && !vendors.error && <Loading what="vendors" />}
        <ul>
          {(vendors.value ?? []).map((vendor) => (
            <li key={vendor.id}>
              {vendor.name}
              {vendor.is1099Vendor ? ' · 1099' : ''}
            </li>
          ))}
        </ul>
      </Card>

      <Card title="New bill">
        {vendors.value && vendors.value.length === 0 && <p className="muted">Add a vendor first.</p>}
        {accounts.value && expenseAccounts.length === 0 && (
          <p className="muted">Add expense accounts to your chart of accounts first.</p>
        )}
        {vendors.value && vendors.value.length > 0 && expenseAccounts.length > 0 && (
          <NewBill
            orgId={orgId}
            entityId={entityId}
            vendors={vendors.value}
            expenseAccounts={expenseAccounts}
            onCreated={() => {
              bills.reload();
              aging.reload();
            }}
          />
        )}
      </Card>

      <Card title="Bills">
        {!bills.value && !bills.error && <Loading what="bills" />}
        {bills.value && bills.value.length === 0 && <p className="muted">No bills yet.</p>}
        {bills.value && bills.value.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Date</th>
                <th>Vendor</th>
                <th>Due</th>
                <th>Status</th>
                <th className="money">Total</th>
                <th className="money">Balance</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {bills.value.map((bill) => (
                <tr key={bill.id}>
                  <td>{bill.billDate}</td>
                  <td>{vendorName(bill.vendorId)}</td>
                  <td>{bill.dueDate}</td>
                  <td>{bill.status}</td>
                  <td className="money">{formatMoney(bill.total)}</td>
                  <td className="money">{formatMoney(bill.balanceDue)}</td>
                  <td>
                    {bill.status === 'draft' && (
                      <button type="button" disabled={busy} onClick={() => act(api.approveBill(orgId, entityId, bill.id))}>
                        Approve
                      </button>
                    )}{' '}
                    {bill.status === 'open' && (
                      <button type="button" disabled={busy} onClick={() => setPayingBill(bill)}>
                        Pay
                      </button>
                    )}{' '}
                    {bill.status !== 'void' && bill.status !== 'paid' && (
                      <button
                        type="button"
                        className="secondary"
                        disabled={busy}
                        onClick={() => act(api.voidBill(orgId, entityId, bill.id))}
                      >
                        Void
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      {payingBill && (
        <Card title={`Pay ${vendorName(payingBill.vendorId)}`}>
          <PayBill
            orgId={orgId}
            entityId={entityId}
            bill={payingBill}
            paymentAccounts={paymentAccounts}
            onDone={() => {
              setPayingBill(null);
              bills.reload();
              aging.reload();
            }}
          />
        </Card>
      )}

      <Card title="What you owe">
        <ErrorMessage error={aging.error} />
        {aging.value && (
          <table>
            <thead>
              <tr>
                <th>Vendor</th>
                <th className="money">Current</th>
                <th className="money">1–30</th>
                <th className="money">31–60</th>
                <th className="money">61–90</th>
                <th className="money">90+</th>
                <th className="money">Total</th>
              </tr>
            </thead>
            <tbody>
              {aging.value.customers.map((row) => (
                <tr key={row.customerId}>
                  <td>{row.customerName}</td>
                  <td className="money">{formatMoney(row.current)}</td>
                  <td className="money">{formatMoney(row.days1to30)}</td>
                  <td className="money">{formatMoney(row.days31to60)}</td>
                  <td className="money">{formatMoney(row.days61to90)}</td>
                  <td className="money">{formatMoney(row.days90plus)}</td>
                  <td className="money">{formatMoney(row.total)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <Card title={`1099-NEC candidates for ${taxYear}`}>
        <ErrorMessage error={form1099.error} />
        {form1099.value && (
          <>
            <p className="notice">{form1099.value.note}</p>
            {form1099.value.thresholdKnown && form1099.value.threshold ? (
              <p className="muted">
                Threshold {formatMoney(form1099.value.threshold)} · {form1099.value.thresholdSource}
              </p>
            ) : (
              <p className="muted">The filing threshold for this year is not in a reviewed rule pack yet.</p>
            )}
            <table>
              <thead>
                <tr>
                  <th>Vendor</th>
                  <th className="money">Paid</th>
                  <th>Meets threshold</th>
                  <th>Missing</th>
                </tr>
              </thead>
              <tbody>
                {form1099.value.vendors.map((vendor) => (
                  <tr key={vendor.vendorId}>
                    <td>{vendor.vendorName}</td>
                    <td className="money">{formatMoney(vendor.paidInYear)}</td>
                    <td>{vendor.meetsThreshold ? 'yes' : 'unknown'}</td>
                    <td>{vendor.missingInformation.join(', ') || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
      </Card>
    </main>
  );
}

function NewVendor({ orgId, entityId, onCreated }: { orgId: string; entityId: string; onCreated: () => void }) {
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [is1099Vendor, setIs1099Vendor] = useState(false);
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        setBusy(true);
        setError(undefined);
        api
          .createVendor(orgId, entityId, { name: name.trim(), email: email.trim() || undefined, is1099Vendor })
          .then(() => {
            setName('');
            setEmail('');
            setIs1099Vendor(false);
            onCreated();
          })
          .catch(setError)
          .finally(() => setBusy(false));
      }}
    >
      <ErrorMessage error={error} />
      <label>
        Vendor name
        <input value={name} required maxLength={200} onChange={(e) => setName(e.target.value)} />
      </label>
      <label>
        Email
        <input type="email" value={email} maxLength={254} onChange={(e) => setEmail(e.target.value)} />
      </label>
      <label>
        <input type="checkbox" checked={is1099Vendor} onChange={(e) => setIs1099Vendor(e.target.checked)} />
        Track for 1099
      </label>
      <button type="submit" disabled={busy}>
        Add vendor
      </button>
    </form>
  );
}

function NewBill({
  orgId,
  entityId,
  vendors,
  expenseAccounts,
  onCreated,
}: {
  orgId: string;
  entityId: string;
  vendors: { id: string; name: string; defaultExpenseAccountId: string | null }[];
  expenseAccounts: Account[];
  onCreated: () => void;
}) {
  const [vendorId, setVendorId] = useState(vendors[0].id);
  const [billDate, setBillDate] = useState(today());
  const [terms, setTerms] = useState('net_30');
  const [description, setDescription] = useState('');
  const [amount, setAmount] = useState('');
  const [expenseAccountId, setExpenseAccountId] = useState(
    vendors[0].defaultExpenseAccountId ?? expenseAccounts[0].id,
  );
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        setBusy(true);
        setError(undefined);
        api
          .createBill(orgId, entityId, {
            vendorId,
            billDate,
            terms,
            lines: [
              { description: description.trim(), amount: { amount, currency: 'USD' }, expenseAccountId },
            ],
          })
          .then(() => {
            setDescription('');
            setAmount('');
            onCreated();
          })
          .catch(setError)
          .finally(() => setBusy(false));
      }}
    >
      <ErrorMessage error={error} />
      <label>
        Vendor
        <select value={vendorId} onChange={(e) => setVendorId(e.target.value)}>
          {vendors.map((vendor) => (
            <option key={vendor.id} value={vendor.id}>
              {vendor.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        Bill date
        <input type="date" value={billDate} required onChange={(e) => setBillDate(e.target.value)} />
      </label>
      <label>
        Terms
        <select value={terms} onChange={(e) => setTerms(e.target.value)}>
          {TERMS.map((value) => (
            <option key={value} value={value}>
              {value.replace(/_/g, ' ')}
            </option>
          ))}
        </select>
      </label>
      <label>
        Description
        <input value={description} required maxLength={300} onChange={(e) => setDescription(e.target.value)} />
      </label>
      <label>
        Amount
        <input value={amount} required inputMode="decimal" placeholder="0.00" onChange={(e) => setAmount(e.target.value)} />
      </label>
      <label>
        Expense account
        <select value={expenseAccountId} onChange={(e) => setExpenseAccountId(e.target.value)}>
          {expenseAccounts.map((account) => (
            <option key={account.id} value={account.id}>
              {account.code} {account.name}
            </option>
          ))}
        </select>
      </label>
      <button type="submit" disabled={busy}>
        Create draft bill
      </button>
    </form>
  );
}

function PayBill({
  orgId,
  entityId,
  bill,
  paymentAccounts,
  onDone,
}: {
  orgId: string;
  entityId: string;
  bill: Bill;
  paymentAccounts: Account[];
  onDone: () => void;
}) {
  const [paidDate, setPaidDate] = useState(today());
  const [amount, setAmount] = useState(bill.balanceDue.amount);
  const [paymentAccountId, setPaymentAccountId] = useState(paymentAccounts[0]?.id ?? '');
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        setBusy(true);
        setError(undefined);
        api
          .payBills(orgId, entityId, {
            vendorId: bill.vendorId,
            paidDate,
            paymentAccountId,
            applications: [{ billId: bill.id, amount: { amount, currency: bill.total.currency } }],
          })
          .then(onDone)
          .catch(setError)
          .finally(() => setBusy(false));
      }}
    >
      <ErrorMessage error={error} />
      <label>
        Paid
        <input type="date" value={paidDate} required onChange={(e) => setPaidDate(e.target.value)} />
      </label>
      <label>
        Amount
        <input value={amount} required inputMode="decimal" onChange={(e) => setAmount(e.target.value)} />
      </label>
      <label>
        Paid from
        <select value={paymentAccountId} onChange={(e) => setPaymentAccountId(e.target.value)}>
          {paymentAccounts.map((account) => (
            <option key={account.id} value={account.id}>
              {account.code} {account.name}
            </option>
          ))}
        </select>
      </label>
      <button type="submit" disabled={busy}>
        Save payment
      </button>
    </form>
  );
}
