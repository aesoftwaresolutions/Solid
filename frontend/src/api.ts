/** Typed wrapper around the Solid API. Auth is the HttpOnly session cookie + CSRF header. */

export interface Money {
  amount: string;
  currency: string;
}

export interface SystemInfo {
  name: string;
  version: string;
  databaseSchemaVersion: string;
}

export interface User {
  id: string;
  email: string;
  displayName: string;
  mfaEnabled: boolean;
  isInstanceAdmin: boolean;
}

export interface Me {
  user: User;
  mfaVerified: boolean;
  organizationIds: string[];
}

export interface Organization {
  id: string;
  name: string;
  kind: string;
}

export interface Entity {
  id: string;
  orgId: string;
  kind: string;
  legalName: string;
  fiscalYearEnd: number;
  accountingMethod: string;
  baseCurrency: string;
}

export interface Account {
  id: string;
  code: string;
  name: string;
  type: string;
  subtype: string | null;
  parentId: string | null;
  isHeader: boolean;
  taxLineCode: string | null;
  isArchived: boolean;
}

export interface BankAccount {
  id: string;
  glAccountId: string;
  name: string;
  institution: string | null;
  mask: string | null;
}

export interface BankTransaction {
  id: string;
  bankAccountId: string;
  postedDate: string;
  amount: Money;
  description: string;
  status: string;
  suggestedAccountId: string | null;
  suggestionSource: string | null;
  journalEntryId: string | null;
}

export interface ImportResult {
  batchId: string;
  format: string;
  parsed: number;
  imported: number;
  duplicates: number;
}

export interface Section {
  rows: { accountId: string | null; code: string | null; name: string; amount: Money }[];
  total: Money;
}

export interface ProfitAndLoss {
  from: string;
  to: string;
  income: Section;
  costOfGoodsSold: Section;
  grossProfit: Money;
  expenses: Section;
  netIncome: Money;
}

export interface BalanceSheet {
  asOf: string;
  assets: Section;
  liabilities: Section;
  equity: Section;
  totalLiabilitiesAndEquity: Money;
  balanced: boolean;
}

export interface TrialBalance {
  asOf: string;
  rows: { accountId: string; code: string; name: string; type: string; debit: Money; credit: Money }[];
  totalDebit: Money;
  totalCredit: Money;
}

export interface TaxLineReport {
  taxYear: number;
  from: string;
  to: string;
  lines: {
    code: string;
    line: string;
    label: string;
    kind: string;
    amount: Money;
    accounts: { accountId: string; code: string; name: string; amount: Money }[];
  }[];
  unmapped: { accountId: string; code: string; name: string; type: string; amount: Money }[];
  totals: { income: Money; costOfGoodsSold: Money; expenses: Money; netProfit: Money };
  readiness: {
    draftEntries: number;
    uncategorizedBankTransactions: number;
    unmappedAccounts: number;
    ready: boolean;
  };
}

