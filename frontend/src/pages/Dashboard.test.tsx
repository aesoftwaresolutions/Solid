import { render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { mockApi, money } from '../testSupport';
import DashboardPage from './DashboardPage';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const base = '/api/v1/orgs/o1/entities/e1';
const month = new Date().toISOString().slice(0, 7);

const taxLines = {
  taxYear: new Date().getFullYear(), from: '2026-01-01', to: '2026-12-31', lines: [], unmapped: [],
  totals: { income: money('2500.00'), costOfGoodsSold: money('0.00'), expenses: money('500.00'), netProfit: money('2000.00') },
  readiness: { draftEntries: 1, uncategorizedBankTransactions: 4, unmappedAccounts: 0, ready: false },
};

const aging = (total: string) => ({
  asOf: '2026-09-18', currency: 'USD', customers: [],
  totals: { customerId: 't', customerName: 'Total', current: money('0.00'), days1to30: money('0.00'), days31to60: money('0.00'), days61to90: money('0.00'), days90plus: money('0.00'), total: money(total) },
});

const coverage = {
  taxYear: new Date().getFullYear(),
  packs: [
    { id: 'standard-mileage-rates', title: 'Standard mileage rate', covered: false, reason: 'No figure on file for this year', source: 'IRS notices', todos: ['add it once announced'] },
    { id: 'home-office-simplified', title: 'Home office', covered: true, reason: 'Applies from 2013 until changed', source: 'Rev. Proc. 2013-13', todos: [] },
  ],
  covered: 1,
  missing: 1,
  note: 'Solid will not guess a missing figure.',
};

const budgetVsActual = {
  periodMonth: `${month}-01`, currency: 'USD', income: [], expenses: [],
  totals: {
    budgetedIncome: money('5000.00'), actualIncome: money('5200.00'),
    budgetedExpenses: money('800.00'), actualExpenses: money('912.34'),
    budgetedNet: money('4200.00'), actualNet: money('4287.66'),
  },
};

function renderDashboard() {
  return render(
    <MemoryRouter initialEntries={['/orgs/o1/entities/e1']}>
      <Routes>
        <Route path="/orgs/:orgId/entities/:entityId" element={<DashboardPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('spec 024: dashboard', () => {
  test('shows money owed both ways, the month against its budget and tax coverage', async () => {
    mockApi({
      [`GET ${base}/reports/tax-lines`]: taxLines,
      [`GET ${base}/reports/accounts-receivable-aging`]: aging('1250.00'),
      [`GET ${base}/reports/accounts-payable-aging`]: aging('430.00'),
      [`GET ${base}/reports/budget-vs-actual`]: budgetVsActual,
      [`GET ${base}/budgets/${month}`]: { id: 'b1', periodMonth: `${month}-01`, currency: 'USD', lines: [], budgetedIncome: money('5000.00'), budgetedExpenses: money('800.00'), budgetedNet: money('4200.00') },
      'GET /api/v1/tax/rule-coverage': coverage,
    });

    renderDashboard();

    expect(await screen.findByText('2,000.00')).toBeInTheDocument();
    expect(screen.getByText(/4 bank transaction\(s\) still need a category/)).toBeInTheDocument();

    const moneyCard = (await screen.findByText('Owed to you')).closest('section') as HTMLElement;
    expect(within(moneyCard).getByText('1,250.00')).toBeInTheDocument();
    expect(within(moneyCard).getByText('430.00')).toBeInTheDocument();
    expect(within(moneyCard).getByRole('link', { name: 'Sales' })).toBeInTheDocument();

    expect(await screen.findByText('4,287.66')).toBeInTheDocument();
    expect(screen.getByText('4,200.00')).toBeInTheDocument();

    expect(await screen.findByText(/1 rule pack\(s\) cover/)).toBeInTheDocument();
    expect(screen.getByText(/Standard mileage rate: No figure on file/)).toBeInTheDocument();
    expect(screen.getByText('Solid will not guess a missing figure.')).toBeInTheDocument();
  });

  test('offers to plan a budget when this month has none, and survives one failing card', async () => {
    mockApi(
      {
        [`GET ${base}/reports/tax-lines`]: taxLines,
        [`GET ${base}/reports/accounts-receivable-aging`]: { detail: 'Something broke', status: 500 },
        [`GET ${base}/reports/accounts-payable-aging`]: aging('430.00'),
        [`GET ${base}/reports/budget-vs-actual`]: budgetVsActual,
        [`GET ${base}/budgets/${month}`]: { detail: 'No budget for that month', status: 404 },
        'GET /api/v1/tax/rule-coverage': coverage,
      },
      {
        status: {
          [`GET ${base}/reports/accounts-receivable-aging`]: 500,
          [`GET ${base}/budgets/${month}`]: 404,
        },
      },
    );

    renderDashboard();

    expect(await screen.findByRole('link', { name: 'plan one' })).toBeInTheDocument();
    // The failing card shows its error, and the others still render.
    expect(await screen.findByRole('alert')).toHaveTextContent('Something broke');
    expect(screen.getByText('2,000.00')).toBeInTheDocument();
    expect(screen.getByText('430.00')).toBeInTheDocument();
  });
});
