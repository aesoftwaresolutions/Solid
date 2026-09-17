import { render, screen } from '@testing-library/react';
import { afterEach, vi } from 'vitest';
import App from './App';

afterEach(() => vi.restoreAllMocks());

// Spec 001, AC 6
test('shows product name and server info when the API responds', async () => {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    new Response(JSON.stringify({ name: 'Solid', version: '0.1.0', databaseSchemaVersion: '202609160001' }), {
      status: 200,
    }),
  );

  render(<App />);

  expect(screen.getByRole('heading', { name: 'Solid' })).toBeInTheDocument();
  expect(await screen.findByText('0.1.0')).toBeInTheDocument();
  expect(screen.getByText('202609160001')).toBeInTheDocument();
});

test('shows a friendly error when the API is unreachable', async () => {
  vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'));

  render(<App />);

  expect(await screen.findByRole('alert')).toHaveTextContent("Can't reach the Solid server");
});
