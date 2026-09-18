import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { mockApi } from '../testSupport';
import OrganizationPage from './OrganizationPage';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const base = '/api/v1/orgs/o1';

const members = [
  { userId: 'u1', email: 'owner@example.test', displayName: 'Owner', role: 'owner' },
  { userId: 'u2', email: 'book@example.test', displayName: 'Book Keeper', role: 'bookkeeper' },
];

const events = [
  {
    seq: 2, id: 'e2', occurredAt: '2026-09-18T10:05:00Z', orgId: 'o1', actorUserId: 'u1', actorIp: '10.0.0.1',
    action: 'journal_entry_reversed', objectType: 'journal_entry', objectId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
    details: { reason: 'typo' },
  },
  {
    seq: 1, id: 'e1', occurredAt: '2026-09-18T10:00:00Z', orgId: 'o1', actorUserId: 'u1', actorIp: '10.0.0.1',
    action: 'member_added', objectType: 'user', objectId: 'u2', details: { role: 'bookkeeper' },
  },
];

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/orgs/o1/settings']}>
      <Routes>
        <Route path="/orgs/:orgId/settings" element={<OrganizationPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('spec 025: people and activity', () => {
  test('lists members, adds one, and lists activity newest first', async () => {
    const user = userEvent.setup();
    let current = members;
    const { calls } = mockApi({
      [`GET ${base}/members`]: () => current,
      [`GET ${base}/audit-events`]: events,
      [`POST ${base}/members`]: () => {
        current = [...current, { userId: 'u3', email: 'cpa@example.test', displayName: 'CPA', role: 'accountant' }];
        return current[current.length - 1];
      },
    });

    renderPage();

    expect(await screen.findByText('book@example.test')).toBeInTheDocument();

    await user.type(screen.getByLabelText('Email'), 'cpa@example.test');
    await user.selectOptions(screen.getByLabelText('Role'), 'accountant');
    expect(screen.getByText('full access to the books and reports')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Add member' }));

    await waitFor(() => expect(screen.getByText('cpa@example.test')).toBeInTheDocument());
    expect(calls.find((c) => c.key === `POST ${base}/members`)?.body).toEqual({
      email: 'cpa@example.test',
      role: 'accountant',
    });

    const rows = screen.getAllByRole('row');
    const activity = rows.filter((row) => row.textContent?.includes('journal_entry_reversed'));
    expect(activity).toHaveLength(1);
    expect(activity[0]).toHaveTextContent('reason=typo');
    expect(screen.getByText('member_added')).toBeInTheDocument();
  });

  test('changing the row limit re-requests', async () => {
    const user = userEvent.setup();
    const { calls } = mockApi({
      [`GET ${base}/members`]: members,
      [`GET ${base}/audit-events`]: events,
    });

    renderPage();
    await screen.findByText('member_added');
    await user.selectOptions(screen.getByLabelText('Rows'), '200');

    await waitFor(() =>
      expect(calls.filter((c) => c.key === `GET ${base}/audit-events`).length).toBeGreaterThan(1),
    );
  });

  test('explains a 403 instead of showing an empty page', async () => {
    mockApi(
      {
        [`GET ${base}/members`]: { detail: 'Only owners and admins can do this', status: 403 },
        [`GET ${base}/audit-events`]: { detail: 'Only owners and admins can do this', status: 403 },
      },
      { status: { [`GET ${base}/members`]: 403, [`GET ${base}/audit-events`]: 403 } },
    );

    renderPage();

    expect(await screen.findByText(/Only owners and admins can see who has access/)).toBeInTheDocument();
    expect(screen.getByText(/Only owners and admins can see the activity log/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to entities' })).toBeInTheDocument();
  });

  test('shows the server message when adding a member is refused', async () => {
    const user = userEvent.setup();
    mockApi(
      {
        [`GET ${base}/members`]: members,
        [`GET ${base}/audit-events`]: events,
        [`POST ${base}/members`]: { detail: 'Only owners can add owners', status: 403 },
      },
      { status: { [`POST ${base}/members`]: 403 } },
    );

    renderPage();
    await user.type(await screen.findByLabelText('Email'), 'someone@example.test');
    await user.selectOptions(screen.getByLabelText('Role'), 'owner');
    await user.click(screen.getByRole('button', { name: 'Add member' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Only owners can add owners');
  });
});
