import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, test, vi } from 'vitest';
import App from './App';
import { formatMoney } from './api';
import { AuthProvider } from './auth';
import BankPage from './pages/BankPage';
import DashboardPage from './pages/DashboardPage';
import { mockApi, money } from './testSupport';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const ME_PENDING = { user: { id: 'u1', email: 'a@b.test', displayName: 'A', mfaEnabled: false, isInstanceAdmin: true }, mfaVerified: false, organizationIds: [] };
const ME_READY = { ...ME_PENDING, mfaVerified: true, organizationIds: [] };

function renderApp() {
  return render(
    <MemoryRouter>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe('spec 011 AC 1-2: login and MFA', () => {
  test('signs in, enrolls MFA and shows recovery codes before continuing', async () => {
    const user = userEvent.setup();
    let verified = false;
    mockApi({
      'GET /api/v1/auth/me': () => (verified ? ME_READY : { detail: 'Please log in', status: 401, code: 'UNAUTHENTICATED' }),
      'POST /api/v1/auth/login': { mfaEnrolled: false },
      'POST /api/v1/auth/mfa/enroll': { secret: 'JBSWY3DPEHPK3PXP', otpauthUri: 'otpauth://totp/Solid:a@b.test?secret=JBSWY3DPEHPK3PXP' },
      'POST /api/v1/auth/mfa/activate': () => {
        verified = true;
        return { recoveryCodes: ['abcde-12345', 'fghij-67890'] };
      },
      'GET /api/v1/orgs': [],
    }, { status: { 'GET /api/v1/auth/me': 200 } });

    renderApp();
    expect(await screen.findByRole('heading', { name: 'Sign in to Solid' })).toBeInTheDocument();

    await user.type(screen.getByLabelText('Email'), 'a@b.test');
    await user.type(screen.getByLabelText('Password'), 'correct horse battery staple');
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(await screen.findByText('JBSWY3DPEHPK3PXP')).toBeInTheDocument();
    await user.type(screen.getByLabelText('6-digit code'), '123456');
    await user.click(screen.getByRole('button', { name: 'Turn on two-factor' }));

    expect(await screen.findByRole('heading', { name: 'Save your recovery codes' })).toBeInTheDocument();
    expect(screen.getByText('abcde-12345')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Organizations' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /I've saved them/ }));
    expect(await screen.findByRole('heading', { name: 'Organizations' })).toBeInTheDocument();
  });

  test('shows the API error message when the password is wrong', async () => {
    const user = userEvent.setup();
    mockApi(
      {
        'GET /api/v1/auth/me': { detail: 'Please log in', status: 401 },
        'POST /api/v1/auth/login': { detail: 'Email or password is incorrect', code: 'INVALID_CREDENTIALS', status: 401 },
      },
      { status: { 'GET /api/v1/auth/me': 401, 'POST /api/v1/auth/login': 401 } },
    );

    renderApp();
    await user.type(await screen.findByLabelText('Email'), 'a@b.test');
    await user.type(screen.getByLabelText('Password'), 'nope');
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Email or password is incorrect');
    expect(alert).toHaveTextContent('INVALID_CREDENTIALS');
  });

  test('a session pending MFA cannot reach the app', async () => {
    mockApi({ 'GET /api/v1/auth/me': ME_PENDING });
    renderApp();
    expect(await screen.findByRole('heading', { name: 'Sign in to Solid' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Organizations' })).not.toBeInTheDocument();
  });
});

describe('spec 011 AC 3: review queue', () => {
  const accounts = [
    { id: 'a-6220', code: '6220', name: 'Software and Subscriptions', type: 'expense', subtype: null, parentId: null, isHeader: false, taxLineCode: 'F1040.SCH_C.L27b', isArchived: false },
    { id: 'a-6010', code: '6010', name: 'Advertising', type: 'expense', subtype: null, parentId: null, isHeader: false, taxLineCode: 'F1040.SCH_C.L8', isArchived: false },
    { id: 'a-6000', code: '6000', name: 'Operating Expenses', type: 'expense', subtype: null, parentId: null, isHeader: true, taxLineCode: null, isArchived: false },
  ];
  const txn = {
    id: 't1', bankAccountId: 'b1', postedDate: '2026-09-03', amount: money('-54.99'),
    description: 'ADOBE *CREATIVE CLD', status: 'new', suggestedAccountId: 'a-6220',
    suggestionSource: 'history', journalEntryId: null,
  };

  test('pre-selects the suggestion and confirms after categorizing', async () => {
    const user = userEvent.setup();
    let categorized = false;
    const { calls } = mockApi({
      'GET /api/v1/orgs/o1/entities/e1/accounts': accounts,
      'GET /api/v1/orgs/o1/entities/e1/bank-accounts': [{ id: 'b1', glAccountId: 'a-1010', name: 'Checking', institution: null, mask: '1234' }],
      'GET /api/v1/orgs/o1/entities/e1/bank-transactions': () => (categorized ? [] : [txn]),
      'POST /api/v1/orgs/o1/entities/e1/bank-transactions/t1/categorize': () => {
        categorized = true;
        return { ...txn, status: 'categorized', journalEntryId: 'j1' };
      },
    });

    render(
      <MemoryRouter initialEntries={['/orgs/o1/entities/e1/bank']}>
        <Routes>
          <Route path="/orgs/:orgId/entities/:entityId/bank" element={<BankPage />} />
        </Routes>
      </MemoryRouter>,
    );

    const select = await screen.findByLabelText('Category for ADOBE *CREATIVE CLD');
    expect(select).toHaveValue('a-6220');
    expect(screen.getByText('suggested from history')).toBeInTheDocument();
    expect(screen.getByText('(54.99)')).toBeInTheDocument();

    await user.selectOptions(select, 'a-6010');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(screen.getByText(/Recorded 2026-09-03/)).toBeInTheDocument());
    expect(calls.find((c) => c.key.endsWith('/categorize'))?.body).toEqual({ accountId: 'a-6010' });
    expect(screen.getByText('Nothing to review. Nice.')).toBeInTheDocument();
  });
});

describe('spec 011 AC 4: dashboard readiness', () => {
  test('lists what is still missing', async () => {
    mockApi({
      'GET /api/v1/orgs/o1/entities/e1/reports/tax-lines': {
        taxYear: 2026, from: '2026-01-01', to: '2026-12-31', lines: [], unmapped: [],
        totals: { income: money('2500.00'), costOfGoodsSold: money('0.00'), expenses: money('500.00'), netProfit: money('2000.00') },
        readiness: { draftEntries: 1, uncategorizedBankTransactions: 4, unmappedAccounts: 0, ready: false },
      },
    });

    render(
      <MemoryRouter initialEntries={['/orgs/o1/entities/e1']}>
        <Routes>
          <Route path="/orgs/:orgId/entities/:entityId" element={<DashboardPage />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByText('2,000.00')).toBeInTheDocument();
    expect(screen.getByText(/4 bank transaction\(s\) still need a category/)).toBeInTheDocument();
    expect(screen.getByText(/1 draft journal entry/)).toBeInTheDocument();
  });
});

describe('money formatting', () => {
  test('groups thousands and shows negatives in parentheses', () => {
    expect(formatMoney(money('1234567.89'))).toBe('1,234,567.89');
    expect(formatMoney(money('-54.99'))).toBe('(54.99)');
    expect(formatMoney(money('0.00'))).toBe('0.00');
    expect(formatMoney(undefined)).toBe('');
  });
});

describe('spec 061: making the first account', () => {
  test('an unclaimed instance offers to make the administrator account, then goes on to two-factor', async () => {
    const user = userEvent.setup();
    const { calls } = mockApi({
      'GET /api/v1/auth/me': { detail: 'Please log in', status: 401, code: 'UNAUTHENTICATED' },
      'GET /api/v1/auth/setup-state': { setupNeeded: true },
      'POST /api/v1/auth/signup': { id: 'u1', email: 'owner@example.test', displayName: 'The Owner' },
      'POST /api/v1/auth/login': { mfaEnrolled: false },
      'POST /api/v1/auth/mfa/enroll': { secret: 'JBSWY3DPEHPK3PXP', otpauthUri: 'otpauth://totp/Solid' },
    }, { status: { 'GET /api/v1/auth/me': 401, 'POST /api/v1/auth/signup': 201 } });

    renderApp();

    expect(await screen.findByRole('heading', { name: 'Create the first account' })).toBeInTheDocument();
    // AC4: it says what this account is and what the password has to be, before anything is typed.
    expect(screen.getByText(/administrator/)).toBeInTheDocument();
    expect(screen.getByText(/At least 12 characters/)).toBeInTheDocument();

    await user.type(screen.getByLabelText('Your name'), 'The Owner');
    await user.type(screen.getByLabelText('Email'), 'owner@example.test');
    await user.type(screen.getByLabelText('Password'), 'correct horse battery staple');
    await user.click(screen.getByRole('button', { name: 'Create the account' }));

    // AC3: created, signed in, and straight on to two-factor setup.
    expect(await screen.findByText('JBSWY3DPEHPK3PXP')).toBeInTheDocument();
    expect(calls.find((c) => c.key === 'POST /api/v1/auth/signup')?.body).toMatchObject({
      email: 'owner@example.test',
      displayName: 'The Owner',
    });
    expect(calls.some((c) => c.key === 'POST /api/v1/auth/login')).toBe(true);
  });

  test('an instance that already has accounts shows the ordinary sign-in form', async () => {
    mockApi({
      'GET /api/v1/auth/me': { detail: 'Please log in', status: 401, code: 'UNAUTHENTICATED' },
      'GET /api/v1/auth/setup-state': { setupNeeded: false },
    }, { status: { 'GET /api/v1/auth/me': 401 } });

    renderApp();

    expect(await screen.findByRole('heading', { name: 'Sign in to Solid' })).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.queryByRole('heading', { name: 'Create the first account' })).not.toBeInTheDocument(),
    );
  });
});
