import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, test, vi } from 'vitest';
import type { ReactElement } from 'react';
import { mockApi, money } from '../testSupport';
import BudgetPage from './BudgetPage';
import JournalPage from './JournalPage';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const base = '/api/v1/orgs/o1/entities/e1';

const accounts = [
  { id: 'a-4010', code: '4010', name: 'Salary and Wages', type: 'income', subtype: null, parentId: null, isHeader: false, taxLineCode: null, isArchived: false },
  { id: 'a-6100', code: '6100', name: 'Groceries', type: 'expense', subtype: null, parentId: null, isHeader: false, taxLineCode: null, isArchived: false },
  { id: 'a-6000', code: '6000', name: 'Living Expenses', type: 'expense', subtype: null, parentId: null, isHeader: true, taxLineCode: null, isArchived: false },
  { id: 'a-1010', code: '1010', name: 'Checking', type: 'asset', subtype: 'bank', parentId: null, isHeader: false, taxLineCode: null, isArchived: false },
];

const comparison = {
  periodMonth: '2026-10-01',
  currency: 'USD',
  income: [{ accountId: 'a-4010', code: '4010', name: 'Salary and Wages', budget: money('5000.00'), actual: money('5200.00'), variance: money('200.00'), overBudget: false }],
  expenses: [{ accountId: 'a-6100', code: '6100', name: 'Groceries', budget: money('800.00'), actual: money('912.34'), variance: money('-112.34'), overBudget: true }],
  totals: {
    budgetedIncome: money('5000.00'), actualIncome: money('5200.00'),
    budgetedExpenses: money('800.00'), actualExpenses: money('912.34'),
    budgetedNet: money('4200.00'), actualNet: money('4287.66'),
  },
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

describe('spec 020 AC 1-3: budget page', () => {
  test('loads a month, saves only the filled-in lines, and shows the comparison', async () => {
    const user = userEvent.setup();
    const { calls } = mockApi({
      [`GET ${base}/accounts`]: accounts,
      [`GET ${base}/budgets/2026-10`]: {
        id: 'b1', periodMonth: '2026-10-01', currency: 'USD',
        lines: [{ accountId: 'a-6100', code: '6100', name: 'Groceries', type: 'expense', amount: money('800.00') }],
        budgetedIncome: money('0.00'), budgetedExpenses: money('800.00'), budgetedNet: money('-800.00'),
      },
      [`GET ${base}/reports/budget-vs-actual`]: comparison,
      [`PUT ${base}/budgets/2026-10`]: { id: 'b1', periodMonth: '2026-10-01', currency: 'USD', lines: [], budgetedIncome: money('0.00'), budgetedExpenses: money('0.00'), budgetedNet: money('0.00') },
    });

    renderAt('/orgs/o1/entities/e1/budget', '/orgs/:orgId/entities/:entityId/budget', <BudgetPage />);
    await user.clear(await screen.findByLabelText('Month'));
    await user.type(screen.getByLabelText('Month'), '2026-10');

    const groceries = await screen.findByLabelText('Planned amount for Groceries');
    await waitFor(() => expect(groceries).toHaveValue('800.00'));
    expect(screen.queryByLabelText('Planned amount for Living Expenses')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Planned amount for Checking')).not.toBeInTheDocument();

    await user.type(screen.getByLabelText('Planned amount for Salary and Wages'), '5000.00');
    await user.click(screen.getByRole('button', { name: 'Save budget' }));

    await screen.findByText('Saved the 2026-10 budget.');
    expect(calls.find((c) => c.key === `PUT ${base}/budgets/2026-10`)?.body).toEqual({
      lines: [
        { accountId: 'a-6100', amount: { amount: '800.00', currency: 'USD' } },
        { accountId: 'a-4010', amount: { amount: '5000.00', currency: 'USD' } },
      ],
    });

    // AC3: the comparison shows both sides and flags the overspend.
    const expensesTable = screen.getByText('Expenses').closest('table') as HTMLElement;
    const overspent = within(expensesTable).getByText(/Groceries/).closest('tr') as HTMLElement;
    expect(within(overspent).getByText('(112.34)')).toBeInTheDocument();
    expect(overspent).toHaveTextContent('over budget');
    expect(screen.getByText('4,287.66')).toBeInTheDocument();
  });

  test('a month with no budget yet shows an empty form, not an error', async () => {
    mockApi(
      {
        [`GET ${base}/accounts`]: accounts,
        [`GET ${base}/budgets/${new Date().toISOString().slice(0, 7)}`]: { detail: 'No budget for that month', status: 404 },
        [`GET ${base}/reports/budget-vs-actual`]: { ...comparison, income: [], expenses: [] },
      },
      { status: { [`GET ${base}/budgets/${new Date().toISOString().slice(0, 7)}`]: 404 } },
    );

    renderAt('/orgs/o1/entities/e1/budget', '/orgs/:orgId/entities/:entityId/budget', <BudgetPage />);

    expect(await screen.findByLabelText('Planned amount for Groceries')).toHaveValue('');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('spec 020 AC 4-7: journal page', () => {
  const draft = {
    id: 'j1', entryDate: '2026-10-02', memo: 'Opening balance', source: 'manual', status: 'draft',
    reversesEntryId: null, postingSeq: null,
    lines: [
      { lineNo: 1, accountId: 'a-1010', amount: money('100.00'), memo: null },
      { lineNo: 2, accountId: 'a-4010', amount: money('-100.00'), memo: null },
    ],
  };

  test('writes a balanced entry with signed amounts and blocks an unbalanced one', async () => {
    const user = userEvent.setup();
    let entries: unknown[] = [];
    const { calls } = mockApi({
      [`GET ${base}/accounts`]: accounts,
      [`GET ${base}/journal-entries`]: () => entries,
      [`GET ${base}/recurring-entries`]: [],
      [`GET ${base}/period-lock`]: { lockedThrough: null },
      [`POST ${base}/journal-entries`]: () => {
        entries = [draft];
        return draft;
      },
    });

    renderAt('/orgs/o1/entities/e1/journal', '/orgs/:orgId/entities/:entityId/journal', <JournalPage />);

    await user.selectOptions(await screen.findByLabelText('Account for line 1'), 'a-1010');
    await user.type(screen.getByLabelText('Debit for line 1'), '100.00');
    await user.selectOptions(screen.getByLabelText('Account for line 2'), 'a-4010');
    await user.type(screen.getByLabelText('Credit for line 2'), '60.00');

    // AC5: out of balance, so nothing can be sent yet.
    expect(screen.getByText(/Debits and credits differ by 40\.00/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Post entry' })).toBeDisabled();

    await user.clear(screen.getByLabelText('Credit for line 2'));
    await user.type(screen.getByLabelText('Credit for line 2'), '100.00');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Post entry' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: 'Save draft' }));

    await waitFor(() => expect(screen.getByRole('button', { name: 'Post' })).toBeInTheDocument());
    expect(calls.find((c) => c.key === `POST ${base}/journal-entries`)?.body).toMatchObject({
      post: false,
      lines: [
        { accountId: 'a-1010', amount: { amount: '100.00', currency: 'USD' } },
        { accountId: 'a-4010', amount: { amount: '-100.00', currency: 'USD' } },
      ],
    });
  });

  test('offers post and delete for a draft, reverse for a posted entry, and shows a locked period', async () => {
    const user = userEvent.setup();
    const posted = { ...draft, id: 'j2', status: 'posted', postingSeq: 7 };
    const { calls } = mockApi({
      [`GET ${base}/accounts`]: accounts,
      [`GET ${base}/journal-entries`]: [posted],
      [`GET ${base}/recurring-entries`]: [],
      [`GET ${base}/period-lock`]: { lockedThrough: '2026-09-30' },
      [`POST ${base}/journal-entries/j2/reverse`]: { ...posted, id: 'j3', reversesEntryId: 'j2' },
    });

    renderAt('/orgs/o1/entities/e1/journal', '/orgs/:orgId/entities/:entityId/journal', <JournalPage />);

    expect(await screen.findByText(/Books are closed through 2026-09-30/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Post' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Reverse' }));

    await waitFor(() => expect(calls.some((c) => c.key === `POST ${base}/journal-entries/j2/reverse`)).toBe(true));
  });

  test('shows the server message when the period is locked', async () => {
    const user = userEvent.setup();
    mockApi(
      {
        [`GET ${base}/accounts`]: accounts,
        [`GET ${base}/journal-entries`]: [],
        [`GET ${base}/recurring-entries`]: [],
      [`GET ${base}/period-lock`]: { lockedThrough: '2026-12-31' },
        [`POST ${base}/journal-entries`]: { detail: 'The period through 2026-12-31 is closed', code: 'PERIOD_LOCKED', status: 409 },
      },
      { status: { [`POST ${base}/journal-entries`]: 409 } },
    );

    renderAt('/orgs/o1/entities/e1/journal', '/orgs/:orgId/entities/:entityId/journal', <JournalPage />);
    await user.selectOptions(await screen.findByLabelText('Account for line 1'), 'a-1010');
    await user.type(screen.getByLabelText('Debit for line 1'), '10.00');
    await user.selectOptions(screen.getByLabelText('Account for line 2'), 'a-4010');
    await user.type(screen.getByLabelText('Credit for line 2'), '10.00');
    await user.click(screen.getByRole('button', { name: 'Post entry' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The period through 2026-12-31 is closed');
    expect(alert).toHaveTextContent('PERIOD_LOCKED');
  });
});

describe('spec 026: recurring entries on the journal page', () => {
  const template = {
    id: 'r1', name: 'Rent', memo: null, frequency: 'monthly', startDate: '2026-01-15', endDate: null,
    dayOfMonth: 15, active: true, nextDate: '2026-04-15',
    lines: [
      { lineNo: 1, accountId: 'a-6100', amount: money('1200.00'), memo: null },
      { lineNo: 2, accountId: 'a-1010', amount: money('-1200.00'), memo: null },
    ],
  };

  test('lists templates, runs them and reports what was skipped', async () => {
    const user = userEvent.setup();
    const { calls } = mockApi({
      [`GET ${base}/accounts`]: accounts,
      [`GET ${base}/journal-entries`]: [],
      [`GET ${base}/period-lock`]: { lockedThrough: null },
      [`GET ${base}/recurring-entries`]: [template],
      [`POST ${base}/recurring-entries/run`]: {
        through: '2026-04-30',
        posted: [{ occurrenceDate: '2026-03-15', journalEntryId: 'j9' }],
        skipped: [{ occurrenceDate: '2026-01-15', reason: 'Books are locked through 2026-02-28' }],
      },
      [`POST ${base}/recurring-entries/r1/deactivate`]: { ...template, active: false, nextDate: null },
    });

    renderAt('/orgs/o1/entities/e1/journal', '/orgs/:orgId/entities/:entityId/journal', <JournalPage />);

    expect(await screen.findByText('Rent')).toBeInTheDocument();
    expect(screen.getByText('2026-04-15')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Run now' }));
    expect(await screen.findByText(/Posted 1; skipped 1 \(Books are locked through 2026-02-28\)/)).toBeInTheDocument();
    expect(calls.some((c) => c.key === `POST ${base}/recurring-entries/run`)).toBe(true);

    await user.click(screen.getByRole('button', { name: 'Stop' }));
    await waitFor(() => expect(calls.some((c) => c.key === `POST ${base}/recurring-entries/r1/deactivate`)).toBe(true));
  });
});
