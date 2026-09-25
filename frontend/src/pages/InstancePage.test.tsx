import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { mockApi, renderApp } from '../testSupport';
import InstancePage from './InstancePage';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const status = {
  instanceId: '11111111-2222-3333-4444-555555555555',
  keyFingerprint: 'a1b2c3d4e5f60718',
  schemaVersion: '202609180003',
  appVersion: '0.1.0',
  databaseBytes: 12 * 1024 * 1024,
  tableEstimates: { 'gl.journal_entry': 42, 'doc.document': 3 },
  documents: { count: 3, bytes: 2 * 1024 * 1024 },
  documentsRoot: '/var/lib/solid/documents',
  checkedAt: '2026-09-18T10:00:00Z',
};

const packs = [
  {
    id: 'standard-mileage-rates', title: 'Standard mileage rate', source: 'IRS annual notices — verify each year.',
    taxYears: [2023, 2024, 2025], appliesFromTaxYear: null, todos: ['2026 not announced yet'],
  },
];

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/instance']}>
      <Routes>
        <Route path="/instance" element={<InstancePage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('spec 027: instance admin area', () => {
  test('shows version, backup readiness and the rule packs', async () => {
    mockApi({
      'GET /api/v1/instance/users': [],
      'GET /api/v1/system/info': { name: 'Solid', version: '0.1.0', databaseSchemaVersion: '202609180003' },
      'GET /api/v1/system/backup-status': status,
      'GET /api/v1/tax/rule-packs': packs,
    });

    renderPage();

    expect(await screen.findByText('Solid 0.1.0')).toBeInTheDocument();
    expect(screen.getByText('Database schema 202609180003')).toBeInTheDocument();

    expect(await screen.findByText('a1b2c3d4e5f60718')).toBeInTheDocument();
    expect(screen.getByText('12.0 MB')).toHaveAttribute('title', `${12 * 1024 * 1024} bytes`);
    expect(screen.getByText('3 file(s), 2.0 MB')).toBeInTheDocument();
    expect(screen.getByText(/same fingerprint/)).toBeInTheDocument();
    expect(screen.getByText('gl.journal_entry')).toBeInTheDocument();

    expect(await screen.findByText('Standard mileage rate')).toBeInTheDocument();
    expect(screen.getByText('IRS annual notices — verify each year.')).toBeInTheDocument();
    expect(screen.getByText('2026 not announced yet')).toBeInTheDocument();
  });

  test('a non-administrator gets an explanation and the rest of the page', async () => {
    mockApi(
      {
        'GET /api/v1/instance/users': [],
      'GET /api/v1/system/info': { name: 'Solid', version: '0.1.0', databaseSchemaVersion: '202609180003' },
        'GET /api/v1/system/backup-status': { detail: 'Only an instance administrator can see the backup status', status: 403 },
        'GET /api/v1/tax/rule-packs': packs,
      },
      { status: { 'GET /api/v1/system/backup-status': 403 } },
    );

    renderPage();

    expect(await screen.findByText(/Only an instance administrator can see the backup status/)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(await screen.findByText('Standard mileage rate')).toBeInTheDocument();
  });
});

describe('spec 049: tax figures added on this installation', () => {
  test('adds a figure with its source and lists what is in use', async () => {
    const { calls } = mockApi({
      'GET /api/v1/system/info': { name: 'Solid', version: '0.1.0', databaseSchemaVersion: '202609210003' },
      'GET /api/v1/instance/backup-status': { detail: 'nope' },
      'GET /api/v1/tax-rules': [],
      'GET /api/v1/instance/users': [],
      'GET /api/v1/instance/tax-figures': [
        {
          id: 'f1', key: 'mileage_rate_per_mile', taxYear: 2026, value: '0.72',
          unit: 'US dollars per mile', source: 'IRS Notice 2026-03, standard mileage rates for 2026.',
          note: null, addedAt: '2026-01-05T09:00:00Z', addedBy: 'u1', supersededAt: null, inUse: true,
        },
      ],
      'GET /api/v1/instance/tax-figures/keys': [
        { key: 'mileage_rate_per_mile', unit: 'US dollars per mile', decimalPlaces: 3 },
      ],
      'POST /api/v1/instance/tax-figures': { id: 'f2' },
    }, { status: { 'GET /api/v1/instance/backup-status': 403 } });

    renderApp(<InstancePage />, '/instance');

    expect(await screen.findByText('Tax figures added here')).toBeInTheDocument();
    expect(screen.getByText(/does not check that a figure is right/)).toBeInTheDocument();
    expect(screen.getByText('mileage_rate_per_mile')).toBeInTheDocument();
    expect(screen.getByText('in use')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/^Tax year/), { target: { value: '2027' } });
    fireEvent.change(screen.getByLabelText(/^Value/), { target: { value: '0.75' } });
    fireEvent.change(screen.getByLabelText(/Where it comes from/), {
      target: { value: 'IRS Notice 2027-04, standard mileage rates for 2027, read on irs.gov.' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Add figure' }));

    expect(calls.find((c) => c.key === 'POST /api/v1/instance/tax-figures')?.body).toMatchObject({
      key: 'mileage_rate_per_mile',
      taxYear: 2027,
      value: '0.75',
    });
  });
});

describe('spec 065: fourth review', () => {
  test('row 19: a password-reset link can be taken off the screen once it is copied', async () => {
    mockApi({
      'GET /api/v1/instance/users': [
        { id: 'u2', email: 'someone@example.test', displayName: 'Someone', mfaEnabled: true, isInstanceAdmin: false },
      ],
      'GET /api/v1/system/info': { name: 'Solid', version: '0.1.0', databaseSchemaVersion: '202609180003' },
      'GET /api/v1/system/backup-status': status,
      'GET /api/v1/tax/rule-packs': packs,
      'POST /api/v1/instance/users/u2/password-reset': { email: 'someone@example.test', resetPath: '/reset-password?token=t' },
    });

    renderPage();
    fireEvent.click(await screen.findByLabelText('Reset password for someone@example.test'));
    expect(await screen.findByLabelText('Reset link')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Done — hide it' }));
    expect(screen.queryByLabelText('Reset link')).not.toBeInTheDocument();
  });
});
