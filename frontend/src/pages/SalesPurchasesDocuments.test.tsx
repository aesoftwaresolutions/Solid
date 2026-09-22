import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { mockApi, money } from '../testSupport';
import DocumentsPage from './DocumentsPage';
import PurchasesPage from './PurchasesPage';
import SalesPage from './SalesPage';
import type { ReactElement } from 'react';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const accounts = [
  { id: 'a-4000', code: '4000', name: 'Sales', type: 'income', subtype: null, parentId: null, isHeader: false, taxLineCode: null, isArchived: false },
  { id: 'a-1010', code: '1010', name: 'Checking', type: 'asset', subtype: 'bank', parentId: null, isHeader: false, taxLineCode: null, isArchived: false },
  { id: 'a-6010', code: '6010', name: 'Advertising', type: 'expense', subtype: null, parentId: null, isHeader: false, taxLineCode: null, isArchived: false },
];

const emptyAging = {
  asOf: '2026-09-18',
  currency: 'USD',
  customers: [],
  totals: { customerId: 'total', customerName: 'Total', current: money('0.00'), days1to30: money('0.00'), days31to60: money('0.00'), days61to90: money('0.00'), days90plus: money('0.00'), total: money('0.00') },
};

function renderAt(path: string, pattern: string, element: ReactElement) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path={pattern} element={element} />
      </Routes>
    </MemoryRouter>,
  );
}

const base = '/api/v1/orgs/o1/entities/e1';

