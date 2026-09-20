import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, test, vi } from 'vitest';
import type { ReactElement } from 'react';
import { mockApi, money } from '../testSupport';
import AssetsPage from './AssetsPage';
import DeductionsPage from './DeductionsPage';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const base = '/api/v1/orgs/o1/entities/e1';
const year = new Date().getFullYear();

const accounts = [
  { id: 'a-1500', code: '1500', name: 'Equipment', type: 'asset', subtype: 'fixed_asset', parentId: null, isHeader: false, taxLineCode: null, isArchived: false },
  { id: 'a-1510', code: '1510', name: 'Accumulated Depreciation', type: 'asset', subtype: 'contra_asset', parentId: null, isHeader: false, taxLineCode: null, isArchived: false },
  { id: 'a-6050', code: '6050', name: 'Depreciation', type: 'expense', subtype: null, parentId: null, isHeader: false, taxLineCode: null, isArchived: false },
];

const asset = {
  id: 'as1', name: 'Laptop', description: null, category: null, placedInServiceDate: '2026-01-01',
  cost: money('1200.00'), salvageValue: money('0.00'), usefulLifeMonths: 36, method: 'straight_line',
  status: 'in_service', disposalDate: null, accumulatedDepreciation: money('99.99'),
  netBookValue: money('1100.01'),
  monthlySchedule: [
    { month: '2026-01-01', amount: money('33.33'), posted: true },
    { month: '2026-02-01', amount: money('33.33'), posted: true },
  ],
};

