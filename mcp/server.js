#!/usr/bin/env node
/**
 * Solid MCP server — exposes the Solid bookkeeping API to local LLM agents.
 *
 * Stdio transport; point any MCP-compatible client or agent host at this process.
 * Configuration is environment variables:
 *
 *   SOLID_URL        e.g. http://localhost:8080  (server) or http://127.0.0.1:18080 (desktop)
 *   SOLID_EMAIL
 *   SOLID_PASSWORD
 *   SOLID_MFA_SECRET  optional; the base32 TOTP secret from enrollment, so an MFA
 *                     account can sign in without a human typing codes
 *
 * Auth is Solid's own session cookie + XSRF-TOKEN header, exactly what the web UI
 * uses — no separate token system needed.
 */
import { createHmac } from 'node:crypto';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';

const BASE = (process.env.SOLID_URL ?? 'http://127.0.0.1:18080').replace(/\/$/, '');

// ---- session state -----------------------------------------------------------
let cookies = new Map();

function refreshCookies(setCookieHeaders) {
  for (const header of setCookieHeaders) {
    const first = header.split(';', 1)[0];
    const eq = first.indexOf('=');
    if (eq > 0) cookies.set(first.slice(0, eq).trim(), first.slice(eq + 1).trim());
  }
}

/** One API call. Returns { status, body }. Throws only on network failure. */
async function call(method, path, body) {
  const headers = { Accept: 'application/json' };
  if (cookies.size) headers.Cookie = [...cookies].map(([k, v]) => `${k}=${v}`).join('; ');
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (!['GET', 'HEAD'].includes(method) && cookies.has('XSRF-TOKEN')) {
    headers['X-XSRF-TOKEN'] = cookies.get('XSRF-TOKEN');
  }
  const res = await fetch(`${BASE}/api/v1${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    redirect: 'manual',
  });
  refreshCookies(res.headers.getSetCookie?.() ?? []);
  const text = await res.text();
  let parsed;
  try { parsed = text ? JSON.parse(text) : undefined; } catch { parsed = { raw: text }; }
  return { status: res.status, body: parsed };
}

// ---- TOTP (RFC 6238, SHA-1, 30s step — what authenticator apps generate) -------
const B32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
function base32Decode(secret) {
  const clean = secret.replace(/[\s=]/g, '').toUpperCase();
  let bits = 0, value = 0;
  const out = [];
  for (const ch of clean) {
    const idx = B32.indexOf(ch);
    if (idx < 0) throw new Error('SOLID_MFA_SECRET is not valid base32');
    value = (value << 5) | idx; bits += 5;
    if (bits >= 8) { out.push((value >>> (bits - 8)) & 0xff); bits -= 8; }
  }
  return Buffer.from(out);
}
function totp(secret, when = Date.now()) {
  const key = base32Decode(secret);
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(when / 30000)));
  const digest = createHmac('sha1', key).update(counter).digest();
  const offset = digest[digest.length - 1] & 0xf;
  const binary = ((digest[offset] & 0x7f) << 24) | (digest[offset + 1] << 16)
    | (digest[offset + 2] << 8) | digest[offset + 3];
  return String(binary % 1_000_000).padStart(6, '0');
}

// ---- auth --------------------------------------------------------------------
async function login() {
  const { SOLID_EMAIL, SOLID_PASSWORD, SOLID_MFA_SECRET } = process.env;
  if (!SOLID_EMAIL || !SOLID_PASSWORD) {
    throw new Error('Set SOLID_EMAIL and SOLID_PASSWORD (and SOLID_MFA_SECRET if the account has MFA on).');
  }
  const res = await call('POST', '/auth/login', { email: SOLID_EMAIL, password: SOLID_PASSWORD });
  if (res.status !== 200) throw new Error(`Login failed: ${JSON.stringify(res.body)}`);
  if (res.body?.mfaEnrolled) {
    if (!SOLID_MFA_SECRET) throw new Error('Account has MFA; set SOLID_MFA_SECRET to the base32 TOTP secret.');
    const mfa = await call('POST', '/auth/mfa/verify', { code: totp(SOLID_MFA_SECRET) });
    if (mfa.status !== 200 && mfa.status !== 204) {
      throw new Error(`MFA verify failed: ${JSON.stringify(mfa.body)}`);
    }
  }
  return true;
}

let loggedIn = false;
async function ensureLogin() {
  if (!loggedIn) { await login(); loggedIn = true; }
}

/** Runs fn, retrying once after a fresh login if the session expired. */
async function authed(fn) {
  await ensureLogin();
  let res = await fn();
  if (res.status === 401) {
    loggedIn = false;
    await ensureLogin();
    res = await fn();
  }
  return res;
}

const fmt = (res) => ({
  content: [{ type: 'text', text: JSON.stringify(res.body ?? { status: res.status }, null, 2) }],
  isError: res.status >= 400,
});

// ---- server + tools -----------------------------------------------------------
const server = new McpServer({
  name: 'solid',
  version: '0.1.0',
});

const orgEntity = { orgId: z.string().describe('Organization id'), entityId: z.string().describe('Entity id') };

server.registerTool('solid_system_info', {
  description: "Solid version, database schema version, and whether this is a desktop install. No login needed.",
  inputSchema: {},
}, async () => fmt(await call('GET', '/system/info')));

server.registerTool('solid_login_status', {
  description: 'Signs in if needed and reports who you are (email, admin flag).',
  inputSchema: {},
}, async () => fmt(await authed(() => call('GET', '/auth/me'))));

server.registerTool('solid_list_organizations', {
  description: 'All organizations this account belongs to. Start here to get orgId values.',
  inputSchema: {},
}, async () => fmt(await authed(() => call('GET', '/orgs'))));

server.registerTool('solid_list_entities', {
  description: 'Entities (the books-keeping units) inside one organization. Gives you entityId values.',
  inputSchema: { orgId: z.string() },
}, async ({ orgId }) => fmt(await authed(() => call('GET', `/orgs/${orgId}/entities`))));

server.registerTool('solid_list_accounts', {
  description: "Chart of accounts for an entity, including each account's tax-line mapping.",
  inputSchema: { ...orgEntity },
}, async ({ orgId, entityId }) => fmt(await authed(() => call('GET', `/orgs/${orgId}/entities/${entityId}/accounts`))));

server.registerTool('solid_review_queue', {
  description: "Bank transactions still waiting for a category, each with Solid's suggested account when one exists.",
  inputSchema: { ...orgEntity },
}, async ({ orgId, entityId }) =>
  fmt(await authed(() => call('GET', `/orgs/${orgId}/entities/${entityId}/bank-transactions?status=new`))));

server.registerTool('solid_categorize_transaction', {
  description: 'WRITE. Assigns an account to one bank transaction from the review queue and posts the journal entry. Echoes the recorded transaction.',
  inputSchema: {
    ...orgEntity,
    transactionId: z.string(),
    accountId: z.string().describe('Target ledger account id — get ids from solid_list_accounts'),
  },
}, async ({ orgId, entityId, transactionId, accountId }) =>
  fmt(await authed(() =>
    call('POST', `/orgs/${orgId}/entities/${entityId}/bank-transactions/${transactionId}/categorize`, { accountId }))));

server.registerTool('solid_profit_and_loss', {
  description: 'Profit & loss for a date range (inclusive), amounts as decimal strings.',
  inputSchema: { ...orgEntity, from: z.string().describe('YYYY-MM-DD'), to: z.string().describe('YYYY-MM-DD') },
}, async ({ orgId, entityId, from, to }) =>
  fmt(await authed(() => call('GET', `/orgs/${orgId}/entities/${entityId}/reports/profit-and-loss?from=${from}&to=${to}`))));

server.registerTool('solid_balance_sheet', {
  description: 'Balance sheet as of a date, plus whether it balances.',
  inputSchema: { ...orgEntity, asOf: z.string().describe('YYYY-MM-DD') },
}, async ({ orgId, entityId, asOf }) =>
  fmt(await authed(() => call('GET', `/orgs/${orgId}/entities/${entityId}/reports/balance-sheet?asOf=${asOf}`))));

server.registerTool('solid_tax_lines', {
  description: 'Tax-line report (Schedule C mapping) for a year: totals, readiness, and any unmapped accounts.',
  inputSchema: { ...orgEntity, taxYear: z.number().int() },
}, async ({ orgId, entityId, taxYear }) =>
  fmt(await authed(() => call('GET', `/orgs/${orgId}/entities/${entityId}/reports/tax-lines?taxYear=${taxYear}`))));

server.registerTool('solid_estimated_tax', {
  description: 'Quarterly set-aside worksheet: self-employment tax from the books\u2019 profit at statute rates plus the sourced wage base, plus an income-tax leg at a rate the caller supplies. An estimate for setting money aside, not a return.',
  inputSchema: {
    ...orgEntity,
    taxYear: z.number().int(),
    marginalRatePercent: z.number().min(0).max(100).optional()
      .describe('Caller-chosen marginal income-tax rate; omit for SE-tax-only worksheet'),
  },
}, async ({ orgId, entityId, taxYear, marginalRatePercent }) => {
  const query = marginalRatePercent === undefined ? '' : `&marginalRatePercent=${marginalRatePercent}`;
  return fmt(await authed(() =>
    call('GET', `/orgs/${orgId}/entities/${entityId}/reports/estimated-tax?taxYear=${taxYear}${query}`)));
});

server.registerTool('solid_backup_status', {
  description: 'Backup readiness for this installation (instance-admin accounts only).',
  inputSchema: {},
}, async () => fmt(await authed(() => call('GET', '/system/backup-status'))));

server.registerTool('solid_list_customers', {
  description: 'Customers of an entity (billing).',
  inputSchema: { ...orgEntity },
}, async ({ orgId, entityId }) => fmt(await authed(() => call('GET', `/orgs/${orgId}/entities/${entityId}/customers`))));

server.registerTool('solid_create_customer', {
  description: 'WRITE. Creates a customer by name. Echoes the created customer including its id.',
  inputSchema: { ...orgEntity, name: z.string() },
}, async ({ orgId, entityId, name }) =>
  fmt(await authed(() => call('POST', `/orgs/${orgId}/entities/${entityId}/customers`, { name }))));

const money = z.object({
  amount: z.string().describe('Decimal string like "1200.00"; negative for the credit side'),
  currency: z.string().default('USD'),
});

server.registerTool('solid_create_journal_entry', {
  description: 'WRITE. Creates a journal entry. Set post=false for a draft unless you are certain. Lines must sum to zero: positive amounts are debits, negative are credits.',
  inputSchema: {
    ...orgEntity,
    entryDate: z.string().describe('YYYY-MM-DD'),
    post: z.boolean().default(false).describe('Post immediately (true) or save as draft (false)'),
    memo: z.string().optional(),
    lines: z.array(z.object({ accountId: z.string(), amount: money, memo: z.string().optional() }))
      .min(2).describe('At least one debit and one credit; amounts must net to zero'),
  },
}, async ({ orgId, entityId, entryDate, post, memo, lines }) =>
  fmt(await authed(() =>
    call('POST', `/orgs/${orgId}/entities/${entityId}/journal-entries`, { entryDate, post, memo, lines }))));

const transport = new StdioServerTransport();
await server.connect(transport);
console.error(`solid-mcp listening (stdio) — target ${BASE}`);
