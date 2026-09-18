import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { vi } from 'vitest';
import type { ReactElement } from 'react';
import { AuthProvider } from './auth';

/** A tiny fake API: map "METHOD /api/v1/path" to a response body (or a function of the request). */
export type Routes = Record<string, unknown | ((init: RequestInit) => unknown)>;

export function mockApi(routes: Routes, options: { status?: Record<string, number> } = {}) {
  const calls: { key: string; body: unknown }[] = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = typeof input === 'string' ? input : input.toString();
    const method = (init.method ?? 'GET').toUpperCase();
    const key = `${method} ${url.split('?')[0]}`;
    calls.push({ key, body: init.body instanceof FormData ? '[form]' : init.body ? JSON.parse(String(init.body)) : undefined });

    const handler = routes[key] ?? routes[`${method} ${url}`];
    const status = options.status?.[key] ?? (handler === undefined ? 404 : method === 'POST' ? 200 : 200);
    const value = typeof handler === 'function' ? (handler as (i: RequestInit) => unknown)(init) : handler;
    const body = value === undefined ? { detail: `No stub for ${key}`, status } : value;
    return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, calls };
}

export function renderApp(ui: ReactElement, initialPath = '/') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <AuthProvider>{ui}</AuthProvider>
    </MemoryRouter>,
  );
}

export const money = (amount: string) => ({ amount, currency: 'USD' });
