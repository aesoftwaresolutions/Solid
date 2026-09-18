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

export interface Customer {
  id: string;
  name: string;
  email: string | null;
  phone: string | null;
  isArchived: boolean;
}

export interface InvoiceLine {
  id: string;
  lineNo: number;
  description: string;
  quantity: string;
  unitPrice: Money;
  amount: Money;
  incomeAccountId: string;
}

export interface Invoice {
  id: string;
  customerId: string;
  invoiceNumber: string | null;
  issueDate: string;
  dueDate: string;
  terms: string;
  memo: string | null;
  total: Money;
  amountPaid: Money;
  balanceDue: Money;
  status: string;
  lines: InvoiceLine[];
}

export interface Vendor {
  id: string;
  name: string;
  email: string | null;
  taxIdLast4: string | null;
  taxClassification: string | null;
  is1099Vendor: boolean;
  defaultExpenseAccountId: string | null;
  isArchived: boolean;
}

export interface BillLine {
  id: string;
  lineNo: number;
  description: string;
  amount: Money;
  expenseAccountId: string;
}

export interface Bill {
  id: string;
  vendorId: string;
  vendorReference: string | null;
  billDate: string;
  dueDate: string;
  terms: string;
  memo: string | null;
  total: Money;
  amountPaid: Money;
  balanceDue: Money;
  status: string;
  lines: BillLine[];
}

export interface AgingBucket {
  customerId: string;
  customerName: string;
  current: Money;
  days1to30: Money;
  days31to60: Money;
  days61to90: Money;
  days90plus: Money;
  total: Money;
}

export interface AgingReport {
  asOf: string;
  currency: string;
  customers: AgingBucket[];
  totals: AgingBucket;
}

export interface Form1099Report {
  taxYear: number;
  thresholdKnown: boolean;
  threshold: Money | null;
  thresholdSource: string | null;
  note: string;
  vendors: {
    vendorId: string;
    vendorName: string;
    paidInYear: Money;
    meetsThreshold: boolean;
    missingInformation: string[];
  }[];
}

export interface DocumentLink {
  objectType: string;
  objectId: string;
}

export interface StoredDocument {
  id: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
  sha256: string;
  kind: string;
  note: string | null;
  uploadedAt: string;
  links: DocumentLink[];
}

export interface BudgetLine {
  accountId: string;
  code: string;
  name: string;
  type: string;
  amount: Money;
}

export interface Budget {
  id: string;
  periodMonth: string;
  currency: string;
  lines: BudgetLine[];
  budgetedIncome: Money;
  budgetedExpenses: Money;
  budgetedNet: Money;
}

export interface ComparisonRow {
  accountId: string;
  code: string;
  name: string;
  budget: Money;
  actual: Money;
  variance: Money;
  overBudget: boolean;
}

export interface BudgetVsActual {
  periodMonth: string;
  currency: string;
  income: ComparisonRow[];
  expenses: ComparisonRow[];
  totals: {
    budgetedIncome: Money;
    actualIncome: Money;
    budgetedExpenses: Money;
    actualExpenses: Money;
    budgetedNet: Money;
    actualNet: Money;
  };
}

export interface JournalLine {
  lineNo: number;
  accountId: string;
  amount: Money;
  memo: string | null;
}

export interface JournalEntry {
  id: string;
  entryDate: string;
  memo: string | null;
  source: string;
  status: 'draft' | 'posted';
  reversesEntryId: string | null;
  postingSeq: number | null;
  lines: JournalLine[];
}

export interface CategorizationRule {
  id: string;
  entityId: string;
  contains: string;
  accountId: string;
  priority: number;
}

export interface TaxRuleCoverage {
  taxYear: number;
  packs: { id: string; title: string; covered: boolean; reason: string; source: string; todos: string[] }[];
  covered: number;
  missing: number;
  note: string;
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

