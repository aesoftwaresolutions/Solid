import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { mockApi } from '../testSupport';
import ImportPage from './ImportPage';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const base = '/api/v1/orgs/o1/entities/e1';

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/orgs/o1/entities/e1/import']}>
      <Routes>
        <Route path="/orgs/:orgId/entities/:entityId/import" element={<ImportPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

const goodPreview = {
  kind: 'accounts', committed: false, totalRows: 2, created: 2, skipped: 0, problemCount: 0, ready: true,
  ignoredColumns: ['Colour'],
  rows: [
    { line: 2, key: '1000', action: 'create', detail: 'Assets (asset)' },
    { line: 3, key: '1010', action: 'create', detail: 'Checking (asset)' },
  ],
};

const csv = 'code,name,type\n1000,Assets,asset\n1010,Checking,asset\n';

describe('spec 040: importing a list from CSV', () => {
  test('checks the file first, and only then offers to import it', async () => {
    const { calls } = mockApi({
      [`POST ${base}/imports/accounts/preview`]: goodPreview,
      [`POST ${base}/imports/accounts`]: { ...goodPreview, committed: true },
    });

    renderPage();

    // Nothing can be imported before it has been looked at.
    expect(screen.getByRole('button', { name: /^Import/ })).toBeDisabled();

    fireEvent.change(screen.getByLabelText('CSV contents'), { target: { value: csv } });
    fireEvent.click(screen.getByRole('button', { name: 'Check the file' }));

    expect(await screen.findByText(/2 to add, 0 already there/)).toBeInTheDocument();
    expect(screen.getByText(/Nothing has been written yet/)).toBeInTheDocument();
    expect(screen.getByText(/Columns we do not use.*Colour/)).toBeInTheDocument();
    expect(calls.map((c) => c.key)).toEqual([`POST ${base}/imports/accounts/preview`]);

    fireEvent.click(screen.getByRole('button', { name: 'Import 2 row(s)' }));

    expect(await screen.findByText(/Added 2, left 0 alone/)).toBeInTheDocument();
    expect(calls.map((c) => c.key)).toContain(`POST ${base}/imports/accounts`);
  });

  test('a file with a bad row cannot be imported, and the line is named', async () => {
    mockApi({
      [`POST ${base}/imports/accounts/preview`]: {
        ...goodPreview, created: 1, problemCount: 1, ready: false, ignoredColumns: [],
        rows: [
          { line: 2, key: '1000', action: 'create', detail: 'Assets (asset)' },
          { line: 3, key: '1010', action: 'error', detail: "Type 'assset' is not one of asset, liability, equity, income, expense" },
        ],
      },
    });

    renderPage();
    fireEvent.change(screen.getByLabelText('CSV contents'), { target: { value: csv } });
    fireEvent.click(screen.getByRole('button', { name: 'Check the file' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/1 row\(s\) need fixing first/);
    const row = screen.getByText('1010').closest('tr') as HTMLElement;
    expect(within(row).getByText('error')).toBeInTheDocument();
    expect(within(row).getByText(/'assset'/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Import/ })).toBeDisabled();
  });
});
