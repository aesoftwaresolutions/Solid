import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { mockApi, renderApp } from '../testSupport';
import AccountPage from './AccountPage';
import ResetPasswordPage from './ResetPasswordPage';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const me = {
  user: { id: 'u1', email: 'owner@example.test', displayName: 'Owner', mfaEnabled: true, isInstanceAdmin: false },
  mfaVerified: true,
  organizationIds: ['o1'],
};

describe('spec 048: changing a password', () => {
  test('sends both passwords and says the other sessions are gone', async () => {
    const { calls } = mockApi({
      'GET /api/v1/auth/me': me,
      'GET /api/v1/auth/sessions': [],
      'POST /api/v1/auth/change-password': {},
    });

    renderApp(<AccountPage />, '/account');

    fireEvent.change(await screen.findByLabelText('Current password'), { target: { value: 'old passphrase x' } });
    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'a brand new passphrase' } });
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }));

    expect(await screen.findByRole('status')).toHaveTextContent('other sessions are signed out');
    expect(calls.find((c) => c.key === 'POST /api/v1/auth/change-password')?.body).toMatchObject({
      currentPassword: 'old passphrase x',
      newPassword: 'a brand new passphrase',
    });
  });

  test('says what to do when nobody can sign in', async () => {
    mockApi({ 'GET /api/v1/auth/me': me, 'GET /api/v1/auth/sessions': [] });
    renderApp(<AccountPage />, '/account');

    expect(await screen.findByText(/--solid.reset-password=your@email/)).toBeInTheDocument();
  });
});

describe('spec 048: using a reset link', () => {
  function renderReset(path: string, routes: Record<string, unknown> = {}) {
    const mocked = mockApi({ 'POST /api/v1/auth/reset-password': {}, ...routes });
    render(
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/reset-password" element={<ResetPasswordPage />} />
        </Routes>
      </MemoryRouter>,
    );
    return mocked;
  }

  test('sets the password and says MFA is still needed', async () => {
    const { calls } = renderReset('/reset-password?token=abc123');

    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'a brand new passphrase' } });
    fireEvent.click(screen.getByRole('button', { name: 'Set password' }));

    expect(await screen.findByRole('status')).toHaveTextContent('authenticator code');
    expect(calls.find((c) => c.key === 'POST /api/v1/auth/reset-password')?.body)
      .toMatchObject({ token: 'abc123' });
  });

  test('a used link shows the server message rather than pretending it worked', async () => {
    renderReset('/reset-password?token=spent', {
      'POST /api/v1/auth/reset-password': { detail: 'That reset link has already been used', code: 'RESET_USED' },
    });
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        new Response(JSON.stringify({ detail: 'That reset link has already been used', code: 'RESET_USED' }), {
          status: 409,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    );

    fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'a brand new passphrase' } });
    fireEvent.click(screen.getByRole('button', { name: 'Set password' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('already been used');
  });

  test('a link with no token says so', () => {
    renderReset('/reset-password');
    expect(screen.getByRole('alert')).toHaveTextContent('no reset token');
  });
});

describe('spec 051: where you are signed in', () => {
  const sessions = [
    {
      id: 's1', createdAt: '2026-09-21T08:00:00Z', lastSeenAt: '2026-09-21T09:30:00Z', ip: '203.0.113.7',
      userAgent: 'Mozilla/5.0 (Macintosh)', mfaVerified: true, current: true, expiresAt: '2026-09-21T20:00:00Z',
    },
    {
      id: 's2', createdAt: '2026-09-20T08:00:00Z', lastSeenAt: '2026-09-20T18:00:00Z', ip: '198.51.100.4',
      userAgent: 'Mozilla/5.0 (Android)', mfaVerified: true, current: false, expiresAt: '2026-09-21T20:00:00Z',
    },
  ];

  test('lists the sessions, ends one, and signs out everywhere else', async () => {
    const { calls } = mockApi({
      'GET /api/v1/auth/me': me,
      'GET /api/v1/auth/sessions': sessions,
      'DELETE /api/v1/auth/sessions/s2': {},
      'POST /api/v1/auth/sessions/revoke-others': { revoked: 1 },
    });

    renderApp(<AccountPage />, '/account');

    expect(await screen.findByText('203.0.113.7')).toBeInTheDocument();
    expect(screen.getByText('this one')).toBeInTheDocument();

    fireEvent.click(screen.getByLabelText('End the session from 198.51.100.4'));
    expect(calls.some((c) => c.key === 'DELETE /api/v1/auth/sessions/s2')).toBe(true);

    fireEvent.click(screen.getByRole('button', { name: 'Sign out everywhere else' }));
    expect(await screen.findByText(/Ended 1 other session/)).toBeInTheDocument();
  });
});
