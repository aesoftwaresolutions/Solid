import { render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { mockApi, money } from '../testSupport';
import SearchPage from './SearchPage';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

function renderSearch(results: unknown, query = 'printer') {
  mockApi({ 'GET /api/v1/orgs/o1/entities/e1/search': results });
  return render(
    <MemoryRouter initialEntries={[`/orgs/o1/entities/e1/search?q=${query}`]}>
      <Routes>
        <Route path="/orgs/:orgId/entities/:entityId/search" element={<SearchPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('spec 042: finding that one transaction', () => {
  test('groups the hits, links each one to its screen, and says when it capped a group', async () => {
    renderSearch({
      query: 'printer',
      amountInterpreted: null,
      groups: [
        {
          kind: 'journal_entry',
          total: 12,
          hits: [
            { kind: 'journal_entry', id: 'j1', label: 'Printer repair', detail: 'posted', date: '2026-03-04', amount: money('420.00'), where: 'journal' },
          ],
        },
        {
          kind: 'vendor',
          total: 1,
          hits: [{ kind: 'vendor', id: 'v1', label: 'Springfield Printing', detail: 'ap@example.test', date: null, amount: null, where: 'purchases' }],
        },
      ],
    });

    expect(await screen.findByText('Journal entries (12)')).toBeInTheDocument();
    expect(screen.getByText(/Showing the 1 most recent of 12/)).toBeInTheDocument();
    const row = screen.getByText('Printer repair').closest('tr') as HTMLElement;
    expect(within(row).getByRole('link', { name: 'Printer repair' }))
      .toHaveAttribute('href', '/orgs/o1/entities/e1/journal');
    expect(within(row).getByText('420.00')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Springfield Printing' }))
      .toHaveAttribute('href', '/orgs/o1/entities/e1/purchases');
  });

  test('says when the query was read as an amount, and when nothing matched', async () => {
    renderSearch({ query: '420', amountInterpreted: money('420.00'), groups: [] }, '420');

    expect(await screen.findByText(/Read as the amount 420.00/)).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Nothing matched');
  });
});
