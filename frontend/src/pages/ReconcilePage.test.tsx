import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { mockApi, money } from '../testSupport';
import ReconcilePage from './ReconcilePage';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const base = '/api/v1/orgs/o1/entities/e1';
const account = '/bank-accounts/b1/reconciliations';

const bankAccounts = [{ id: 'b1', glAccountId: 'a-1010', name: 'Checking', institution: null, mask: '1234' }];

const open = (difference: string, clearedCount: number) => ({
  id: 'r1', bankAccountId: 'b1', statementDate: '2026-03-31',
  statementEndingBalance: money('1500.00'), beginningBalance: money('1000.00'),
  clearedBalance: money(clearedCount === 0 ? '0.00' : '500.00'), difference: money(difference),
  clearedCount, status: 'open', completedAt: null,
});

const candidates = [
  { lineId: 'l1', entryId: 'j1', entryDate: '2026-03-02', memo: 'Client payment', amount: money('500.00'), cleared: false },
  { lineId: 'l2', entryId: 'j2', entryDate: '2026-03-09', memo: 'Coffee', amount: money('-18.75'), cleared: false },
];

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/orgs/o1/entities/e1/reconcile']}>
      <Routes>
        <Route path="/orgs/:orgId/entities/:entityId/reconcile" element={<ReconcilePage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('spec 034: reconciliation screen', () => {
  test('starts one when none is open', async () => {
    const user = userEvent.setup();
    let history: unknown[] = [];
    const { calls } = mockApi({
      [`GET ${base}/bank-accounts`]: bankAccounts,
      [`GET ${base}${account}`]: () => history,
      [`POST ${base}${account}`]: () => {
        history = [open('500.00', 0)];
        return history[0];
      },
      [`GET ${base}${account}/r1/candidates`]: candidates,
    });

    renderPage();
    await user.type(await screen.findByLabelText('Statement ending balance'), '1500.00');
    await user.click(screen.getByRole('button', { name: 'Start' }));

    await waitFor(() => expect(screen.getByText('Statement to 2026-03-31')).toBeInTheDocument());
    expect(calls.find((c) => c.key === `POST ${base}${account}`)?.body).toMatchObject({
      statementEndingBalance: { amount: '1500.00', currency: 'USD' },
    });
  });

  test('ticking a line sends it and takes the new figures from the server, then finishes at zero', async () => {
    const user = userEvent.setup();
    let history: unknown[] = [open('500.00', 0)];
    const { calls } = mockApi({
      [`GET ${base}/bank-accounts`]: bankAccounts,
      [`GET ${base}${account}`]: () => history,
      [`GET ${base}${account}/r1/candidates`]: candidates,
      [`POST ${base}${account}/r1/cleared`]: open('0.00', 1),
      [`POST ${base}${account}/r1/complete`]: () => {
        history = [{ ...open('0.00', 1), status: 'completed', completedAt: '2026-04-01T09:00:00Z' }];
        return history[0];
      },
    });

    renderPage();

    const finish = await screen.findByRole('button', { name: 'Finish' });
    expect(finish).toBeDisabled();

    await user.click(await screen.findByLabelText(/Cleared: 2026-03-02/));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Finish' })).toBeEnabled());
    expect(calls.find((c) => c.key === `POST ${base}${account}/r1/cleared`)?.body).toEqual({
      lineIds: ['l1'],
      cleared: true,
    });
    expect(screen.getByText('Cleared (1)')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Finish' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Undo' })).toBeInTheDocument());
    expect(screen.getByText('2026-04-01T09:00:00Z')).toBeInTheDocument();
  });

  test('shows the server message when finishing is refused', async () => {
    const user = userEvent.setup();
    mockApi(
      {
        [`GET ${base}/bank-accounts`]: bankAccounts,
        [`GET ${base}${account}`]: [open('0.00', 1)],
        [`GET ${base}${account}/r1/candidates`]: candidates,
        [`POST ${base}${account}/r1/complete`]: {
          detail: 'The difference must be zero before completing', code: 'NOT_BALANCED', status: 409,
        },
      },
      { status: { [`POST ${base}${account}/r1/complete`]: 409 } },
    );

    renderPage();
    await user.click(await screen.findByRole('button', { name: 'Finish' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('The difference must be zero before completing');
  });
});
