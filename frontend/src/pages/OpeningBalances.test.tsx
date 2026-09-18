import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { mockApi, money } from '../testSupport';
import AccountsPage from './AccountsPage';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const base = '/api/v1/orgs/o1/entities/e1';

const accounts = [
  { id: 'a-1000', code: '1000', name: 'Assets', type: 'asset', subtype: null, parentId: null, isHeader: true, taxLineCode: null, isArchived: false },
  { id: 'a-1010', code: '1010', name: 'Business Checking', type: 'asset', subtype: 'bank', parentId: 'a-1000', isHeader: false, taxLineCode: null, isArchived: false },
  { id: 'a-2010', code: '2010', name: 'Business Credit Card', type: 'liability', subtype: 'credit_card', parentId: null, isHeader: false, taxLineCode: null, isArchived: false },
  { id: 'a-3010', code: '3010', name: "Owner's Contributions", type: 'equity', subtype: 'owner_contribution', parentId: null, isHeader: false, taxLineCode: null, isArchived: false },
  { id: 'a-6010', code: '6010', name: 'Advertising', type: 'expense', subtype: null, parentId: null, isHeader: false, taxLineCode: null, isArchived: false },
];

const entry = {
  id: 'ob-1234-5678', entityId: 'e1', entryDate: '2026-01-01', memo: 'Opening balances', source: 'opening_balance',
  status: 'posted', reversesEntryId: null, postingSeq: 1,
  lines: [
    { lineNo: 1, accountId: 'a-1010', amount: money('5000.00'), memo: null },
    { lineNo: 2, accountId: 'a-3010', amount: money('-5000.00'), memo: null },
  ],
};

function renderAccounts() {
  return render(
    <MemoryRouter initialEntries={['/orgs/o1/entities/e1/accounts']}>
      <Routes>
        <Route path="/orgs/:orgId/entities/:entityId/accounts" element={<AccountsPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('spec 028: opening balances on the accounts page', () => {
  test('offers only balance-sheet accounts and sends the positive amounts typed', async () => {
    const user = userEvent.setup();
    let saved = false;
    const { calls } = mockApi(
      {
        [`GET ${base}/accounts`]: accounts,
        [`GET ${base}/opening-balances`]: () => (saved ? entry : { detail: 'no opening balances yet', status: 404 }),
        [`POST ${base}/opening-balances`]: () => {
          saved = true;
          return entry;
        },
      },
      { status: { [`GET ${base}/opening-balances`]: 404 } },
    );

    renderAccounts();

    const checking = await screen.findByLabelText('Opening balance for Business Checking');
    expect(screen.getByLabelText('Opening balance for Business Credit Card')).toBeInTheDocument();
    expect(screen.queryByLabelText('Opening balance for Advertising')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Opening balance for Assets')).not.toBeInTheDocument();

    await user.type(checking, '5000.00');
    await user.type(screen.getByLabelText('Opening balance for Business Credit Card'), '1200.00');
    await user.selectOptions(screen.getByLabelText('Balancing equity account'), 'a-3010');
    await user.click(screen.getByRole('button', { name: 'Save opening balances' }));

    await waitFor(() => expect(calls.some((c) => c.key === `POST ${base}/opening-balances`)).toBe(true));
    expect(calls.find((c) => c.key === `POST ${base}/opening-balances`)?.body).toMatchObject({
      equityAccountId: 'a-3010',
      balances: [
        { accountId: 'a-1010', amount: { amount: '5000.00', currency: 'USD' } },
        { accountId: 'a-2010', amount: { amount: '1200.00', currency: 'USD' } },
      ],
    });
  });

  test('once set, it says so and points at the reversal instead of offering the form again', async () => {
    mockApi({
      [`GET ${base}/accounts`]: accounts,
      [`GET ${base}/opening-balances`]: entry,
    });

    renderAccounts();

    expect(await screen.findByText(/Set on 2026-01-01/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save opening balances' })).not.toBeInTheDocument();
  });

  test('shows the server message when the chart has no opening-balance account', async () => {
    const user = userEvent.setup();
    mockApi(
      {
        [`GET ${base}/accounts`]: accounts,
        [`GET ${base}/opening-balances`]: { detail: 'no opening balances yet', status: 404 },
        [`POST ${base}/opening-balances`]: {
          detail: "This chart of accounts has no equity account with subtype 'opening_balance'.",
          status: 400,
        },
      },
      { status: { [`GET ${base}/opening-balances`]: 404, [`POST ${base}/opening-balances`]: 400 } },
    );

    renderAccounts();
    await user.type(await screen.findByLabelText('Opening balance for Business Checking'), '10.00');
    await user.click(screen.getByRole('button', { name: 'Save opening balances' }));

    expect(await screen.findByRole('alert')).toHaveTextContent("no equity account with subtype 'opening_balance'");
  });
});
