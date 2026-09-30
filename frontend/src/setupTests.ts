import '@testing-library/jest-dom/vitest';

// Keep async page-level effects inside the mocked response boundary. Some
// routes start a session lookup while another test is unmounting; without a
// default response that late request becomes an unhandled rejection.
const authenticatedUser = {
  authenticated: true,
  userId: 'test-user',
  email: 'tester@example.test',
  roles: ['OWNER'],
};

beforeEach(() => {
  const nativeFetch = globalThis.fetch.bind(globalThis) as typeof fetch;

  vi.stubGlobal('fetch', async (input, init) => {
    const url = typeof input === 'string'
      ? input
      : input instanceof Request
        ? input.url
        : String(input);

    if (/\/api\/v1\/auth\/me(?:[?#].*)?$/.test(url)) {
      return new Response(JSON.stringify(authenticatedUser), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }

    return nativeFetch(input, init);
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
});