describe('spec 017 AC 1-3: sales', () => {
  const customer = { id: 'c1', name: 'Acme LLC', email: null, phone: null, isArchived: false };
  const draft = {
    id: 'i1', customerId: 'c1', invoiceNumber: null, issueDate: '2026-09-01', dueDate: '2026-10-01',
    terms: 'net_30', memo: null, total: money('1500.00'), amountPaid: money('0.00'),
    balanceDue: money('1500.00'), status: 'draft', lines: [],
  };

  test('creates a draft invoice, finalizes it and records a payment', async () => {
    const user = userEvent.setup();
    let status = 'draft';
    let invoices: unknown[] = [];
    const { calls } = mockApi({
      [`GET ${base}/accounts`]: accounts,
      [`GET ${base}/customers`]: [customer],
      [`GET ${base}/invoices`]: () => invoices,
      [`GET ${base}/reports/accounts-receivable-aging`]: emptyAging,
      [`GET ${base}/sales-tax-rates`]: [],
      [`GET ${base}/recurring-invoices`]: [],
      [`GET ${base}/quotes`]: [],
      [`GET ${base}/credit-notes`]: [],
      [`GET ${base}/reports/sales-tax`]: { from: '2026-01-01', to: '2026-09-19', currency: 'USD', jurisdictions: [], totalTaxable: money('0.00'), totalCollected: money('0.00'), note: 'note' },
      [`POST ${base}/invoices`]: () => {
        invoices = [draft];
        return draft;
      },
      [`POST ${base}/invoices/i1/finalize`]: () => {
        status = 'open';
        invoices = [{ ...draft, status, invoiceNumber: 'INV-1001' }];
        return invoices[0];
      },
      [`POST ${base}/payments`]: () => {
        invoices = [{ ...draft, status: 'paid', invoiceNumber: 'INV-1001', balanceDue: money('0.00') }];
        return { id: 'p1' };
      },
    });

    renderAt('/orgs/o1/entities/e1/sales', '/orgs/:orgId/entities/:entityId/sales', <SalesPage />);

    expect(await screen.findAllByText('Acme LLC')).not.toHaveLength(0);
    await user.type(await screen.findByLabelText('Description'), 'Website build');
    await user.type(screen.getByLabelText('Unit price'), '1500.00');
    await user.click(screen.getByRole('button', { name: 'Create draft invoice' }));

    await waitFor(() => expect(screen.getByRole('button', { name: 'Finalize' })).toBeInTheDocument());
    const created = calls.find((c) => c.key === `POST ${base}/invoices`)?.body as {
      lines: { unitPrice: { amount: string; currency: string }; quantity: string }[];
    };
    expect(created.lines[0].unitPrice).toEqual({ amount: '1500.00', currency: 'USD' });
    expect(created.lines[0].quantity).toBe('1');

    await user.click(screen.getByRole('button', { name: 'Finalize' }));
    await waitFor(() => expect(screen.getByText('INV-1001')).toBeInTheDocument());
    expect(status).toBe('open');

    await user.click(screen.getByRole('button', { name: 'Record payment' }));
    const amount = await screen.findByLabelText('Amount');
    expect(amount).toHaveValue('1500.00');
    await user.click(screen.getByRole('button', { name: 'Save payment' }));

    await waitFor(() => expect(screen.getByText('paid')).toBeInTheDocument());
    expect(calls.find((c) => c.key === `POST ${base}/payments`)?.body).toMatchObject({
      customerId: 'c1',
      depositAccountId: 'a-1010',
      applications: [{ invoiceId: 'i1', amount: { amount: '1500.00', currency: 'USD' } }],
    });
  });

  test('shows the server message when finalizing fails and leaves the button usable', async () => {
    const user = userEvent.setup();
    mockApi(
      {
        [`GET ${base}/accounts`]: accounts,
        [`GET ${base}/customers`]: [customer],
        [`GET ${base}/invoices`]: [draft],
        [`GET ${base}/reports/accounts-receivable-aging`]: emptyAging,
        [`GET ${base}/sales-tax-rates`]: [],
      [`GET ${base}/recurring-invoices`]: [],
      [`GET ${base}/quotes`]: [],
      [`GET ${base}/credit-notes`]: [],
        [`GET ${base}/reports/sales-tax`]: { from: '2026-01-01', to: '2026-09-19', currency: 'USD', jurisdictions: [], totalTaxable: money('0.00'), totalCollected: money('0.00'), note: 'note' },
        [`POST ${base}/invoices/i1/finalize`]: { detail: 'The period is locked', code: 'PERIOD_LOCKED', status: 409 },
      },
      { status: { [`POST ${base}/invoices/i1/finalize`]: 409 } },
    );

    renderAt('/orgs/o1/entities/e1/sales', '/orgs/:orgId/entities/:entityId/sales', <SalesPage />);
    await user.click(await screen.findByRole('button', { name: 'Finalize' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The period is locked');
    expect(alert).toHaveTextContent('PERIOD_LOCKED');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Finalize' })).toBeEnabled());
  });
});

describe('spec 017 AC 4-5: purchases', () => {
  const vendor = {
    id: 'v1', name: 'Hostinger', email: null, taxIdLast4: null, taxClassification: null,
    is1099Vendor: true, defaultExpenseAccountId: null, isArchived: false,
  };
  const bill = {
    id: 'b1', vendorId: 'v1', vendorReference: null, billDate: '2026-09-02', dueDate: '2026-10-02',
    terms: 'net_30', memo: null, total: money('120.00'), amountPaid: money('0.00'),
    balanceDue: money('120.00'), status: 'draft', lines: [],
  };
  const form1099 = {
    taxYear: 2025,
    thresholdKnown: false,
    threshold: null,
    thresholdSource: null,
    note: 'The 1099-NEC threshold for this year is not in a reviewed rule pack yet.',
    vendors: [{ vendorId: 'v1', vendorName: 'Hostinger', paidInYear: money('120.00'), meetsThreshold: false, missingInformation: ['taxId', 'address'] }],
  };

  test('creates a bill, approves it, pays it and shows 1099 gaps', async () => {
    const user = userEvent.setup();
    let bills: unknown[] = [];
    const { calls } = mockApi({
      [`GET ${base}/accounts`]: accounts,
      [`GET ${base}/vendors`]: [vendor],
      [`GET ${base}/bills`]: () => bills,
      [`GET ${base}/reports/accounts-payable-aging`]: emptyAging,
      [`GET ${base}/reports/form-1099-candidates`]: form1099,
      [`POST ${base}/bills`]: () => {
        bills = [bill];
        return bill;
      },
      [`POST ${base}/bills/b1/approve`]: () => {
        bills = [{ ...bill, status: 'open' }];
        return bills[0];
      },
      [`POST ${base}/bill-payments`]: () => {
        bills = [{ ...bill, status: 'paid', balanceDue: money('0.00') }];
        return { id: 'bp1' };
      },
    });

    renderAt('/orgs/o1/entities/e1/purchases', '/orgs/:orgId/entities/:entityId/purchases', <PurchasesPage />);

    expect(await screen.findByText(/The 1099-NEC threshold for this year is not in a reviewed rule pack/)).toBeInTheDocument();
    expect(screen.getByText('taxId, address')).toBeInTheDocument();

    await user.type(await screen.findByLabelText('Description'), 'VPS hosting');
    await user.type(screen.getByLabelText('Amount'), '120.00');
    await user.click(screen.getByRole('button', { name: 'Create draft bill' }));

    await waitFor(() => expect(screen.getByRole('button', { name: 'Approve' })).toBeInTheDocument());
    expect((calls.find((c) => c.key === `POST ${base}/bills`)?.body as { lines: { amount: unknown }[] }).lines[0].amount)
      .toEqual({ amount: '120.00', currency: 'USD' });

    await user.click(screen.getByRole('button', { name: 'Approve' }));
    await user.click(await screen.findByRole('button', { name: 'Pay' }));
    await user.click(await screen.findByRole('button', { name: 'Save payment' }));

    await waitFor(() => expect(screen.getByText('paid')).toBeInTheDocument());
    expect(calls.find((c) => c.key === `POST ${base}/bill-payments`)?.body).toMatchObject({
      vendorId: 'v1',
      paymentAccountId: 'a-1010',
      applications: [{ billId: 'b1', amount: { amount: '120.00', currency: 'USD' } }],
    });
  });
});

describe('spec 017 AC 6-7: documents', () => {
  const document = {
    id: 'd1', filename: 'receipt.png', contentType: 'image/png', sizeBytes: 2048,
    sha256: 'a'.repeat(64), kind: 'receipt', note: null, uploadedAt: '2026-09-18T10:00:00Z', links: [],
  };
  const linked = { ...document, links: [{ objectType: 'journal_entry', objectId: 'j1' }] };

  test('uploads, attaches to a record and refuses to delete while attached', async () => {
    const user = userEvent.setup();
    let documents: unknown[] = [];
    const { calls } = mockApi(
      {
        [`GET ${base}/documents`]: () => documents,
        [`POST ${base}/documents`]: () => {
          documents = [document];
          return document;
        },
        [`POST ${base}/documents/d1/links`]: () => {
          documents = [linked];
          return linked;
        },
        [`DELETE ${base}/documents/d1`]: {
          detail: 'Unlink this document from its 1 record(s) before deleting it',
          code: 'DOCUMENT_LINKED',
          status: 409,
        },
      },
      { status: { [`DELETE ${base}/documents/d1`]: 409 } },
    );

    renderAt('/orgs/o1/entities/e1/documents', '/orgs/:orgId/entities/:entityId/documents', <DocumentsPage />);

    expect(await screen.findByText('Nothing filed yet.')).toBeInTheDocument();
    const file = new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], 'receipt.png', { type: 'image/png' });
    await user.upload(screen.getByLabelText('File'), file);

    const link = await screen.findByRole('link', { name: 'receipt.png' });
    expect(link).toHaveAttribute('href', `${base}/documents/d1/content`);
    expect(calls.some((c) => c.key === `POST ${base}/documents`)).toBe(true);

    await user.click(screen.getByRole('button', { name: 'Attach' }));
    await user.type(await screen.findByLabelText('Record id'), 'j1');
    const attachButtons = screen.getAllByRole('button', { name: 'Attach' });
    await user.click(attachButtons[attachButtons.length - 1]);

    await waitFor(() => expect(screen.getByRole('button', { name: 'Unlink' })).toBeInTheDocument());
    expect(calls.find((c) => c.key === `POST ${base}/documents/d1/links`)?.body).toEqual({
      objectType: 'journal_entry',
      objectId: 'j1',
    });

    await user.click(screen.getByRole('button', { name: 'Delete' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('before deleting it');
    expect(alert).toHaveTextContent('DOCUMENT_LINKED');
  });

  test('filters by kind', async () => {
    const user = userEvent.setup();
    const { calls } = mockApi({ [`GET ${base}/documents`]: [document] });

    renderAt('/orgs/o1/entities/e1/documents', '/orgs/:orgId/entities/:entityId/documents', <DocumentsPage />);
    await screen.findByRole('link', { name: 'receipt.png' });
    await user.selectOptions(screen.getByLabelText('Show'), 'w2');

    await waitFor(() => expect(calls.filter((c) => c.key === `GET ${base}/documents`).length).toBeGreaterThan(1));
  });
});

describe('spec 037: sales tax on the sales page', () => {
  const customer = { id: 'c1', name: 'Acme LLC', email: null, phone: null, isArchived: false };
  const liability = {
    id: 'a-2200', code: '2200', name: 'Sales Tax Payable', type: 'liability', subtype: 'sales_tax',
    parentId: null, isHeader: false, taxLineCode: null, isArchived: false,
  };
  const rate = {
    id: 'r1', jurisdiction: 'Springfield, IL', ratePercent: '8.2500', liabilityAccountId: 'a-2200',
    effectiveFrom: '2026-01-01', effectiveTo: null, note: 'State DOR page, checked in January.', active: true,
  };

  test('adds a rate, charges it on a line and shows what has been collected', async () => {
    const user = userEvent.setup();
    let rates: unknown[] = [];
    const { calls } = mockApi({
      [`GET ${base}/accounts`]: [...accounts, liability],
      [`GET ${base}/customers`]: [customer],
      [`GET ${base}/invoices`]: [],
      [`GET ${base}/reports/accounts-receivable-aging`]: emptyAging,
      [`GET ${base}/sales-tax-rates`]: () => rates,
      [`GET ${base}/recurring-invoices`]: [],
      [`GET ${base}/quotes`]: [],
      [`GET ${base}/credit-notes`]: [],
      [`GET ${base}/reports/sales-tax`]: {
        from: '2026-01-01', to: '2026-09-19', currency: 'USD',
        jurisdictions: [{ jurisdiction: 'Springfield, IL', ratePercent: '8.2500', taxableSales: money('1000.00'), taxCollected: money('82.50') }],
        totalTaxable: money('1000.00'), totalCollected: money('82.50'),
        note: 'Tax collected is money you are holding for the state, not income.',
      },
      [`POST ${base}/sales-tax-rates`]: () => {
        rates = [rate];
        return rate;
      },
      [`POST ${base}/invoices`]: { ...{ id: 'i9', customerId: 'c1', invoiceNumber: null, issueDate: '2026-09-01', dueDate: '2026-10-01', terms: 'net_30', memo: null, total: money('1082.50'), amountPaid: money('0.00'), balanceDue: money('1082.50'), status: 'draft', lines: [] } },
    });

    renderAt('/orgs/o1/entities/e1/sales', '/orgs/:orgId/entities/:entityId/sales', <SalesPage />);

    expect(await screen.findByText(/Solid does not know your rates/)).toBeInTheDocument();
    expect(screen.getByText(/holding for the state/)).toBeInTheDocument();
    expect(screen.getByText('82.50')).toBeInTheDocument();

    await user.type(screen.getByLabelText('Jurisdiction'), 'Springfield, IL');
    await user.type(screen.getByLabelText('Rate percent'), '8.25');
    await user.type(screen.getByLabelText('Where this rate came from'), 'State DOR page');
    await user.click(screen.getByRole('button', { name: 'Add rate' }));

    expect(calls.find((c) => c.key === `POST ${base}/sales-tax-rates`)?.body).toMatchObject({
      jurisdiction: 'Springfield, IL',
      ratePercent: '8.25',
      liabilityAccountId: 'a-2200',
      note: 'State DOR page',
    });

    await waitFor(() => expect(screen.getByLabelText('Sales tax')).toBeInTheDocument());
    await user.selectOptions(screen.getByLabelText('Sales tax'), 'r1');
    await user.type(screen.getByLabelText('Description'), 'Consulting');
    await user.type(screen.getByLabelText('Unit price'), '1000.00');
    await user.click(screen.getByRole('button', { name: 'Create draft invoice' }));

    await waitFor(() => expect(calls.some((c) => c.key === `POST ${base}/invoices`)).toBe(true));
    const created = calls.find((c) => c.key === `POST ${base}/invoices`)?.body as { lines: { taxRateId?: string }[] };
    expect(created.lines[0].taxRateId).toBe('r1');
  });
});

describe('spec 038: editing a vendor', () => {
  const vendor = {
    id: 'v1', name: 'Contractor Co', email: null, taxIdLast4: null, taxClassification: null,
    is1099Vendor: true, defaultExpenseAccountId: null, isArchived: false,
  };
  const aging = emptyAging;
  const form1099 = {
    taxYear: 2025, thresholdKnown: true, threshold: money('2000.00'), thresholdSource: 'IRS instructions',
    note: 'Amounts are payments made during the year.',
    vendors: [{ vendorId: 'v1', vendorName: 'Contractor Co', paidInYear: money('3000.00'), meetsThreshold: true,
      missingInformation: ['Taxpayer ID (collect a W-9)'] }],
  };

  test('fills in the details the 1099 report asks for, sending only the last four digits', async () => {
    const user = userEvent.setup();
    const { calls } = mockApi({
      [`GET ${base}/accounts`]: accounts,
      [`GET ${base}/vendors`]: [vendor],
      [`GET ${base}/bills`]: [],
      [`GET ${base}/reports/accounts-payable-aging`]: aging,
      [`GET ${base}/reports/form-1099-candidates`]: form1099,
      [`PATCH ${base}/vendors/v1`]: { ...vendor, taxIdLast4: '6789', taxClassification: 'single_member_llc' },
    });

    renderAt('/orgs/o1/entities/e1/purchases', '/orgs/:orgId/entities/:entityId/purchases', <PurchasesPage />);

    expect(await screen.findByText('Taxpayer ID (collect a W-9)')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Edit' }));

    const taxId = await screen.findByLabelText('Taxpayer ID — last four digits only');
    expect(taxId).toHaveAttribute('maxLength', '4');
    await user.type(taxId, '6789');
    await user.selectOptions(screen.getByLabelText('Tax classification (from their W-9)'), 'single_member_llc');
    await user.click(screen.getByRole('button', { name: 'Save vendor' }));

    await waitFor(() => expect(calls.some((c) => c.key === `PATCH ${base}/vendors/v1`)).toBe(true));
    expect(calls.find((c) => c.key === `PATCH ${base}/vendors/v1`)?.body).toMatchObject({
      taxIdLast4: '6789',
      taxClassification: 'single_member_llc',
      is1099Vendor: true,
    });
  });
});

describe('spec 052: invoices that repeat', () => {
  const customer = { id: 'c1', name: 'Retainer Client', email: null, phone: null, isArchived: false };
  const template = {
    id: 'r1', entityId: 'e1', customerId: 'c1', customerName: 'Retainer Client', name: 'Monthly retainer',
    memo: null, terms: 'net_30', frequency: 'monthly', startDate: '2026-01-10', endDate: null,
    dayOfMonth: 10, active: true, total: money('1500.00'), lastCreated: '2026-02-10',
    lines: [{
      id: 'rl1', lineNo: 1, description: 'Retainer', quantity: '1', unitPrice: money('1500.00'),
      amount: money('1500.00'), incomeAccountId: 'a4', taxRateId: null,
    }],
  };

  function renderSales(routes: Record<string, unknown> = {}) {
    const mocked = mockApi({
      [`GET ${base}/accounts`]: accounts,
      [`GET ${base}/customers`]: [customer],
      [`GET ${base}/invoices`]: [],
      [`GET ${base}/reports/accounts-receivable-aging`]: emptyAging,
      [`GET ${base}/sales-tax-rates`]: [],
      [`GET ${base}/reports/sales-tax`]: {
        from: '2026-01-01', to: '2026-09-22', currency: 'USD', jurisdictions: [],
        totalTaxable: money('0.00'), totalCollected: money('0.00'), note: 'note',
      },
      [`GET ${base}/recurring-invoices`]: [template],
      [`GET ${base}/quotes`]: [],
      [`GET ${base}/credit-notes`]: [],
      ...routes,
    });
    render(
      <MemoryRouter initialEntries={['/orgs/o1/entities/e1/sales']}>
        <Routes>
          <Route path="/orgs/:orgId/entities/:entityId/sales" element={<SalesPage />} />
        </Routes>
      </MemoryRouter>,
    );
    return mocked;
  }

  test('lists the templates and says what a run created', async () => {
    const { calls } = renderSales({
      [`POST ${base}/recurring-invoices/run`]: {
        through: '2026-09-22', created: [{ recurringInvoiceId: 'r1', date: '2026-03-10', invoiceId: 'i9', invoiceNumber: 'INV-1009' }],
        skipped: [],
      },
    });

    expect(await screen.findByText('Invoices that repeat')).toBeInTheDocument();
    expect(screen.getByText('Monthly retainer')).toBeInTheDocument();
    expect(screen.getByText(/monthly, day 10/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Create what is due' }));

    expect(await screen.findByText(/Made 1 draft invoice/)).toBeInTheDocument();
    expect(calls.some((c) => c.key === `POST ${base}/recurring-invoices/run`)).toBe(true);
  });

  test('a skipped month says why', async () => {
    renderSales({
      [`POST ${base}/recurring-invoices/run`]: {
        through: '2026-09-22', created: [],
        skipped: [{ recurringInvoiceId: 'r1', date: '2026-03-10', reason: 'The customer Retainer Client is archived' }],
      },
    });

    fireEvent.click(await screen.findByRole('button', { name: 'Create what is due' }));

    expect(await screen.findByText(/skipped 1: The customer Retainer Client is archived/)).toBeInTheDocument();
  });

  test('stopping a template calls the server', async () => {
    const { calls } = renderSales({ [`POST ${base}/recurring-invoices/r1/deactivate`]: { ...template, active: false } });

    fireEvent.click(await screen.findByLabelText('Stop Monthly retainer'));

    expect(calls.some((c) => c.key === `POST ${base}/recurring-invoices/r1/deactivate`)).toBe(true);
  });
});

describe('spec 054: quotes', () => {
  const customer = { id: 'c1', name: 'Prospect Ltd', email: null, phone: null, isArchived: false };
  const quote = {
    id: 'q1', customerId: 'c1', customerName: 'Prospect Ltd', quoteNumber: 'Q-0001',
    issueDate: '2026-09-22', validUntil: '2026-10-22', memo: null, total: money('4000.00'),
    status: 'accepted', invoiceId: null, declinedReason: null, expired: false,
    lines: [{ id: 'ql1', lineNo: 1, description: 'Design and build', quantity: '1', unitPrice: money('4000.00'), amount: money('4000.00'), incomeAccountId: 'a-4000' }],
  };

  function renderSales(routes: Record<string, unknown> = {}) {
    const mocked = mockApi({
      [`GET ${base}/accounts`]: accounts,
      [`GET ${base}/customers`]: [customer],
      [`GET ${base}/invoices`]: [],
      [`GET ${base}/reports/accounts-receivable-aging`]: emptyAging,
      [`GET ${base}/sales-tax-rates`]: [],
      [`GET ${base}/reports/sales-tax`]: {
        from: '2026-01-01', to: '2026-09-22', currency: 'USD', jurisdictions: [],
        totalTaxable: money('0.00'), totalCollected: money('0.00'), note: 'note',
      },
      [`GET ${base}/recurring-invoices`]: [],
      [`GET ${base}/quotes`]: [quote],
      [`GET ${base}/credit-notes`]: [],
      ...routes,
    });
    render(
      <MemoryRouter initialEntries={['/orgs/o1/entities/e1/sales']}>
        <Routes>
          <Route path="/orgs/:orgId/entities/:entityId/sales" element={<SalesPage />} />
        </Routes>
      </MemoryRouter>,
    );
    return mocked;
  }

  test('lists a quote and turns an accepted one into an invoice', async () => {
    const { calls } = renderSales({
      [`POST ${base}/quotes/q1/convert`]: { id: 'i5', status: 'draft', total: money('4000.00') },
    });

    expect(await screen.findByText('Q-0001')).toBeInTheDocument();
    expect(screen.getByText(/posts nothing and is owed by nobody/)).toBeInTheDocument();

    fireEvent.click(screen.getByLabelText('Make an invoice from Q-0001'));

    await waitFor(() => expect(calls.some((c) => c.key === `POST ${base}/quotes/q1/convert`)).toBe(true));
  });

  test('creates a quote from the form', async () => {
    const { calls } = renderSales({ [`POST ${base}/quotes`]: { ...quote, id: 'q2', status: 'draft' } });

    fireEvent.change(await screen.findByLabelText('What the work is'), { target: { value: 'New website' } });
    fireEvent.change(screen.getByLabelText('Price'), { target: { value: '4000.00' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save the quote' }));

    await waitFor(() =>
      expect(calls.find((c) => c.key === `POST ${base}/quotes`)?.body).toMatchObject({
        customerId: 'c1',
        lines: [{ description: 'New website', quantity: '1' }],
      }),
    );
  });
});

describe('spec 057: credit notes', () => {
  const customer = { id: 'c1', name: 'Northwind Traders', email: null, phone: null, isArchived: false };
  const openInvoice = {
    id: 'i1', customerId: 'c1', invoiceNumber: 'INV-0001', issueDate: '2026-09-01', dueDate: '2026-10-01',
    terms: 'net_30', memo: null, total: money('1000.00'), amountPaid: money('0.00'),
    creditsApplied: money('0.00'), balanceDue: money('1000.00'), status: 'open', lines: [],
  };
  const credit = {
    id: 'cn1', customerId: 'c1', customerName: 'Northwind Traders', creditNumber: 'CN-0001',
    issueDate: '2026-09-22', memo: null, total: money('250.00'), status: 'issued',
    applied: money('0.00'), remaining: money('250.00'), lines: [], applications: [],
  };

  function renderSales(routes: Record<string, unknown> = {}, creditNotes: unknown[] = [credit]) {
    const mocked = mockApi({
      [`GET ${base}/accounts`]: accounts,
      [`GET ${base}/customers`]: [customer],
      [`GET ${base}/invoices`]: [openInvoice],
      [`GET ${base}/reports/accounts-receivable-aging`]: emptyAging,
      [`GET ${base}/sales-tax-rates`]: [],
      [`GET ${base}/reports/sales-tax`]: {
        from: '2026-01-01', to: '2026-09-22', currency: 'USD', jurisdictions: [],
        totalTaxable: money('0.00'), totalCollected: money('0.00'), note: 'note',
      },
      [`GET ${base}/recurring-invoices`]: [],
      [`GET ${base}/quotes`]: [],
      [`GET ${base}/credit-notes`]: creditNotes,
      ...routes,
    });
    render(
      <MemoryRouter initialEntries={['/orgs/o1/entities/e1/sales']}>
        <Routes>
          <Route path="/orgs/:orgId/entities/:entityId/sales" element={<SalesPage />} />
        </Routes>
      </MemoryRouter>,
    );
    return mocked;
  }

  test('an issued credit can be applied to that customer\'s open invoice', async () => {
    const { calls } = renderSales({
      [`POST ${base}/credit-notes/cn1/applications`]: { ...credit, applied: money('250.00'), remaining: money('0.00') },
    });

    expect(await screen.findByText('CN-0001')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }));

    await waitFor(() =>
      expect(calls.find((c) => c.key === `POST ${base}/credit-notes/cn1/applications`)?.body).toMatchObject({
        invoiceId: 'i1',
        amount: { amount: '250.00', currency: 'USD' },
      }),
    );
  });

  test('a draft is issued from the list, and the form drafts a new one', async () => {
    const draft = { ...credit, id: 'cn2', creditNumber: 'CN-0002', status: 'draft' };
    const { calls } = renderSales(
      {
        [`POST ${base}/credit-notes/cn2/issue`]: { ...draft, status: 'issued' },
        [`POST ${base}/credit-notes`]: { ...draft, id: 'cn3' },
      },
      [draft],
    );

    fireEvent.click(await screen.findByLabelText('Issue CN-0002'));
    await waitFor(() => expect(calls.some((c) => c.key === `POST ${base}/credit-notes/cn2/issue`)).toBe(true));

    fireEvent.change(screen.getByLabelText('What the credit is for'), { target: { value: 'Overcharged' } });
    fireEvent.change(screen.getByLabelText('Amount to credit'), { target: { value: '250.00' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save the credit note' }));

    await waitFor(() =>
      expect(calls.find((c) => c.key === `POST ${base}/credit-notes`)?.body).toMatchObject({
        customerId: 'c1',
        lines: [{ description: 'Overcharged', quantity: '1' }],
      }),
    );
  });
});
