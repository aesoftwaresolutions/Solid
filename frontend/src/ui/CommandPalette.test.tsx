import { fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, test } from 'vitest';
import { CommandPalette } from './CommandPalette';

function Harness() {
  const [open, setOpen] = useState(false);
  return (
    <MemoryRouter initialEntries={['/orgs/o1/entities/e1']}>
      <CommandPalette orgId="o1" entityId="e1" open={open} onOpenChange={setOpen} />
      <Routes>
        <Route path="/orgs/o1/entities/e1/journal" element={<p>journal route</p>} />
      </Routes>
    </MemoryRouter>
  );
}

describe('command palette', () => {
  test('Ctrl K opens it, Ctrl K closes it', () => {
    render(<Harness />);
    expect(screen.queryByRole('dialog')).toBeNull();
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true });
    expect(screen.getByRole('dialog', { name: 'Jump to' })).toBeInTheDocument();
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true });
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  test('Escape closes it', () => {
    render(<Harness />);
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true });
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  test('typing filters the destinations and Enter goes there', () => {
    render(<Harness />);
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true });
    const box = screen.getByRole('textbox', { name: 'Jump to page' });
    fireEvent.change(box, { target: { value: 'jour' } });
    expect(screen.getByRole('option', { name: 'Journal' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'Sales' })).toBeNull();
    fireEvent.keyDown(box, { key: 'Enter' });
    expect(screen.getByText('journal route')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});