  customers: (orgId: string, entityId: string) =>
    request<Customer[]>(`/orgs/${orgId}/entities/${entityId}/customers`),
  createCustomer: (orgId: string, entityId: string, name: string, email?: string) =>
    request<Customer>(`/orgs/${orgId}/entities/${entityId}/customers`, { method: 'POST', ...json({ name, email }) }),
  invoices: (orgId: string, entityId: string, status?: string) =>
    request<Invoice[]>(`/orgs/${orgId}/entities/${entityId}/invoices${status ? `?status=${status}` : ''}`),
  createInvoice: (
    orgId: string,
    entityId: string,
    body: {
      customerId: string;
      issueDate: string;
      terms: string;
      memo?: string;
      lines: { description: string; quantity: string; unitPrice: Money; incomeAccountId: string }[];
    },
  ) => request<Invoice>(`/orgs/${orgId}/entities/${entityId}/invoices`, { method: 'POST', ...json(body) }),
  finalizeInvoice: (orgId: string, entityId: string, invoiceId: string) =>
    request<Invoice>(`/orgs/${orgId}/entities/${entityId}/invoices/${invoiceId}/finalize`, { method: 'POST' }),
  voidInvoice: (orgId: string, entityId: string, invoiceId: string) =>
    request<Invoice>(`/orgs/${orgId}/entities/${entityId}/invoices/${invoiceId}/void`, { method: 'POST' }),
  recordPayment: (
    orgId: string,
    entityId: string,
    body: {
      customerId: string;
      receivedDate: string;
      depositAccountId: string;
      method?: string;
      reference?: string;
      applications: { invoiceId: string; amount: Money }[];
    },
  ) => request<unknown>(`/orgs/${orgId}/entities/${entityId}/payments`, { method: 'POST', ...json(body) }),
  arAging: (orgId: string, entityId: string, asOf: string) =>
    request<AgingReport>(`/orgs/${orgId}/entities/${entityId}/reports/accounts-receivable-aging?asOf=${asOf}`),

  vendors: (orgId: string, entityId: string) => request<Vendor[]>(`/orgs/${orgId}/entities/${entityId}/vendors`),
  createVendor: (
    orgId: string,
    entityId: string,
    body: { name: string; email?: string; is1099Vendor?: boolean; taxIdLast4?: string; taxClassification?: string },
  ) => request<Vendor>(`/orgs/${orgId}/entities/${entityId}/vendors`, { method: 'POST', ...json(body) }),
  bills: (orgId: string, entityId: string, status?: string) =>
    request<Bill[]>(`/orgs/${orgId}/entities/${entityId}/bills${status ? `?status=${status}` : ''}`),
  createBill: (
    orgId: string,
    entityId: string,
    body: {
      vendorId: string;
      billDate: string;
      terms: string;
      vendorReference?: string;
      memo?: string;
      lines: { description: string; amount: Money; expenseAccountId: string }[];
    },
  ) => request<Bill>(`/orgs/${orgId}/entities/${entityId}/bills`, { method: 'POST', ...json(body) }),
  approveBill: (orgId: string, entityId: string, billId: string) =>
    request<Bill>(`/orgs/${orgId}/entities/${entityId}/bills/${billId}/approve`, { method: 'POST' }),
  voidBill: (orgId: string, entityId: string, billId: string) =>
    request<Bill>(`/orgs/${orgId}/entities/${entityId}/bills/${billId}/void`, { method: 'POST' }),
  payBills: (
    orgId: string,
    entityId: string,
    body: {
      vendorId: string;
      paidDate: string;
      paymentAccountId: string;
      method?: string;
      reference?: string;
      applications: { billId: string; amount: Money }[];
    },
  ) => request<unknown>(`/orgs/${orgId}/entities/${entityId}/bill-payments`, { method: 'POST', ...json(body) }),
  apAging: (orgId: string, entityId: string, asOf: string) =>
    request<AgingReport>(`/orgs/${orgId}/entities/${entityId}/reports/accounts-payable-aging?asOf=${asOf}`),
  form1099Candidates: (orgId: string, entityId: string, taxYear: number) =>
    request<Form1099Report>(`/orgs/${orgId}/entities/${entityId}/reports/form-1099-candidates?taxYear=${taxYear}`),

  documents: (orgId: string, entityId: string, kind?: string) =>
    request<StoredDocument[]>(`/orgs/${orgId}/entities/${entityId}/documents${kind ? `?kind=${kind}` : ''}`),
  uploadDocument: (orgId: string, entityId: string, file: File, kind: string, note?: string) => {
    const form = new FormData();
    form.append('file', file);
    const query = `?kind=${encodeURIComponent(kind)}${note ? `&note=${encodeURIComponent(note)}` : ''}`;
    return request<StoredDocument>(`/orgs/${orgId}/entities/${entityId}/documents${query}`, {
      method: 'POST',
      body: form,
    });
  },
  linkDocument: (orgId: string, entityId: string, documentId: string, objectType: string, objectId: string) =>
    request<StoredDocument>(`/orgs/${orgId}/entities/${entityId}/documents/${documentId}/links`, {
      method: 'POST',
      ...json({ objectType, objectId }),
    }),
  unlinkDocument: (orgId: string, entityId: string, documentId: string, objectType: string, objectId: string) =>
    request<StoredDocument>(
      `/orgs/${orgId}/entities/${entityId}/documents/${documentId}/links/${objectType}/${objectId}`,
      { method: 'DELETE' },
    ),
  deleteDocument: (orgId: string, entityId: string, documentId: string) =>
    request<void>(`/orgs/${orgId}/entities/${entityId}/documents/${documentId}`, { method: 'DELETE' }),
  documentContentUrl: (orgId: string, entityId: string, documentId: string) =>
    `/api/v1/orgs/${orgId}/entities/${entityId}/documents/${documentId}/content`,

