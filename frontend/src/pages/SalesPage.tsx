import { useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  api,
  formatMoney,
  type Account,
  type Customer,
  type Invoice,
  type RecurringInvoiceRun,
  type SalesTaxRate,
} from '../api';
import { Card, ErrorMessage, Loading, useLoader } from '../components';

const TERMS = ['due_on_receipt', 'net_15', 'net_30', 'net_60'];

const today = () => new Date().toISOString().slice(0, 10);

export default function SalesPage() {
  const { orgId = '', entityId = '' } = useParams();
  const accounts = useLoader(() => api.accounts(orgId, entityId), [orgId, entityId]);
  const customers = useLoader(() => api.customers(orgId, entityId), [orgId, entityId]);
  const invoices = useLoader(() => api.invoices(orgId, entityId), [orgId, entityId]);
  const aging = useLoader(() => api.arAging(orgId, entityId, today()), [orgId, entityId]);
  const taxRates = useLoader(() => api.salesTaxRates(orgId, entityId), [orgId, entityId]);
  const taxReport = useLoader(
    () => api.salesTaxReport(orgId, entityId, `${new Date().getFullYear()}-01-01`, today()),
    [orgId, entityId],
  );

  const recurring = useLoader(() => api.recurringInvoices(orgId, entityId), [orgId, entityId]);

  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);
  const [paying, setPaying] = useState<Invoice | null>(null);
  const [runResult, setRunResult] = useState<RecurringInvoiceRun | null>(null);

  const postable = useMemo(
    () => (accounts.value ?? []).filter((a: Account) => !a.isHeader && !a.isArchived),
    [accounts.value],
  );
  const incomeAccounts = postable.filter((a) => a.type === 'income');
  const depositAccounts = postable.filter((a) => a.type === 'asset');
  const customerName = (id: string) => customers.value?.find((c) => c.id === id)?.name ?? '—';

  const act = (work: Promise<unknown>) => {
    setBusy(true);
    setError(undefined);
    work
      .then(() => {
        invoices.reload();
        aging.reload();
      })
      .catch(setError)
      .finally(() => setBusy(false));
  };

  return (
    <main>
      <h1>Sales</h1>
      <ErrorMessage error={accounts.error} />
      <ErrorMessage error={customers.error} />
      <ErrorMessage error={invoices.error} />
      <ErrorMessage error={error} />

      <Card title="Customers">
        <NewCustomer orgId={orgId} entityId={entityId} onCreated={customers.reload} />
        {!customers.value && !customers.error && <Loading what="customers" />}
        <ul>
          {(customers.value ?? []).map((customer) => (
            <li key={customer.id}>
              {customer.name}
              {customer.email ? ` · ${customer.email}` : ''}{' '}
              <a href={api.customerStatementPdfUrl(orgId, entityId, customer.id)} download>
                statement
              </a>
            </li>
          ))}
        </ul>
      </Card>

      <Card title="New invoice">
        {customers.value && customers.value.length === 0 && <p className="muted">Add a customer first.</p>}
        {accounts.value && incomeAccounts.length === 0 && (
          <p className="muted">Add income accounts to your chart of accounts first.</p>
        )}
        {customers.value && customers.value.length > 0 && incomeAccounts.length > 0 && (
          <NewInvoice
            orgId={orgId}
            entityId={entityId}
            customers={customers.value}
            incomeAccounts={incomeAccounts}
            taxRates={(taxRates.value ?? []).filter((rate) => rate.active)}
            onCreated={() => {
              invoices.reload();
              aging.reload();
            }}
          />
        )}
      </Card>

      <Card title="Invoices">
        {!invoices.value && !invoices.error && <Loading what="invoices" />}
        {invoices.value && invoices.value.length === 0 && <p className="muted">No invoices yet.</p>}
        {invoices.value && invoices.value.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Issued</th>
                <th>Number</th>
                <th>Customer</th>
                <th>Status</th>
                <th className="money">Total</th>
                <th className="money">Balance</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {invoices.value.map((invoice) => (
                <tr key={invoice.id}>
                  <td>{invoice.issueDate}</td>
                  <td>{invoice.invoiceNumber ?? 'draft'}</td>
                  <td>{customerName(invoice.customerId)}</td>
                  <td>{invoice.status}</td>
                  <td className="money">{formatMoney(invoice.total)}</td>
                  <td className="money">{formatMoney(invoice.balanceDue)}</td>
                  <td>
                    <a href={api.invoicePdfUrl(orgId, entityId, invoice.id)} download>
                      PDF
                    </a>{' '}
                    {invoice.status === 'draft' && (
                      <button
                        type="button"
                        disabled={busy}
                        onClick={() => act(api.finalizeInvoice(orgId, entityId, invoice.id))}
                      >
                        Finalize
                      </button>
                    )}{' '}
                    {invoice.status === 'open' && (
                      <button type="button" disabled={busy} onClick={() => setPaying(invoice)}>
                        Record payment
                      </button>
                    )}{' '}
                    {invoice.status !== 'void' && invoice.status !== 'paid' && (
                      <button
                        type="button"
                        className="secondary"
                        disabled={busy}
                        onClick={() => act(api.voidInvoice(orgId, entityId, invoice.id))}
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

      {paying && (
        <Card title={`Payment for ${paying.invoiceNumber ?? 'invoice'}`}>
          <RecordPayment
            orgId={orgId}
            entityId={entityId}
            invoice={paying}
            depositAccounts={depositAccounts}
            onDone={() => {
              setPaying(null);
              invoices.reload();
              aging.reload();
            }}
          />
        </Card>
      )}

      <Card title="Invoices that repeat">
        <ErrorMessage error={recurring.error} />
        <p className="muted">
          A retainer or a monthly service. Nothing goes out on a timer: press <em>Create what is due</em> and
          Solid makes a draft invoice for each period that has come round, once. You look at it, then issue it.
        </p>
        {customers.value && customers.value.length > 0 && incomeAccounts.length > 0 && (
          <NewRecurringInvoice
            orgId={orgId}
            entityId={entityId}
            customers={customers.value}
            incomeAccounts={incomeAccounts}
            onCreated={recurring.reload}
          />
        )}
        {recurring.value && recurring.value.length > 0 && (
          <>
            <table>
              <thead>
                <tr>
                  <th>What</th>
                  <th>Customer</th>
                  <th>Every</th>
                  <th>Amount</th>
                  <th>Last made</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {recurring.value.map((template) => (
                  <tr key={template.id}>
                    <td>{template.name}</td>
                    <td>{template.customerName}</td>
                    <td>
                      {template.frequency}, day {template.dayOfMonth}
                    </td>
                    <td className="right">{formatMoney(template.total)}</td>
                    <td className="muted">{template.lastCreated ?? 'never'}</td>
                    <td>
                      {template.active ? (
                        <button
                          type="button"
                          aria-label={`Stop ${template.name}`}
                          onClick={() => act(api.deactivateRecurringInvoice(orgId, entityId, template.id))}
                        >
                          Stop
                        </button>
                      ) : (
                        <span className="muted">stopped</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p>
              <button
                type="button"
                disabled={busy}
                onClick={() =>
                  api
                    .runRecurringInvoices(orgId, entityId, today())
                    .then((result) => {
                      setRunResult(result);
                      invoices.reload();
                      recurring.reload();
                    })
                    .catch(setError)
                }
              >
                Create what is due
              </button>
              {runResult && (
                <span role="status">
                  {' '}
                  Made {runResult.created.length} draft invoice(s)
                  {runResult.skipped.length > 0
                    ? `, skipped ${runResult.skipped.length}: ${runResult.skipped[0].reason}`
                    : ''}
                  .
                </span>
              )}
            </p>
          </>
        )}
      </Card>

      <Card title="Sales tax">
        <ErrorMessage error={taxRates.error} />
        <p className="muted">
          Solid does not know your rates — enter the ones you charge, with a note saying where you got them.
        </p>
        <NewTaxRate
          orgId={orgId}
          entityId={entityId}
          liabilityAccounts={postable.filter((a) => a.type === 'liability')}
          onCreated={taxRates.reload}
        />
        {taxRates.value && taxRates.value.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Jurisdiction</th>
                <th>Rate</th>
                <th>From</th>
                <th>State</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {taxRates.value.map((rate) => (
                <tr key={rate.id}>
                  <td>{rate.jurisdiction}</td>
                  <td>{rate.ratePercent}%</td>
                  <td>{rate.effectiveFrom}</td>
                  <td>{rate.active ? 'in use' : 'retired'}</td>
                  <td>
                    {rate.active && (
                      <button
                        type="button"
                        className="secondary"
                        disabled={busy}
                        onClick={() => act(api.deactivateSalesTaxRate(orgId, entityId, rate.id).then(taxRates.reload))}
                      >
                        Retire
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {taxReport.value && taxReport.value.jurisdictions.length > 0 && (
          <>
            <h3>Collected this year</h3>
            <table>
              <thead>
                <tr>
                  <th>Jurisdiction</th>
                  <th className="money">Taxable sales</th>
                  <th className="money">Tax collected</th>
                </tr>
              </thead>
              <tbody>
                {taxReport.value.jurisdictions.map((row) => (
                  <tr key={row.jurisdiction}>
                    <td>{row.jurisdiction}</td>
                    <td className="money">{formatMoney(row.taxableSales)}</td>
                    <td className="money">{formatMoney(row.taxCollected)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="muted">{taxReport.value.note}</p>
          </>
        )}
      </Card>

      <Card title="Who owes you">
        <ErrorMessage error={aging.error} />
        {!aging.value && !aging.error && <Loading what="aging" />}
        {aging.value && (
          <table>
            <thead>
              <tr>
                <th>Customer</th>
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
    </main>
  );
}

function NewCustomer({ orgId, entityId, onCreated }: { orgId: string; entityId: string; onCreated: () => void }) {
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        setBusy(true);
        setError(undefined);
        api
          .createCustomer(orgId, entityId, name.trim(), email.trim() || undefined)
          .then(() => {
            setName('');
            setEmail('');
            onCreated();
          })
          .catch(setError)
          .finally(() => setBusy(false));
      }}
    >
      <ErrorMessage error={error} />
      <label>
        Customer name
        <input value={name} required maxLength={200} onChange={(e) => setName(e.target.value)} />
      </label>
      <label>
        Email
        <input type="email" value={email} maxLength={254} onChange={(e) => setEmail(e.target.value)} />
      </label>
      <button type="submit" disabled={busy}>
        Add customer
      </button>
    </form>
  );
}

function NewInvoice({
  orgId,
  entityId,
  customers,
  incomeAccounts,
  taxRates,
  onCreated,
}: {
  orgId: string;
  entityId: string;
  customers: { id: string; name: string }[];
  incomeAccounts: Account[];
  taxRates: SalesTaxRate[];
  onCreated: () => void;
}) {
  const [customerId, setCustomerId] = useState(customers[0].id);
  const [issueDate, setIssueDate] = useState(today());
  const [terms, setTerms] = useState('net_30');
  const [description, setDescription] = useState('');
  const [quantity, setQuantity] = useState('1');
  const [unitPrice, setUnitPrice] = useState('');
  const [incomeAccountId, setIncomeAccountId] = useState(incomeAccounts[0].id);
  const [taxRateId, setTaxRateId] = useState('');
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        setBusy(true);
        setError(undefined);
        api
          .createInvoice(orgId, entityId, {
            customerId,
            issueDate,
            terms,
            lines: [
              {
                description: description.trim(),
                quantity,
                unitPrice: { amount: unitPrice, currency: 'USD' },
                incomeAccountId,
                taxRateId: taxRateId || undefined,
              },
            ],
          })
          .then(() => {
            setDescription('');
            setUnitPrice('');
            onCreated();
          })
          .catch(setError)
          .finally(() => setBusy(false));
      }}
    >
      <ErrorMessage error={error} />
      <label>
        Customer
        <select value={customerId} onChange={(e) => setCustomerId(e.target.value)}>
          {customers.map((customer) => (
            <option key={customer.id} value={customer.id}>
              {customer.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        Issue date
        <input type="date" value={issueDate} required onChange={(e) => setIssueDate(e.target.value)} />
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
        Quantity
        <input value={quantity} required inputMode="decimal" onChange={(e) => setQuantity(e.target.value)} />
      </label>
      <label>
        Unit price
        <input
          value={unitPrice}
          required
          inputMode="decimal"
          placeholder="0.00"
          onChange={(e) => setUnitPrice(e.target.value)}
        />
      </label>
      <label>
        Income account
        <select value={incomeAccountId} onChange={(e) => setIncomeAccountId(e.target.value)}>
          {incomeAccounts.map((account) => (
            <option key={account.id} value={account.id}>
              {account.code} {account.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        Sales tax
        <select value={taxRateId} onChange={(e) => setTaxRateId(e.target.value)}>
          <option value="">none</option>
          {taxRates.map((rate) => (
            <option key={rate.id} value={rate.id}>
              {rate.jurisdiction} {rate.ratePercent}%
            </option>
          ))}
        </select>
      </label>
      <button type="submit" disabled={busy}>
        Create draft invoice
      </button>
    </form>
  );
}

function RecordPayment({
  orgId,
  entityId,
  invoice,
  depositAccounts,
  onDone,
}: {
  orgId: string;
  entityId: string;
  invoice: Invoice;
  depositAccounts: Account[];
  onDone: () => void;
}) {
  const [receivedDate, setReceivedDate] = useState(today());
  const [amount, setAmount] = useState(invoice.balanceDue.amount);
  const [depositAccountId, setDepositAccountId] = useState(depositAccounts[0]?.id ?? '');
  const [method, setMethod] = useState('');
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        setBusy(true);
        setError(undefined);
        api
          .recordPayment(orgId, entityId, {
            customerId: invoice.customerId,
            receivedDate,
            depositAccountId,
            method: method || undefined,
            applications: [{ invoiceId: invoice.id, amount: { amount, currency: invoice.total.currency } }],
          })
          .then(onDone)
          .catch(setError)
          .finally(() => setBusy(false));
      }}
    >
      <ErrorMessage error={error} />
      <label>
        Received
        <input type="date" value={receivedDate} required onChange={(e) => setReceivedDate(e.target.value)} />
      </label>
      <label>
        Amount
        <input value={amount} required inputMode="decimal" onChange={(e) => setAmount(e.target.value)} />
      </label>
      <label>
        Deposit to
        <select value={depositAccountId} onChange={(e) => setDepositAccountId(e.target.value)}>
          {depositAccounts.map((account) => (
            <option key={account.id} value={account.id}>
              {account.code} {account.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        Method
        <input value={method} maxLength={40} onChange={(e) => setMethod(e.target.value)} />
      </label>
      <button type="submit" disabled={busy}>
        Save payment
      </button>
    </form>
  );
}

function NewTaxRate({
  orgId,
  entityId,
  liabilityAccounts,
  onCreated,
}: {
  orgId: string;
  entityId: string;
  liabilityAccounts: Account[];
  onCreated: () => void;
}) {
  const [jurisdiction, setJurisdiction] = useState('');
  const [ratePercent, setRatePercent] = useState('');
  const [chosenAccountId, setChosenAccountId] = useState('');
  // The accounts arrive after this form first renders, so fall back to the first one rather than sending an
  // empty account id.
  const liabilityAccountId = chosenAccountId || liabilityAccounts[0]?.id || '';
  const [effectiveFrom, setEffectiveFrom] = useState(`${new Date().getFullYear()}-01-01`);
  const [note, setNote] = useState('');
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  if (liabilityAccounts.length === 0) {
    return <p className="muted">Add a liability account (for example "Sales Tax Payable") first.</p>;
  }

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        setBusy(true);
        setError(undefined);
        api
          .createSalesTaxRate(orgId, entityId, {
            jurisdiction: jurisdiction.trim(),
            ratePercent: ratePercent.trim(),
            liabilityAccountId,
            effectiveFrom,
            note: note.trim() || undefined,
          })
          .then(() => {
            setJurisdiction('');
            setRatePercent('');
            setNote('');
            onCreated();
          })
          .catch(setError)
          .finally(() => setBusy(false));
      }}
    >
      <ErrorMessage error={error} />
      <label>
        Jurisdiction
        <input value={jurisdiction} required maxLength={120} onChange={(e) => setJurisdiction(e.target.value)} />
      </label>
      <label>
        Rate percent
        <input value={ratePercent} required inputMode="decimal" placeholder="8.25" onChange={(e) => setRatePercent(e.target.value)} />
      </label>
      <label>
        Owed to
        <select value={liabilityAccountId} onChange={(e) => setChosenAccountId(e.target.value)}>
          {liabilityAccounts.map((account) => (
            <option key={account.id} value={account.id}>
              {account.code} {account.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        Effective from
        <input type="date" value={effectiveFrom} required onChange={(e) => setEffectiveFrom(e.target.value)} />
      </label>
      <label>
        Where this rate came from
        <input value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} />
      </label>
      <button type="submit" disabled={busy}>
        Add rate
      </button>
    </form>
  );
}

/** A template for an invoice that repeats: one line is enough to be useful, so that is what this asks for. */
function NewRecurringInvoice({
  orgId,
  entityId,
  customers,
  incomeAccounts,
  onCreated,
}: {
  orgId: string;
  entityId: string;
  customers: Customer[];
  incomeAccounts: Account[];
  onCreated: () => void;
}) {
  const [customerId, setCustomerId] = useState('');
  const [name, setName] = useState('');
  const [frequency, setFrequency] = useState('monthly');
  const [startDate, setStartDate] = useState(today());
  const [description, setDescription] = useState('');
  const [amount, setAmount] = useState('');
  const [incomeAccountId, setIncomeAccountId] = useState('');
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  // Derived rather than initialised from a list that may still have been empty on the first render.
  const chosenCustomer = customerId || customers[0]?.id || '';
  const chosenAccount = incomeAccountId || incomeAccounts[0]?.id || '';

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        setBusy(true);
        setError(undefined);
        api
          .createRecurringInvoice(orgId, entityId, {
            customerId: chosenCustomer,
            name: name.trim(),
            terms: 'net_30',
            frequency,
            startDate,
            lines: [
              {
                description: description.trim(),
                quantity: '1',
                unitPrice: { amount: amount.trim(), currency: 'USD' },
                incomeAccountId: chosenAccount,
              },
            ],
          })
          .then(() => {
            setName('');
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
        What to call it
        <input value={name} required maxLength={120} onChange={(e) => setName(e.target.value)} />
      </label>
      <label>
        Customer for the repeat
        <select value={chosenCustomer} onChange={(e) => setCustomerId(e.target.value)}>
          {customers.map((customer) => (
            <option key={customer.id} value={customer.id}>
              {customer.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        How often
        <select value={frequency} onChange={(e) => setFrequency(e.target.value)}>
          <option value="monthly">monthly</option>
          <option value="quarterly">quarterly</option>
          <option value="annual">annual</option>
        </select>
      </label>
      <label>
        First one on
        <input type="date" value={startDate} required onChange={(e) => setStartDate(e.target.value)} />
      </label>
      <label>
        Line description
        <input value={description} required maxLength={300} onChange={(e) => setDescription(e.target.value)} />
      </label>
      <label>
        Amount each time
        <input value={amount} required inputMode="decimal" onChange={(e) => setAmount(e.target.value)} />
      </label>
      <label>
        Income account for the repeat
        <select value={chosenAccount} onChange={(e) => setIncomeAccountId(e.target.value)}>
          {incomeAccounts.map((account) => (
            <option key={account.id} value={account.id}>
              {account.code} {account.name}
            </option>
          ))}
        </select>
      </label>
      <button type="submit" disabled={busy}>
        Save the template
      </button>
    </form>
  );
}
