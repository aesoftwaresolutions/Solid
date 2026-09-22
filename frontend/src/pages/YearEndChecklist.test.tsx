import { render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { mockApi, money } from '../testSupport';
import ReportsPage from './ReportsPage';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const base = '/api/v1/orgs/o1/entities/e1';
const year = new Date().getFullYear();

const emptySection = { rows: [], total: money('0.00') };

const common = {
  [`GET ${base}/reports/profit-and-loss`]: {
    from: `${year}-01-01`, to: `${year}-12-31`, currency: 'USD', income: emptySection,
    costOfGoodsSold: emptySection, grossProfit: money('0.00'), expenses: emptySection, netIncome: money('0.00'),
  },
  [`GET ${base}/reports/balance-sheet`]: {
    asOf: `${year}-12-31`, currency: 'USD', assets: emptySection, liabilities: emptySection, equity: emptySection,
    totalLiabilitiesAndEquity: money('0.00'), balanced: true,
  },
  [`GET ${base}/reports/trial-balance`]: {
    asOf: `${year}-12-31`, currency: 'USD', rows: [], totalDebit: money('0.00'), totalCredit: money('0.00'),
  },
  [`GET ${base}/reports/cash-flow`]: {
    from: `${year}-01-01`, to: `${year}-12-31`, currency: 'USD', openingCash: money('100.00'),
    operating: { rows: [], total: money('50.00') }, investing: { rows: [], total: money('0.00') },
    financing: { rows: [], total: money('0.00') }, unclassified: { rows: [], total: money('0.00') },
    netChange: money('50.00'), closingCash: money('150.00'),
    note: 'Prepared by the direct method from the posted ledger.',
  },
  [`GET ${base}/reports/whats-coming`]: {
    from: `${year}-01-01`, to: `${year}-03-31`, currency: 'USD', openingCash: money('0.00'),
    items: [], totals: { in: money('0.00'), out: money('0.00'), net: money('0.00'), projectedClosing: money('0.00') },
    lowestPoint: null, note: 'This lists what is already scheduled. It is not a forecast.',
  },
  [`GET ${base}/reports/tax-lines`]: {
    taxYear: year, from: `${year}-01-01`, to: `${year}-12-31`, lines: [], unmapped: [],
    totals: { income: money('0.00'), costOfGoodsSold: money('0.00'), expenses: money('0.00'), netProfit: money('0.00') },
    readiness: { draftEntries: 0, uncategorizedBankTransactions: 0, unmappedAccounts: 0, ready: true },
  },
};

const checklist = (ready: boolean) => ({
  taxYear: year, from: `${year}-01-01`, to: `${year}-12-31`, ready,
  items: [
    { key: 'bank_categorized', title: 'Bank activity categorized', status: ready ? 'done' : 'todo',
      detail: ready ? 'Nothing is waiting in the review queue.' : '3 imported transaction(s) still need a category.',
      count: ready ? 0 : 3, where: 'bank' },
    { key: 'books_closed', title: 'Books closed', status: ready ? 'done' : 'todo',
      detail: ready ? `The books are locked through ${year}-12-31.` : `Lock the period through ${year}-12-31 once everything else is done.`,
      count: null, where: 'journal' },
    { key: 'tax_figures', title: 'Tax figures on file', status: 'unknown',
      detail: '1 rule pack(s) have no figure for this year. That does not stop you closing the books.',
      count: 1, where: 'instance' },
  ],
});

function renderReports() {
  return render(
    <MemoryRouter initialEntries={['/orgs/o1/entities/e1/reports']}>
      <Routes>
        <Route path="/orgs/:orgId/entities/:entityId/reports" element={<ReportsPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('spec 036: year-end checklist on the reports page', () => {
  test('lists what is left, with a link to the screen that fixes it', async () => {
    mockApi({ ...common, [`GET ${base}/reports/year-end-checklist`]: checklist(false) });

    renderReports();

    expect(await screen.findByText(`Is ${year} finished?`)).toBeInTheDocument();
    expect(screen.getByText('Still to do before this year can be handed over:')).toBeInTheDocument();

    const row = screen.getByText('Bank activity categorized').closest('tr') as HTMLElement;
    expect(within(row).getByText('todo')).toBeInTheDocument();
    expect(within(row).getByText(/3 imported transaction\(s\)/)).toBeInTheDocument();
    expect(within(row).getByRole('link', { name: 'Bank activity categorized' }))
      .toHaveAttribute('href', '/orgs/o1/entities/e1/bank');

    // A missing tax figure is reported as unknown, not as the person's unfinished work.
    const figures = screen.getByText('Tax figures on file').closest('tr') as HTMLElement;
    expect(within(figures).getByText('unknown')).toBeInTheDocument();
    expect(within(figures).getByText(/does not stop you closing the books/)).toBeInTheDocument();
  });

  test('when ready it says so without claiming the return is right', async () => {
    mockApi({ ...common, [`GET ${base}/reports/year-end-checklist`]: checklist(true) });

    renderReports();

    expect(await screen.findByText(/The bookkeeping steps for this year are done/)).toBeInTheDocument();
    expect(screen.getByText(/your preparer decides that/)).toBeInTheDocument();
  });
});

describe('spec 053: what is coming', () => {
  test('shows the lowest point and says it is not a forecast', async () => {
    mockApi({
      ...common,
      [`GET ${base}/reports/year-end-checklist`]: checklist(true),
      [`GET ${base}/reports/whats-coming`]: {
        from: '2026-09-22', to: '2026-12-21', currency: 'USD', openingCash: money('5000.00'),
        items: [
          {
            date: '2026-10-07', kind: 'bill_due', description: 'Bill P-100', reference: 'P-100',
            amountIn: money('0.00'), amountOut: money('4500.00'), projectedBalance: money('500.00'),
          },
          {
            date: '2026-11-21', kind: 'invoice_due', description: 'Invoice INV-1001', reference: 'INV-1001',
            amountIn: money('3000.00'), amountOut: money('0.00'), projectedBalance: money('3500.00'),
          },
        ],
        totals: { in: money('3000.00'), out: money('4500.00'), net: money('-1500.00'), projectedClosing: money('3500.00') },
        lowestPoint: {
          date: '2026-10-07', kind: 'bill_due', description: 'Bill P-100', reference: 'P-100',
          amountIn: money('0.00'), amountOut: money('4500.00'), projectedBalance: money('500.00'),
        },
        note: 'This lists what is already scheduled: invoices you have issued... It is not a forecast.',
      },
    });

    renderReports();

    expect(await screen.findByText('What is coming (next 90 days)')).toBeInTheDocument();
    expect(screen.getByText(/not a forecast/)).toBeInTheDocument();
    expect(screen.getByText('Lowest point')).toBeInTheDocument();
    expect(screen.getByText('on 2026-10-07')).toBeInTheDocument();
    const row = screen.getByText('Bill P-100').closest('tr') as HTMLElement;
    expect(within(row).getByText('4,500.00')).toBeInTheDocument();
    expect(within(row).getByText('500.00')).toBeInTheDocument();
  });
});