/** An API error carrying the server's problem-details code so screens can react to specific cases. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string | undefined;

  constructor(status: number, message: string, code?: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

function csrfToken(): string | null {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : null;
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase();
  const headers = new Headers(init.headers);
  if (init.body !== undefined && !(init.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json');
  }
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    const token = csrfToken();
    if (token) {
      headers.set('X-XSRF-TOKEN', token);
    }
  }

  const response = await fetch(`/api/v1${path}`, { ...init, method, headers, credentials: 'same-origin' });
  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  const body = text ? JSON.parse(text) : undefined;
  if (!response.ok) {
    throw new ApiError(response.status, body?.detail ?? body?.title ?? `Request failed (${response.status})`, body?.code);
  }
  return body as T;
}

const json = (body: unknown): RequestInit => ({ body: JSON.stringify(body) });

export const api = {
  systemInfo: () => request<SystemInfo>('/system/info'),
  me: () => request<Me>('/auth/me'),
  login: (email: string, password: string) =>
    request<{ mfaEnrolled: boolean }>('/auth/login', { method: 'POST', ...json({ email, password }) }),
  signup: (email: string, password: string, displayName: string) =>
    request<User>('/auth/signup', { method: 'POST', ...json({ email, password, displayName }) }),
  enrollMfa: () => request<{ secret: string; otpauthUri: string }>('/auth/mfa/enroll', { method: 'POST' }),
  activateMfa: (code: string) =>
    request<{ recoveryCodes: string[] }>('/auth/mfa/activate', { method: 'POST', ...json({ code }) }),
  verifyMfa: (code: string, recoveryCode?: string) =>
    request<void>('/auth/mfa/verify', { method: 'POST', ...json(recoveryCode ? { recoveryCode } : { code }) }),
  logout: () => request<void>('/auth/logout', { method: 'POST' }),

  organizations: () => request<Organization[]>('/orgs'),
  createOrganization: (name: string, kind: string) =>
    request<Organization>('/orgs', { method: 'POST', ...json({ name, kind }) }),

  entities: (orgId: string) => request<Entity[]>(`/orgs/${orgId}/entities`),
  createEntity: (orgId: string, kind: string, legalName: string) =>
    request<Entity>(`/orgs/${orgId}/entities`, { method: 'POST', ...json({ kind, legalName }) }),

  accounts: (orgId: string, entityId: string) =>
    request<Account[]>(`/orgs/${orgId}/entities/${entityId}/accounts`),
  applyTemplate: (orgId: string, entityId: string, template: string) =>
    request<Account[]>(`/orgs/${orgId}/entities/${entityId}/accounts/apply-template`, {
      method: 'POST',
      ...json({ template }),
    }),

  bankAccounts: (orgId: string, entityId: string) =>
    request<BankAccount[]>(`/orgs/${orgId}/entities/${entityId}/bank-accounts`),
  createBankAccount: (orgId: string, entityId: string, name: string, glAccountId: string) =>
    request<BankAccount>(`/orgs/${orgId}/entities/${entityId}/bank-accounts`, {
      method: 'POST',
      ...json({ name, glAccountId }),
    }),
  importStatement: (orgId: string, entityId: string, bankAccountId: string, file: File) => {
    const form = new FormData();
    form.append('file', file);
    return request<ImportResult>(`/orgs/${orgId}/entities/${entityId}/bank-accounts/${bankAccountId}/imports`, {
      method: 'POST',
      body: form,
    });
  },
  bankTransactions: (orgId: string, entityId: string, status = 'new') =>
    request<BankTransaction[]>(`/orgs/${orgId}/entities/${entityId}/bank-transactions?status=${status}`),
  categorize: (orgId: string, entityId: string, txnId: string, accountId: string) =>
    request<BankTransaction>(`/orgs/${orgId}/entities/${entityId}/bank-transactions/${txnId}/categorize`, {
      method: 'POST',
      ...json({ accountId }),
    }),
  excludeTransaction: (orgId: string, entityId: string, txnId: string) =>
    request<BankTransaction>(`/orgs/${orgId}/entities/${entityId}/bank-transactions/${txnId}/exclude`, {
      method: 'POST',
      ...json({}),
    }),

  profitAndLoss: (orgId: string, entityId: string, from: string, to: string) =>
    request<ProfitAndLoss>(`/orgs/${orgId}/entities/${entityId}/reports/profit-and-loss?from=${from}&to=${to}`),
  balanceSheet: (orgId: string, entityId: string, asOf: string) =>
    request<BalanceSheet>(`/orgs/${orgId}/entities/${entityId}/reports/balance-sheet?asOf=${asOf}`),
  trialBalance: (orgId: string, entityId: string, asOf: string) =>
    request<TrialBalance>(`/orgs/${orgId}/entities/${entityId}/reports/trial-balance?asOf=${asOf}`),
  taxLines: (orgId: string, entityId: string, taxYear: number) =>
    request<TaxLineReport>(`/orgs/${orgId}/entities/${entityId}/reports/tax-lines?taxYear=${taxYear}`),
  taxLinesCsvUrl: (orgId: string, entityId: string, taxYear: number) =>
    `/api/v1/orgs/${orgId}/entities/${entityId}/reports/tax-lines.csv?taxYear=${taxYear}`,
};

/** Formats an API money value for display; negatives use accounting parentheses. */
export function formatMoney(money: Money | undefined): string {
  if (!money) {
    return '';
  }
  const negative = money.amount.startsWith('-');
  const digits = negative ? money.amount.slice(1) : money.amount;
  const [whole, fraction] = digits.split('.');
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const text = fraction ? `${grouped}.${fraction}` : grouped;
  return negative ? `(${text})` : text;
}
