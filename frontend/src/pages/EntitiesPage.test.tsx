import { render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { mockApi, money } from '../testSupport';
import EntitiesPage from './EntitiesPage';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const entities = [
  { id: 'e1', orgId: 'o1', kind: 'smllc', legalName: 'Zeta Shop LLC', fiscalYearEnd: 12, accountingMethod: 'cash', baseCurrency: 'USD' },
  { id: 'e2', orgId: 'o1', kind: 'individual', legalName: 'Alpha Household', fiscalYearEnd: 12, accountingMethod: 'cash', baseCurrency: 'USD' },
];

const line = (id: string, name: string, extra: Record<string, unknown> = {}) => ({
  entityId: id, legalName: name, kind: 'smllc', currency: 'USD', setUp: true,
  from: '2026-01-01', to: '2026-09-19',
  cash: money('2800.00'), netIncome: money('2800.00'), draftEntries: 0,
  uncategorizedBankTransactions: 0, needsAttention: false, ...extra,
});

function renderPage(overview: unknown) {
  mockApi({
    'GET /api/v1/orgs/o1/entities': entities,
    'GET /api/v1/orgs/o1/overview': overview,
  });
  return render(
    <MemoryRouter initialEntries={['/orgs/o1']}>
      <Routes>
        <Route path="/orgs/:orgId" element={<EntitiesPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('spec 041: the whole organization on one page', () => {
  test('shows each entity, what is waiting, and the total', async () => {
    renderPage({
      orgId: 'o1', from: '2026-01-01', to: '2026-09-19', mixedCurrencies: false,
      entities: [
        line('e2', 'Alpha Household', { from: '2026-07-01' }),
        line('e1', 'Zeta Shop LLC', { draftEntries: 1, uncategorizedBankTransactions: 3, needsAttention: true }),
      ],
      totals: { currency: 'USD', cash: money('5600.00'), netIncome: money('5600.00') },
      note: 'Totals are a plain sum of the entities, not a consolidation.',
    });

    expect(await screen.findByText('How the year is going')).toBeInTheDocument();
    const row = screen.getAllByText('Zeta Shop LLC')[1].closest('tr') as HTMLElement;
    expect(within(row).getByText(/1 draft entry\(s\), 3 bank transaction\(s\) to categorize/)).toBeInTheDocument();
    expect(screen.getByText('All entities (USD)')).toBeInTheDocument();
    expect(screen.getAllByText('5,600.00')).toHaveLength(2);
    expect(screen.getByText(/not a consolidation/)).toBeInTheDocument();
    // Spec 043: each line says which period it covers, because two entities can be on different fiscal years.
    expect(screen.getByText('2026-07-01 → 2026-09-19')).toBeInTheDocument();
  });

  test('spec 043: a failing overview says so instead of vanishing', async () => {
    mockApi(
      { 'GET /api/v1/orgs/o1/entities': entities, 'GET /api/v1/orgs/o1/overview': { detail: 'Overview broke' } },
      { status: { 'GET /api/v1/orgs/o1/overview': 500 } },
    );
    render(
      <MemoryRouter initialEntries={['/orgs/o1']}>
        <Routes>
          <Route path="/orgs/:orgId" element={<EntitiesPage />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByRole('alert')).toHaveTextContent('Overview broke');
    expect(screen.queryByText('How the year is going')).not.toBeInTheDocument();
  });

  test('two currencies are shown side by side with no total invented', async () => {
    renderPage({
      orgId: 'o1', from: '2026-01-01', to: '2026-09-19', mixedCurrencies: true,
      entities: [
        line('e2', 'Alpha Household', { currency: 'GBP', setUp: false, cash: money('0.00'), netIncome: money('0.00') }),
        line('e1', 'Zeta Shop LLC'),
      ],
      totals: null,
      note: 'Totals are a plain sum of the entities, not a consolidation.',
    });

    expect(await screen.findByText(/will not invent one/)).toBeInTheDocument();
    expect(screen.queryByText(/^All entities/)).not.toBeInTheDocument();
    expect(screen.getByText('not set up yet')).toBeInTheDocument();
  });
});
