import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, test } from 'vitest';
import { ENTITY_NAV } from './navigation';
import { Sidebar } from './Sidebar';

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Sidebar orgId="o1" entityId="e1" open={false} />
    </MemoryRouter>,
  );
}

describe('sidebar', () => {
  test('lists every page in navigation.ts, pointing at this entity', () => {
    renderAt('/orgs/o1/entities/e1');
    for (const item of ENTITY_NAV.flatMap((group) => group.items)) {
      const expected = item.path ? `/orgs/o1/entities/e1/${item.path}` : '/orgs/o1/entities/e1';
      expect(screen.getByRole('link', { name: item.label })).toHaveAttribute('href', expected);
    }
    expect(screen.getByRole('link', { name: 'All entities' })).toHaveAttribute('href', '/orgs/o1');
  });

  test('marks only the current page, and the dashboard only on its own address', () => {
    renderAt('/orgs/o1/entities/e1/bank');
    expect(screen.getByRole('link', { name: 'Bank' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: 'Dashboard' })).not.toHaveAttribute('aria-current');
  });
});
