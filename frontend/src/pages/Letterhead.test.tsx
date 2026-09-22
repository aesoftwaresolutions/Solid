import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { mockApi } from '../testSupport';
import EntitySettingsPage from './EntitySettingsPage';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const base = '/api/v1/orgs/o1/entities/e1';
const entity = {
  id: 'e1', orgId: 'o1', kind: 'smllc', legalName: 'Zeta Shop LLC', fiscalYearEnd: 12,
  accountingMethod: 'cash', homeState: 'TX', baseCurrency: 'USD',
};
const empty = {
  address: null, phone: null, email: null, website: null, taxId: null, paymentInstructions: null,
  hasLogo: false,
};

function renderSettings(routes: Record<string, unknown>) {
  const mocked = mockApi({
    [`GET ${base}`]: entity,
    [`GET ${base}/journal/verify`]: { valid: true, postedEntries: 3, firstInvalidSeq: null },
    ...routes,
  });
  render(
    <MemoryRouter initialEntries={['/orgs/o1/entities/e1/settings']}>
      <Routes>
        <Route path="/orgs/:orgId/entities/:entityId/settings" element={<EntitySettingsPage />} />
      </Routes>
    </MemoryRouter>,
  );
  return mocked;
}

describe('the letterhead', () => {
  test('what is saved comes back in the form and goes to the API', async () => {
    const { calls } = renderSettings({
      [`GET ${base}/branding`]: { ...empty, address: '12 Example Street', phone: '555-0100' },
      [`PUT ${base}/branding`]: { ...empty, address: '12 Example Street', phone: '555-0199' },
    });

    await waitFor(() => expect(screen.getByLabelText('Your address')).toHaveValue('12 Example Street'));
    fireEvent.change(screen.getByLabelText('Phone'), { target: { value: '555-0199' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save the letterhead' }));

    await screen.findByText('Saved.');
    const saved = calls.find((call) => call.key === `PUT ${base}/branding`);
    expect(saved).toBeDefined();
    expect(saved?.body).toMatchObject({ phone: '555-0199' });
  });

  test('the page says payment instructions stay off quotes, and offers to remove a logo', async () => {
    const { calls } = renderSettings({
      [`GET ${base}/branding`]: { ...empty, hasLogo: true },
      [`DELETE ${base}/branding/logo`]: {},
    });

    expect(await screen.findByText(/never carries payment instructions/)).toBeInTheDocument();
    fireEvent.click(await screen.findByRole('button', { name: 'Remove the logo' }));
    await waitFor(() =>
      expect(calls.some((call) => call.key === `DELETE ${base}/branding/logo`)).toBe(true),
    );
  });
});