  budget: (orgId: string, entityId: string, month: string) =>
    request<Budget>(`/orgs/${orgId}/entities/${entityId}/budgets/${month}`),
  saveBudget: (orgId: string, entityId: string, month: string, lines: { accountId: string; amount: Money }[]) =>
    request<Budget>(`/orgs/${orgId}/entities/${entityId}/budgets/${month}`, { method: 'PUT', ...json({ lines }) }),
  budgetVsActual: (orgId: string, entityId: string, month: string) =>
    request<BudgetVsActual>(`/orgs/${orgId}/entities/${entityId}/reports/budget-vs-actual?month=${month}`),

  journalEntries: (orgId: string, entityId: string, from: string, to: string) =>
    request<JournalEntry[]>(`/orgs/${orgId}/entities/${entityId}/journal-entries?from=${from}&to=${to}`),
  createJournalEntry: (
    orgId: string,
    entityId: string,
    body: { entryDate: string; memo?: string; post: boolean; lines: { accountId: string; amount: Money; memo?: string }[] },
  ) => request<JournalEntry>(`/orgs/${orgId}/entities/${entityId}/journal-entries`, { method: 'POST', ...json(body) }),
  postJournalEntry: (orgId: string, entityId: string, entryId: string) =>
    request<JournalEntry>(`/orgs/${orgId}/entities/${entityId}/journal-entries/${entryId}/post`, { method: 'POST' }),
  reverseJournalEntry: (orgId: string, entityId: string, entryId: string) =>
    request<JournalEntry>(`/orgs/${orgId}/entities/${entityId}/journal-entries/${entryId}/reverse`, {
      method: 'POST',
      ...json({}),
    }),
  deleteJournalEntry: (orgId: string, entityId: string, entryId: string) =>
    request<void>(`/orgs/${orgId}/entities/${entityId}/journal-entries/${entryId}`, { method: 'DELETE' }),
  periodLock: (orgId: string, entityId: string) =>
    request<{ lockedThrough: string | null }>(`/orgs/${orgId}/entities/${entityId}/period-lock`),
  setPeriodLock: (orgId: string, entityId: string, lockedThrough: string) =>
    request<{ lockedThrough: string | null }>(`/orgs/${orgId}/entities/${entityId}/period-lock`, {
      method: 'PUT',
      ...json({ lockedThrough }),
    }),

  categorizationRules: (orgId: string, entityId: string) =>
    request<CategorizationRule[]>(`/orgs/${orgId}/entities/${entityId}/categorization-rules`),
  createCategorizationRule: (orgId: string, entityId: string, contains: string, accountId: string, priority: number) =>
    request<CategorizationRule>(`/orgs/${orgId}/entities/${entityId}/categorization-rules`, {
      method: 'POST',
      ...json({ contains, accountId, priority }),
    }),
  deleteCategorizationRule: (orgId: string, entityId: string, ruleId: string) =>
    request<void>(`/orgs/${orgId}/entities/${entityId}/categorization-rules/${ruleId}`, { method: 'DELETE' }),
  categorizeAll: (orgId: string, entityId: string, items: { id: string; accountId: string }[]) =>
    request<BankTransaction[]>(`/orgs/${orgId}/entities/${entityId}/bank-transactions/categorize`, {
      method: 'POST',
      ...json({ items }),
    }),

  taxRuleCoverage: (taxYear: number) =>
    request<TaxRuleCoverage>(`/tax/rule-coverage?taxYear=${taxYear}`),

  profitAndLoss: (orgId: string, entityId: string, from: string, to: string) =>
    request<ProfitAndLoss>(`/orgs/${orgId}/entities/${entityId}/reports/profit-and-loss?from=${from}&to=${to}`),
  balanceSheet: (orgId: string, entityId: string, asOf: string) =>
    request<BalanceSheet>(`/orgs/${orgId}/entities/${entityId}/reports/balance-sheet?asOf=${asOf}`),
  trialBalance: (orgId: string, entityId: string, asOf: string) =>
    request<TrialBalance>(`/orgs/${orgId}/entities/${entityId}/reports/trial-balance?asOf=${asOf}`),
  taxLines: (orgId: string, entityId: string, taxYear: number) =>
    request<TaxLineReport>(`/orgs/${orgId}/entities/${entityId}/reports/tax-lines?taxYear=${taxYear}`),
  entityExportUrl: (orgId: string, entityId: string) =>
    `/api/v1/orgs/${orgId}/entities/${entityId}/export.zip`,
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
