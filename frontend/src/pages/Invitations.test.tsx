import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { mockApi } from '../testSupport';
import AcceptInvitationPage from './AcceptInvitationPage';
import OrganizationPage from './OrganizationPage';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const invitation = {
  id: 'i1', orgId: 'o1', email: 'accountant@example.test', role: 'accountant', status: 'pending',
  invitedBy: 'u1', createdAt: '2026-09-21T10:00:00Z', expiresAt: '2026-09-28T10:00:00Z', token: null,
};

describe('spec 046: inviting the second person', () => {
  test('shows the link once, and lets an owner withdraw a pending invitation', async () => {
    const { calls } = mockApi({
      'GET /api/v1/orgs/o1/members': [{ userId: 'u1', email: 'owner@example.test', displayName: 'Owner', role: 'owner' }],
      'GET /api/v1/orgs/o1/audit-events': [],
      'GET /api/v1/orgs/o1/invitations': [invitation],
      'POST /api/v1/orgs/o1/invitations': { ...invitation, token: 'sekrit-token' },
      'DELETE /api/v1/orgs/o1/invitations/i1': undefined,
    });

    render(
      <MemoryRouter initialEntries={['/orgs/o1/settings']}>
        <Routes>
          <Route path="/orgs/:orgId/settings" element={<OrganizationPage />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByText('Invitations')).toBeInTheDocument();
    expect(screen.getByText(/Solid does not send the email/)).toBeInTheDocument();

    const form = screen.getByRole('button', { name: 'Create invitation' }).closest('form') as HTMLElement;
    fireEvent.change(within(form).getByLabelText('Email to invite'), { target: { value: 'new@example.test' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create invitation' }));

    const link = (await screen.findByLabelText('Invitation link')) as HTMLInputElement;
    expect(link.value).toContain('/accept-invitation?token=sekrit-token');
    expect(calls.find((c) => c.key === 'POST /api/v1/orgs/o1/invitations')?.body)
      .toMatchObject({ email: 'new@example.test' });

    fireEvent.click(screen.getByRole('button', { name: 'Withdraw' }));
    expect(calls.some((c) => c.key === 'DELETE /api/v1/orgs/o1/invitations/i1')).toBe(true);
  });

  test('the accept page takes a name and password and says what happened', async () => {
    const { calls } = mockApi({
      'POST /api/v1/auth/accept-invitation': { organizationId: 'o1', email: 'accountant@example.test', created: true },
    });

    render(
      <MemoryRouter initialEntries={['/accept-invitation?token=abc123']}>
        <Routes>
          <Route path="/accept-invitation" element={<AcceptInvitationPage />} />
        </Routes>
      </MemoryRouter>,
    );

    fireEvent.change(screen.getByLabelText('Your name'), { target: { value: 'New Accountant' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'correct horse battery staple' } });
    fireEvent.click(screen.getByRole('button', { name: 'Accept' }));

    expect(await screen.findByRole('status')).toHaveTextContent(/two-factor authentication/);
    expect(calls.find((c) => c.key === 'POST /api/v1/auth/accept-invitation')?.body)
      .toMatchObject({ token: 'abc123', displayName: 'New Accountant' });
  });

  test('a link with no token says so instead of failing silently', () => {
    mockApi({});
    render(
      <MemoryRouter initialEntries={['/accept-invitation']}>
        <Routes>
          <Route path="/accept-invitation" element={<AcceptInvitationPage />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(screen.getByRole('alert')).toHaveTextContent('no invitation in it');
  });
});

describe('spec 047: changing and removing people', () => {
  const owner = { userId: 'u1', email: 'owner@example.test', displayName: 'Owner', role: 'owner' };
  const helper = { userId: 'u2', email: 'helper@example.test', displayName: 'Helper', role: 'bookkeeper' };

  function renderPeople(routes: Record<string, unknown>, status?: Record<string, number>) {
    const mocked = mockApi(
      {
        'GET /api/v1/orgs/o1/members': [owner, helper],
        'GET /api/v1/orgs/o1/audit-events': [],
        'GET /api/v1/orgs/o1/invitations': [],
        ...routes,
      },
      status ? { status } : {},
    );
    render(
      <MemoryRouter initialEntries={['/orgs/o1/settings']}>
        <Routes>
          <Route path="/orgs/:orgId/settings" element={<OrganizationPage />} />
        </Routes>
      </MemoryRouter>,
    );
    return mocked;
  }

  test('changes a role and removes a member', async () => {
    const { calls } = renderPeople({
      'PATCH /api/v1/orgs/o1/members/u2': { ...helper, role: 'accountant' },
      'DELETE /api/v1/orgs/o1/members/u2': undefined,
    });

    fireEvent.change(await screen.findByLabelText('Role for helper@example.test'), {
      target: { value: 'accountant' },
    });
    expect(calls.find((c) => c.key === 'PATCH /api/v1/orgs/o1/members/u2')?.body)
      .toMatchObject({ role: 'accountant' });

    fireEvent.click(screen.getByLabelText('Remove helper@example.test'));
    expect(calls.some((c) => c.key === 'DELETE /api/v1/orgs/o1/members/u2')).toBe(true);
  });

  test('shows the server refusal when it would leave nobody in charge', async () => {
    renderPeople(
      {
        'DELETE /api/v1/orgs/o1/members/u1': {
          detail: 'You cannot remove the last owner: someone has to be able to administer these books.',
          code: 'LAST_OWNER',
        },
      },
      { 'DELETE /api/v1/orgs/o1/members/u1': 409 },
    );

    expect(await screen.findByText(/always keeps at least one owner/)).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText('Remove owner@example.test'));

    expect(await screen.findByRole('alert')).toHaveTextContent('cannot remove the last owner');
  });
});
