import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { mockApi, money } from '../testSupport';
import BankPage from './BankPage';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const base = '/api/v1/orgs/o1/entities/e1';

const accounts = [
  { id: 'a-6220', code: '6220', name: 'Software and Subscriptions', type: 'expense', subtype: null, parentId: null, isHeader: false, taxLineCode: null, isArchived: false },
  { id: 'a-6010', code: '6010', name: 'Advertising', type: 'expense', subtype: null, parentId: null, isHeader: false, taxLineCode: null, isArchived: false },
];

const bankAccounts = [{ id: 'b1', glAccountId: 'a-1010', name: 'Checking', institution: null, mask: '1234' }];

const txn = (id: string, description: string) => ({
  id, bankAccountId: 'b1', postedDate: '2026-09-03', amount: money('-54.99'), description,
  status: 'new', suggestedAccountId: null, suggestionSource: null, journalEntryId: null,
});

const document = {
  id: 'd1', filename: 'adobe.pdf', contentType: 'application/pdf', sizeBytes: 1024,
  sha256: 'a'.repeat(64), kind: 'receipt', note: null, uploadedAt: '2026-09-18T10:00:00Z',
  links: [{ objectType: 'bank_transaction', objectId: 't1' }],
};

function renderBank() {
  return render(
    <MemoryRouter initialEntries={['/orgs/o1/entities/e1/bank']}>
      <Routes>
        <Route path="/orgs/:orgId/entities/:entityId/bank" element={<BankPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('spec 022 AC 1-3: categorization rules', () => {
  test('lists rules by priority, creates one and deletes one', async () => {
    const user = userEvent.setup();
    let rules = [
      { id: 'r2', entityId: 'e1', contains: 'ADOBE', accountId: 'a-6220', priority: 200 },
      { id: 'r1', entityId: 'e1', contains: 'FACEBOOK ADS', accountId: 'a-6010', priority: 10 },
    ];
    const { calls } = mockApi({
      [`GET ${base}/accounts`]: accounts,
      [`GET ${base}/bank-accounts`]: bankAccounts,
      [`GET ${base}/bank-transactions`]: [],
      [`GET ${base}/documents`]: [],
      [`GET ${base}/categorization-rules`]: () => rules,
      [`POST ${base}/categorization-rules`]: () => {
        rules = [...rules, { id: 'r3', entityId: 'e1', contains: 'GITHUB', accountId: 'a-6220', priority: 100 }];
        return rules[rules.length - 1];
      },
      [`DELETE ${base}/categorization-rules/r2`]: () => {
        rules = rules.filter((r) => r.id !== 'r2');
        return {};
      },
    });

    renderBank();

    const rulesTable = (await screen.findByText('When the description contains')).closest('table') as HTMLElement;
    const rows = within(rulesTable).getAllByRole('row').slice(1);
    expect(rows[0]).toHaveTextContent('FACEBOOK ADS');
    expect(rows[0]).toHaveTextContent('6010 Advertising');
    expect(rows[1]).toHaveTextContent('ADOBE');

    await user.type(screen.getByLabelText('Description contains'), 'GITHUB');
    await user.click(screen.getByRole('button', { name: 'Add rule' }));
    await waitFor(() => expect(screen.getByText('GITHUB')).toBeInTheDocument());
    expect(calls.find((c) => c.key === `POST ${base}/categorization-rules`)?.body).toEqual({
      contains: 'GITHUB',
      accountId: 'a-6220',
      priority: 100,
    });

    const adobeRow = screen.getByText('ADOBE').closest('tr') as HTMLElement;
    await user.click(within(adobeRow).getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(screen.queryByText('ADOBE')).not.toBeInTheDocument());
  });
});

describe('spec 022 AC 4-7: bulk save and receipts', () => {
  test('saves every reviewed row in one request', async () => {
    const user = userEvent.setup();
    let queue: unknown[] = [txn('t1', 'ADOBE *CREATIVE CLD'), txn('t2', 'FACEBOOK ADS')];
    const { calls } = mockApi({
      [`GET ${base}/accounts`]: accounts,
      [`GET ${base}/bank-accounts`]: bankAccounts,
      [`GET ${base}/bank-transactions`]: () => queue,
      [`GET ${base}/documents`]: [],
      [`GET ${base}/categorization-rules`]: [],
      [`POST ${base}/bank-transactions/categorize`]: () => {
        queue = [];
        return [];
      },
    });

    renderBank();
    await user.selectOptions(await screen.findByLabelText('Category for ADOBE *CREATIVE CLD'), 'a-6220');
    await user.click(screen.getByRole('button', { name: 'Save all reviewed' }));

    await waitFor(() => expect(screen.getByText('Nothing to review. Nice.')).toBeInTheDocument());
    expect(calls.find((c) => c.key === `POST ${base}/bank-transactions/categorize`)?.body).toEqual({
      items: [{ id: 't1', accountId: 'a-6220' }],
    });
  });

  test('uploads a receipt, links it to the transaction, and shows an existing one', async () => {
    const user = userEvent.setup();
    let documents: unknown[] = [];
    const { calls } = mockApi({
      [`GET ${base}/accounts`]: accounts,
      [`GET ${base}/bank-accounts`]: bankAccounts,
      [`GET ${base}/bank-transactions`]: [txn('t1', 'ADOBE *CREATIVE CLD')],
      [`GET ${base}/documents`]: () => documents,
      [`GET ${base}/categorization-rules`]: [],
      [`POST ${base}/documents`]: { ...document, links: [] },
      [`POST ${base}/documents/d1/links`]: () => {
        documents = [document];
        return document;
      },
    });

    renderBank();
    const file = new File([new Uint8Array([0x25, 0x50, 0x44, 0x46])], 'adobe.pdf', { type: 'application/pdf' });
    await user.upload(await screen.findByLabelText('Receipt for ADOBE *CREATIVE CLD'), file);

    await waitFor(() => expect(screen.getByRole('link', { name: 'adobe.pdf' })).toBeInTheDocument());
    const uploaded = calls.find((c) => c.key === `POST ${base}/documents`);
    expect(uploaded).toBeDefined();
    expect(calls.find((c) => c.key === `POST ${base}/documents/d1/links`)?.body).toEqual({
      objectType: 'bank_transaction',
      objectId: 't1',
    });
  });

  test('shows the server message when a bulk save fails', async () => {
    const user = userEvent.setup();
    mockApi(
      {
        [`GET ${base}/accounts`]: accounts,
        [`GET ${base}/bank-accounts`]: bankAccounts,
        [`GET ${base}/bank-transactions`]: [txn('t1', 'ADOBE *CREATIVE CLD')],
        [`GET ${base}/documents`]: [],
        [`GET ${base}/categorization-rules`]: [],
        [`POST ${base}/bank-transactions/categorize`]: { detail: 'The period through 2026-09-30 is closed', code: 'PERIOD_LOCKED', status: 409 },
      },
      { status: { [`POST ${base}/bank-transactions/categorize`]: 409 } },
    );

    renderBank();
    await user.selectOptions(await screen.findByLabelText('Category for ADOBE *CREATIVE CLD'), 'a-6010');
    await user.click(screen.getByRole('button', { name: 'Save all reviewed' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The period through 2026-09-30 is closed');
    expect(await screen.findByLabelText('Category for ADOBE *CREATIVE CLD')).toHaveValue('a-6010');
  });
});

describe('spec 030: category suggestion from the local model', () => {
  test('fills the row with the suggested account and says which model said so', async () => {
    const user = userEvent.setup();
    mockApi({
      [`GET ${base}/accounts`]: accounts,
      [`GET ${base}/bank-accounts`]: bankAccounts,
      [`GET ${base}/bank-transactions`]: [txn('t1', 'SQ *BLUE BOTTLE')],
      [`GET ${base}/documents`]: [],
      [`GET ${base}/categorization-rules`]: [],
      [`POST ${base}/bank-transactions/t1/suggest`]: {
        accountId: 'a-6010', code: '6010', name: 'Advertising', source: 'ai', model: 'llama3.2', reason: null,
      },
    });

    renderBank();
    await user.click(await screen.findByRole('button', { name: 'Ask the model' }));

    await waitFor(() =>
      expect(screen.getByLabelText('Category for SQ *BLUE BOTTLE')).toHaveValue('a-6010'),
    );
    expect(screen.getByText(/llama3.2 suggests 6010 Advertising — check it before saving/)).toBeInTheDocument();
  });

  test('says why when there is no suggestion and changes nothing', async () => {
    const user = userEvent.setup();
    mockApi({
      [`GET ${base}/accounts`]: accounts,
      [`GET ${base}/bank-accounts`]: bankAccounts,
      [`GET ${base}/bank-transactions`]: [txn('t1', 'SQ *BLUE BOTTLE')],
      [`GET ${base}/documents`]: [],
      [`GET ${base}/categorization-rules`]: [],
      [`POST ${base}/bank-transactions/t1/suggest`]: {
        accountId: null, code: null, name: null, source: null, model: null,
        reason: 'Category suggestions are turned off on this server (solid.ai.enabled).',
      },
    });

    renderBank();
    await user.click(await screen.findByRole('button', { name: 'Ask the model' }));

    expect(await screen.findByText(/turned off on this server/)).toBeInTheDocument();
    expect(screen.getByLabelText('Category for SQ *BLUE BOTTLE')).toHaveValue('');
  });
});
