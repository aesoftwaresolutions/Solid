import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { mockApi, money } from '../testSupport';
import { BusinessLineSelect, BusinessLinesCard, ProfitAndLossByBusinessLineCard } from './BusinessLines';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const base = '/api/v1/orgs/o1/entities/e1';

const column = (id: string | null, name: string, income: string, net: string, archived = false) => ({
  businessLineId: id, name, archived, income: money(income), costOfGoodsSold: money('0.00'),
  grossProfit: money(income), expenses: money('0.00'), netIncome: money(net),
});

describe('spec 069: business lines', () => {
  test('the report shows one column per line plus shared, and the total', async () => {
    mockApi({
      [`GET ${base}/reports/profit-and-loss-by-business-line`]: {
        from: '2026-01-01', to: '2026-12-31', currency: 'USD',
        columns: [column('a', 'Automation & AI', '2400.00', '1780.00'), column(null, 'Shared / overhead', '250.00', '195.01')],
        rows: [{ accountId: 'x', code: '4010', name: 'Sales and Service Revenue', section: 'income',
          amounts: [money('2400.00'), money('250.00')], total: money('2650.00') }],
        total: column(null, 'Total', '2650.00', '1975.01'),
      },
    });
    render(<ProfitAndLossByBusinessLineCard orgId="o1" entityId="e1" from="2026-01-01" to="2026-12-31" />);

    expect(await screen.findByRole('columnheader', { name: 'Automation & AI' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Shared / overhead' })).toBeInTheDocument();
    const net = screen.getByText('Net income').closest('tr') as HTMLElement;
    expect(within(net).getByText('1,780.00')).toBeInTheDocument();
    expect(within(net).getByText('1,975.01')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Download CSV' }))
      .toHaveAttribute('href', expect.stringContaining('profit-and-loss-by-business-line.csv?from=2026-01-01'));
  });

  test('with only the shared column it explains how to start', async () => {
    mockApi({
      [`GET ${base}/reports/profit-and-loss-by-business-line`]: {
        from: '2026-01-01', to: '2026-12-31', currency: 'USD', rows: [],
        columns: [column(null, 'Shared / overhead', '0.00', '0.00')], total: column(null, 'Total', '0.00', '0.00'),
      },
    });
    render(<ProfitAndLossByBusinessLineCard orgId="o1" entityId="e1" from="2026-01-01" to="2026-12-31" />);
    expect(await screen.findByText(/Everything is shared so far/)).toBeInTheDocument();
  });

  test('settings adds a line and archives one', async () => {
    const { calls } = mockApi({
      [`GET ${base}/business-lines`]: [{ id: 'a', entityId: 'e1', name: 'Automation & AI', isArchived: false }],
      [`POST ${base}/business-lines`]: { id: 'w', entityId: 'e1', name: 'Websites & hosting', isArchived: false },
      [`PATCH ${base}/business-lines/a`]: { id: 'a', entityId: 'e1', name: 'Automation & AI', isArchived: true },
    });
    render(<BusinessLinesCard orgId="o1" entityId="e1" />);

    expect(await screen.findByText('Automation & AI')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('New business line'), 'Websites & hosting');
    await userEvent.click(screen.getByRole('button', { name: 'Add' }));
    await userEvent.click(screen.getByRole('button', { name: 'Archive' }));

    await waitFor(() => {
      expect(calls.find((c) => c.key === `POST ${base}/business-lines`)?.body).toEqual({ name: 'Websites & hosting' });
      expect(calls.find((c) => c.key === `PATCH ${base}/business-lines/a`)?.body).toEqual({ archived: true });
    });
  });

  test('the picker hides archived lines and stays out of the way when there are none', () => {
    const { rerender } = render(
      <BusinessLineSelect
        lines={[
          { id: 'a', entityId: 'e1', name: 'Automation & AI', isArchived: false },
          { id: 'o', entityId: 'e1', name: 'Old facet', isArchived: true },
        ]}
        value=""
        onChange={() => {}}
      />,
    );
    expect(screen.getByRole('option', { name: 'Automation & AI' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'Old facet' })).not.toBeInTheDocument();

    rerender(<BusinessLineSelect lines={[]} value="" onChange={() => {}} />);
    expect(screen.queryByLabelText('Business line')).not.toBeInTheDocument();
  });
});
