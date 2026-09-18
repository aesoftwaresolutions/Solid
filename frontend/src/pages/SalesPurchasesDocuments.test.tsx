import { render, screen, waitFor } from '@testing-library/react';
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