const fixedAssetReport = {
  asOf: '2026-09-18', currency: 'USD', assets: [], totalCost: money('1200.00'),
  totalAccumulated: money('99.99'), totalNetBookValue: money('1100.01'),
  taxNote: 'These figures are book depreciation (straight-line). Tax depreciation is not calculated yet.',
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

describe('spec 035 AC 1-4: fixed assets', () => {
  test('lists assets with the book-depreciation note, runs depreciation and disposes', async () => {
    const user = userEvent.setup();
    const { calls } = mockApi({
      [`GET ${base}/accounts`]: accounts,
      [`GET ${base}/assets`]: [asset],
      [`GET ${base}/reports/fixed-assets`]: fixedAssetReport,
      [`POST ${base}/depreciation-runs`]: {
        months: [{ month: '2026-03-01', amount: money('33.33'), journalEntryId: 'j1' }],
        totalPosted: money('33.33'), skippedMonths: [], skippedReason: null,
      },
      [`POST ${base}/assets/as1/dispose`]: { ...asset, status: 'disposed', disposalDate: '2026-09-01' },
    });

    renderAt('/orgs/o1/entities/e1/assets', '/orgs/:orgId/entities/:entityId/assets', <AssetsPage />);

    expect(await screen.findByText('Laptop')).toBeInTheDocument();
    expect(screen.getByText('1,100.01')).toBeInTheDocument();
    expect(await screen.findByText(/book depreciation \(straight-line\)/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Schedule' }));
    expect(await screen.findByText('2026-02-01')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Run now' }));
    expect(await screen.findByText(/Posted 1 month\(s\), 33.33 in total/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Dispose' }));
    await user.type(await screen.findByLabelText('Proceeds'), '400.00');
    await user.click(screen.getByRole('button', { name: 'Record disposal' }));

    await waitFor(() => expect(calls.some((c) => c.key === `POST ${base}/assets/as1/dispose`)).toBe(true));
    expect(calls.find((c) => c.key === `POST ${base}/assets/as1/dispose`)?.body).toMatchObject({
      proceeds: { amount: '400.00', currency: 'USD' },
      gainLossAccountId: 'a-6050',
    });
  });
});

describe('spec 035 AC 5-7: deductions', () => {
  const vehicles = [{ id: 'v1', name: 'Van', description: null, inServiceDate: null, isArchived: false }];
  const trips = [{
    id: 't1', vehicleId: 'v1', tripDate: `${year}-03-02`, miles: '42.5', category: 'business',
    purpose: 'Client visit', startLocation: null, endLocation: null,
  }];

  const mileage = (rateKnown: boolean) => ({
    taxYear: year, rateKnown, ratePerMile: rateKnown ? '0.70' : null,
    businessMiles: '42.5', commutingMiles: '0', personalMiles: '0', otherMiles: '0',
    estimatedDeduction: rateKnown ? money('29.75') : null,
    source: rateKnown ? 'IRS Notice for the year; verify before filing.' : null,
    note: 'Mileage is an estimate; your preparer decides what is deductible.',
    byVehicle: [{ vehicleId: 'v1', vehicleName: 'Van', businessMiles: '42.5', totalMiles: '42.5' }],
  });

  test('logs a trip and shows the estimated deduction with its source', async () => {
    const user = userEvent.setup();
    const { calls } = mockApi(
      {
        [`GET ${base}/vehicles`]: vehicles,
        [`GET ${base}/mileage-trips`]: trips,
        [`GET ${base}/reports/mileage`]: mileage(true),
        [`GET ${base}/reports/home-office`]: { detail: 'No home office declared', status: 404 },
        [`POST ${base}/mileage-trips`]: trips[0],
      },
      { status: { [`GET ${base}/reports/home-office`]: 404 } },
    );

    renderAt('/orgs/o1/entities/e1/deductions', '/orgs/:orgId/entities/:entityId/deductions', <DeductionsPage />);

    expect(await screen.findByText('Client visit')).toBeInTheDocument();
    expect(await screen.findByText('29.75')).toBeInTheDocument();
    expect(screen.getByText(/At 0.70 per mile/)).toBeInTheDocument();
    expect(screen.getByText(/Mileage is an estimate/)).toBeInTheDocument();
    expect(screen.getByText(`No home office declared for ${year} yet.`)).toBeInTheDocument();

    await user.type(screen.getByLabelText('Miles'), '12.5');
    await user.click(screen.getByRole('button', { name: 'Log trip' }));
    await waitFor(() => expect(calls.some((c) => c.key === `POST ${base}/mileage-trips`)).toBe(true));
    expect(calls.find((c) => c.key === `POST ${base}/mileage-trips`)?.body).toMatchObject({
      vehicleId: 'v1',
      miles: '12.5',
      category: 'business',
    });
  });

  test('with no rate on file it shows the miles and no deduction figure', async () => {
    mockApi(
      {
        [`GET ${base}/vehicles`]: vehicles,
        [`GET ${base}/mileage-trips`]: trips,
        [`GET ${base}/reports/mileage`]: mileage(false),
        [`GET ${base}/reports/home-office`]: { detail: 'No home office declared', status: 404 },
      },
      { status: { [`GET ${base}/reports/home-office`]: 404 } },
    );

    renderAt('/orgs/o1/entities/e1/deductions', '/orgs/:orgId/entities/:entityId/deductions', <DeductionsPage />);

    expect(await screen.findByText(/standard mileage rate for .* is not on file/)).toBeInTheDocument();
    expect(screen.getAllByText('42.5').length).toBeGreaterThan(0);
    expect(screen.queryByText('Estimated deduction')).not.toBeInTheDocument();
    expect(screen.queryByText('0.00')).not.toBeInTheDocument();
  });

  test('saves a home-office declaration and shows the reported deduction', async () => {
    const user = userEvent.setup();
    let declared = false;
    const { calls } = mockApi(
      {
        [`GET ${base}/vehicles`]: vehicles,
        [`GET ${base}/mileage-trips`]: trips,
        [`GET ${base}/reports/mileage`]: mileage(true),
        [`GET ${base}/reports/home-office`]: () =>
          declared
            ? {
                taxYear: year, method: 'simplified', officeSquareFeet: 120, countedSquareFeet: 120,
                maximumSquareFeet: 300, ratePerSquareFoot: '5.00', businessUsePercent: '10.00', monthsUsed: 12,
                deduction: money('600.00'), source: 'Rev. Proc. 2013-13 via IRS Publication 587.',
                note: 'An estimate under the simplified method; your preparer decides.',
              }
            : { detail: 'No home office declared', status: 404 },
        [`PUT ${base}/home-office`]: () => {
          declared = true;
          return { taxYear: year, method: 'simplified', totalHomeSquareFeet: 1200, officeSquareFeet: 120, monthsUsed: 12 };
        },
      },
      { status: { [`GET ${base}/reports/home-office`]: 404 } },
    );

    renderAt('/orgs/o1/entities/e1/deductions', '/orgs/:orgId/entities/:entityId/deductions', <DeductionsPage />);

    await user.type(await screen.findByLabelText('Home square feet'), '1200');
    await user.type(screen.getByLabelText('Office square feet'), '120');
    await user.click(screen.getByRole('button', { name: 'Save declaration' }));

    expect(calls.find((c) => c.key === `PUT ${base}/home-office`)?.body).toEqual({
      method: 'simplified',
      totalHomeSquareFeet: 1200,
      officeSquareFeet: 120,
      monthsUsed: 12,
    });
  });
});
