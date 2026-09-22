import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { mockApi, money } from '../testSupport';
import DashboardPage from './DashboardPage';
import EntitySettingsPage from './EntitySettingsPage';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const base = '/api/v1/orgs/o1/entities/e1';
const year = new Date().getFullYear();
const emptySection = { rows: [], total: money('0.00') };
const emptyAging = {
  asOf: '2026-09-19', currency: 'USD', customers: [],
  totals: {
    customerId: '', customerName: 'Total', current: money('0.00'), days1to30: money('0.00'),
    days31to60: money('0.00'), days61to90: money('0.00'), days90plus: money('0.00'), total: money('0.00'),
  },
};

const dashboardRoutes = {
  [`GET ${base}/reports/tax-lines`]: {
    taxYear: year, from: `${year}-01-01`, to: `${year}-12-31`, lines: [], unmapped: [],
    totals: { income: money('0.00'), costOfGoodsSold: money('0.00'), expenses: money('0.00'), netProfit: money('0.00') },
    readiness: { draftEntries: 0, uncategorizedBankTransactions: 0, unmappedAccounts: 0, ready: true },
  },
  [`GET ${base}/reports/accounts-receivable-aging`]: emptyAging,
  [`GET ${base}/reports/accounts-payable-aging`]: { ...emptyAging, vendors: [] },
  'GET /api/v1/tax-rules/coverage': { taxYear: year, packs: [], covered: 0, missing: 0, note: '' },
  [`GET ${base}/reports/budget-vs-actual`]: undefined,
  [`GET ${base}/budgets/${year}-01`]: undefined,
  ...emptySection,
};

function renderDashboard(setup: unknown) {
  mockApi({ ...dashboardRoutes, [`GET ${base}/setup`]: setup });
  return render(
    <MemoryRouter initialEntries={['/orgs/o1/entities/e1']}>
      <Routes>
        <Route path="/orgs/:orgId/entities/:entityId" element={<DashboardPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('spec 044: the first ten minutes', () => {
  test('lists what is left, links each step, and marks the optional one', async () => {
    renderDashboard({
      complete: false,
      doneCount: 1,
      requiredCount: 5,
      steps: [
        { key: 'entity_details', title: 'Say where this entity is based', status: 'todo', detail: 'Nothing state-specific can work without it.', where: 'settings' },
        { key: 'chart_of_accounts', title: 'Set up the chart of accounts', status: 'done', detail: '42 account(s).', where: 'accounts' },
        { key: 'opening_balances', title: 'Enter opening balances', status: 'optional', detail: 'Only if this entity existed before.', where: 'accounts' },
      ],
    });

    expect(await screen.findByText(/Getting started · 1 of 5 done/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Say where this entity is based' }))
      .toHaveAttribute('href', '/orgs/o1/entities/e1/settings');
    expect(screen.getByText(/✓ Set up the chart of accounts/)).toBeInTheDocument();
    expect(screen.getByText(/only if it applies/)).toBeInTheDocument();
  });

  test('a finished setup stops nagging', async () => {
    renderDashboard({ complete: true, doneCount: 5, requiredCount: 5, steps: [] });

    // Wait for the page itself, then check the banner never appeared.
    expect(await screen.findByText(`Tax year ${year}`)).toBeInTheDocument();
    expect(screen.queryByText(/Getting started/)).not.toBeInTheDocument();
  });

  test('the settings page saves a home state and will not offer to change the entity kind', async () => {
    const { calls } = mockApi({
      [`GET ${base}/journal/verify`]: { valid: true, postedEntries: 12, firstInvalidSeq: null },
      [`GET ${base}`]: { id: 'e1', orgId: 'o1', kind: 'smllc', legalName: 'Zeta Shop LLC', fiscalYearEnd: 12, accountingMethod: 'cash', homeState: null, baseCurrency: 'USD' },
      [`PATCH ${base}`]: { id: 'e1', orgId: 'o1', kind: 'smllc', legalName: 'Zeta Shop LLC', fiscalYearEnd: 12, accountingMethod: 'cash', homeState: 'TX', baseCurrency: 'USD' },
    });
    render(
      <MemoryRouter initialEntries={['/orgs/o1/entities/e1/settings']}>
        <Routes>
          <Route path="/orgs/:orgId/entities/:entityId/settings" element={<EntitySettingsPage />} />
        </Routes>
      </MemoryRouter>,
    );

    fireEvent.change(await screen.findByLabelText('Home state'), { target: { value: 'TX' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    // Spec 045: the integrity check is on this page too.
    expect(await screen.findByText(/12 posted entries still hashes/)).toBeInTheDocument();
    expect((await screen.findAllByRole('status')).some((n) => n.textContent?.includes('Saved.'))).toBe(true);
    expect(calls.find((c) => c.key === `PATCH ${base}`)?.body).toMatchObject({ homeState: 'TX' });
    expect(screen.queryByLabelText(/Kind/)).not.toBeInTheDocument();
  });
});
