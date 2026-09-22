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
  /** Two-letter state code, or null until someone says where this entity is based. */
  homeState: string | null;
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

export interface Member {
  userId: string;
  email: string;
  displayName: string;
  role: string;
}

export interface AuditEvent {
  seq: number;
  id: string;
  occurredAt: string;
  orgId: string | null;
  actorUserId: string | null;
  actorIp: string | null;
  action: string;
  objectType: string | null;
  objectId: string | null;
  details: Record<string, unknown>;
}

export interface RecurringEntry {
  id: string;
  name: string;
  memo: string | null;
  frequency: string;
  startDate: string;
  endDate: string | null;
  dayOfMonth: number;
  active: boolean;
  nextDate: string | null;
  lines: { lineNo: number; accountId: string; amount: Money; memo: string | null }[];
}

export interface RecurringRunResult {
  through: string;
  posted: { occurrenceDate: string; journalEntryId: string }[];
  skipped: { occurrenceDate: string; reason: string }[];
}

export interface BackupStatus {
  instanceId: string;
  keyFingerprint: string;
  schemaVersion: string;
  appVersion: string;
  databaseBytes: number;
  tableEstimates: Record<string, number>;
  documents: { count: number; bytes: number };
  documentsRoot: string;
  checkedAt: string;
}

export interface RulePack {
  id: string;
  title: string;
  source: string;
  taxYears: number[];
  appliesFromTaxYear: number | null;
  todos: string[];
}

export interface CategorySuggestion {
  accountId: string | null;
  code: string | null;
  name: string | null;
  source: string | null;
  model: string | null;
  reason: string | null;
}

export interface Reconciliation {
  id: string;
  bankAccountId: string;
  statementDate: string;
  statementEndingBalance: Money;
  beginningBalance: Money;
  clearedBalance: Money;
  difference: Money;
  clearedCount: number;
  status: string;
  completedAt: string | null;
}

export interface ReconciliationCandidate {
  lineId: string;
  entryId: string;
  entryDate: string;
  memo: string | null;
  amount: Money;
  cleared: boolean;
}

export interface Asset {
  id: string;
  name: string;
  description: string | null;
  category: string | null;
  placedInServiceDate: string;
  cost: Money;
  salvageValue: Money;
  usefulLifeMonths: number;
  method: string;
  status: string;
  disposalDate: string | null;
  accumulatedDepreciation: Money;
  netBookValue: Money;
  monthlySchedule: { month: string; amount: Money; posted: boolean }[];
}

export interface DepreciationRun {
  months: { month: string; amount: Money; journalEntryId: string }[];
  totalPosted: Money;
  skippedMonths: string[];
  skippedReason: string | null;
}

export interface FixedAssetReport {
  asOf: string;
  currency: string;
  assets: {
    assetId: string; name: string; placedInServiceDate: string; cost: Money;
    accumulatedDepreciation: Money; netBookValue: Money; status: string;
  }[];
  totalCost: Money;
  totalAccumulated: Money;
  totalNetBookValue: Money;
  taxNote: string;
}

export interface Vehicle {
  id: string;
  name: string;
  description: string | null;
  inServiceDate: string | null;
  isArchived: boolean;
}

export interface Trip {
  id: string;
  vehicleId: string;
  tripDate: string;
  miles: string;
  category: string;
  purpose: string | null;
  startLocation: string | null;
  endLocation: string | null;
}

export interface MileageReport {
  taxYear: number;
  rateKnown: boolean;
  ratePerMile: string | null;
  businessMiles: string;
  commutingMiles: string;
  personalMiles: string;
  otherMiles: string;
  estimatedDeduction: Money | null;
  source: string | null;
  note: string;
  byVehicle: { vehicleId: string; vehicleName: string; businessMiles: string; totalMiles: string }[];
}

export interface HomeOffice {
  taxYear: number;
  method: string;
  totalHomeSquareFeet: number;
  officeSquareFeet: number;
  monthsUsed: number | null;
}

export interface HomeOfficeReport {
  taxYear: number;
  method: string;
  officeSquareFeet: number;
  countedSquareFeet: number;
  maximumSquareFeet: number;
  ratePerSquareFoot: string | null;
  businessUsePercent: string | null;
  monthsUsed: number | null;
  deduction: Money | null;
  source: string | null;
  note: string;
}

export interface YearEndChecklist {
  taxYear: number;
  from: string;
  to: string;
  ready: boolean;
  items: {
    key: string;
    title: string;
    status: 'done' | 'todo' | 'unknown';
    detail: string;
    count: number | null;
    where: string;
  }[];
}

export interface SalesTaxRate {
  id: string;
  jurisdiction: string;
  ratePercent: string;
  liabilityAccountId: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  note: string | null;
  active: boolean;
}

export interface SalesTaxReport {
  from: string;
  to: string;
  currency: string;
  jurisdictions: { jurisdiction: string; ratePercent: string; taxableSales: Money; taxCollected: Money }[];
  totalTaxable: Money;
  totalCollected: Money;
  note: string;
}

export interface CashFlowSection {
  rows: { accountId: string | null; code: string | null; name: string; amount: Money }[];
  total: Money;
}

export interface CashFlow {
  from: string;
  to: string;
  currency: string;
  openingCash: Money;
  operating: CashFlowSection;
  investing: CashFlowSection;
  financing: CashFlowSection;
  unclassified: CashFlowSection;
  netChange: Money;
  closingCash: Money;
  note: string;
}

/** An API error carrying the server's problem-details code so screens can react to specific cases. */
export interface Invitation {
  id: string;
  orgId: string;
  email: string;
  role: string;
  status: 'pending' | 'accepted' | 'revoked' | 'expired';
  invitedBy: string | null;
  createdAt: string;
  expiresAt: string;
  /** Present only on the response that created it — there is no way to read it again. */
  token: string | null;
}

export interface RecurringInvoiceLine {
  id: string;
  lineNo: number;
  description: string;
  quantity: string;
  unitPrice: Money;
  amount: Money;
  incomeAccountId: string;
  taxRateId: string | null;
}

export interface RecurringInvoice {
  id: string;
  entityId: string;
  customerId: string;
  customerName: string;
  name: string;
  memo: string | null;
  terms: string;
  frequency: string;
  startDate: string;
  endDate: string | null;
  dayOfMonth: number;
  active: boolean;
  lines: RecurringInvoiceLine[];
  total: Money;
  /** The last occurrence created, or null when none has been. */
  lastCreated: string | null;
}

export interface RecurringInvoiceRun {
  through: string;
  created: { recurringInvoiceId: string; date: string; invoiceId: string; invoiceNumber: string }[];
  skipped: { recurringInvoiceId: string; date: string; reason: string }[];
}

export interface SessionInfo {
  id: string;
  createdAt: string;
  lastSeenAt: string;
  expiresAt: string;
  ip: string | null;
  userAgent: string | null;
  mfaVerified: boolean;
  /** True for the session making this request. */
  current: boolean;
}

export interface TaxFigure {
  id: string;
  key: string;
  taxYear: number;
  value: string;
  unit: string;
  source: string;
  note: string | null;
  addedAt: string;
  addedBy: string | null;
  supersededAt: string | null;
  /** True when this is the figure Solid would use today for that key and year. */
  inUse: boolean;
}

export interface SetupStep {
  key: string;
  title: string;
  status: 'done' | 'todo' | 'optional';
  detail: string;
  /** The screen that does this step. */
  where: string;
}

export interface Setup {
  complete: boolean;
  doneCount: number;
  requiredCount: number;
  steps: SetupStep[];
}

export interface SearchHit {
  kind: string;
  id: string;
  label: string;
  detail: string | null;
  date: string | null;
  amount: Money | null;
  /** The screen that shows this record, so a hit can be a link. */
  where: string;
}

export interface SearchResults {
  query: string;
  /** The amount the text was read as, if it read as one — so a person can see why 420.00 came back. */
  amountInterpreted: Money | null;
  groups: { kind: string; total: number; hits: SearchHit[] }[];
}

export interface OverviewEntityLine {
  entityId: string;
  legalName: string;
  kind: string;
  currency: string;
  /** False for an entity with no chart of accounts yet: its figures are zero, not a result. */
  setUp: boolean;
  /** The period these figures cover — the entity's own fiscal year unless the caller asked for one. */
  from: string;
  to: string;
  cash: Money;
  netIncome: Money;
  draftEntries: number;
  uncategorizedBankTransactions: number;
  needsAttention: boolean;
}

export interface Overview {
  orgId: string;
  /** Null when no period was asked for and each entity used its own fiscal year. */
  from: string | null;
  to: string;
  mixedCurrencies: boolean;
  entities: OverviewEntityLine[];
  totals: { currency: string; cash: Money; netIncome: Money } | null;
  note: string;
}

export type ImportKind = 'accounts' | 'customers' | 'vendors';

export interface ImportResultRow {
  line: number;
  key: string;
  action: 'create' | 'skip' | 'error';
  detail: string;
}

export interface ImportResult {
  kind: ImportKind;
  /** False for a preview, and false for an import that was refused because a row was wrong. */
  committed: boolean;
  totalRows: number;
  created: number;
  skipped: number;
  problemCount: number;
  ready: boolean;
  ignoredColumns: string[];
  rows: ImportResultRow[];
}

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

/**
 * @param accepted status codes whose body is a normal result rather than an error — the import endpoint
 *   answers 422 with the same report it would have returned on success, listing what is wrong with the file.
 */
async function request<T>(path: string, init: RequestInit = {}, accepted: number[] = []): Promise<T> {
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
  if (!response.ok && !accepted.includes(response.status)) {
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
  entity: (orgId: string, entityId: string) => request<Entity>(`/orgs/${orgId}/entities/${entityId}`),
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
      lines: { description: string; quantity: string; unitPrice: Money; incomeAccountId: string; taxRateId?: string }[];
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

  members: (orgId: string) => request<Member[]>(`/orgs/${orgId}/members`),
  addMember: (orgId: string, email: string, role: string) =>
    request<Member>(`/orgs/${orgId}/members`, { method: 'POST', ...json({ email, role }) }),
  auditEvents: (orgId: string, limit: number) =>
    request<AuditEvent[]>(`/orgs/${orgId}/audit-events?limit=${limit}`),

  recurringEntries: (orgId: string, entityId: string) =>
    request<RecurringEntry[]>(`/orgs/${orgId}/entities/${entityId}/recurring-entries`),
  createRecurringEntry: (
    orgId: string,
    entityId: string,
    body: {
      name: string;
      memo?: string;
      frequency: string;
      startDate: string;
      dayOfMonth?: number;
      lines: { accountId: string; amount: Money; memo?: string }[];
    },
  ) => request<RecurringEntry>(`/orgs/${orgId}/entities/${entityId}/recurring-entries`, { method: 'POST', ...json(body) }),
  deactivateRecurringEntry: (orgId: string, entityId: string, recurringId: string) =>
    request<RecurringEntry>(`/orgs/${orgId}/entities/${entityId}/recurring-entries/${recurringId}/deactivate`, {
      method: 'POST',
      ...json({}),
    }),
  runRecurringEntries: (orgId: string, entityId: string, through: string) =>
    request<RecurringRunResult>(`/orgs/${orgId}/entities/${entityId}/recurring-entries/run`, {
      method: 'POST',
      ...json({ through }),
    }),

  backupStatus: () => request<BackupStatus>('/system/backup-status'),
  taxRulePacks: () => request<RulePack[]>('/tax/rule-packs'),

  openingBalances: (orgId: string, entityId: string) =>
    request<JournalEntry>(`/orgs/${orgId}/entities/${entityId}/opening-balances`),
  createOpeningBalances: (
    orgId: string,
    entityId: string,
    body: { asOfDate: string; equityAccountId?: string; balances: { accountId: string; amount: Money }[] },
  ) => request<JournalEntry>(`/orgs/${orgId}/entities/${entityId}/opening-balances`, { method: 'POST', ...json(body) }),

  suggestCategory: (orgId: string, entityId: string, txnId: string) =>
    request<CategorySuggestion>(`/orgs/${orgId}/entities/${entityId}/bank-transactions/${txnId}/suggest`, {
      method: 'POST',
      ...json({}),
    }),

  reconciliations: (orgId: string, entityId: string, bankAccountId: string) =>
    request<Reconciliation[]>(
      `/orgs/${orgId}/entities/${entityId}/bank-accounts/${bankAccountId}/reconciliations`),
  startReconciliation: (
    orgId: string,
    entityId: string,
    bankAccountId: string,
    statementDate: string,
    statementEndingBalance: Money,
  ) =>
    request<Reconciliation>(`/orgs/${orgId}/entities/${entityId}/bank-accounts/${bankAccountId}/reconciliations`, {
      method: 'POST',
      ...json({ statementDate, statementEndingBalance }),
    }),
  reconciliationCandidates: (orgId: string, entityId: string, bankAccountId: string, reconciliationId: string) =>
    request<ReconciliationCandidate[]>(
      `/orgs/${orgId}/entities/${entityId}/bank-accounts/${bankAccountId}/reconciliations/${reconciliationId}/candidates`),
  setReconciliationCleared: (
    orgId: string,
    entityId: string,
    bankAccountId: string,
    reconciliationId: string,
    lineIds: string[],
    cleared: boolean,
  ) =>
    request<Reconciliation>(
      `/orgs/${orgId}/entities/${entityId}/bank-accounts/${bankAccountId}/reconciliations/${reconciliationId}/cleared`,
      { method: 'POST', ...json({ lineIds, cleared }) }),
  completeReconciliation: (orgId: string, entityId: string, bankAccountId: string, reconciliationId: string) =>
    request<Reconciliation>(
      `/orgs/${orgId}/entities/${entityId}/bank-accounts/${bankAccountId}/reconciliations/${reconciliationId}/complete`,
      { method: 'POST', ...json({}) }),
  undoReconciliation: (orgId: string, entityId: string, bankAccountId: string, reconciliationId: string) =>
    request<void>(
      `/orgs/${orgId}/entities/${entityId}/bank-accounts/${bankAccountId}/reconciliations/${reconciliationId}/undo`,
      { method: 'POST', ...json({}) }),

  assets: (orgId: string, entityId: string) => request<Asset[]>(`/orgs/${orgId}/entities/${entityId}/assets`),
  createAsset: (
    orgId: string,
    entityId: string,
    body: {
      name: string; placedInServiceDate: string; cost: Money; usefulLifeMonths: number;
      assetAccountId: string; accumulatedAccountId: string; depreciationExpenseAccountId: string;
      salvageValue?: Money; category?: string; description?: string;
    },
  ) => request<Asset>(`/orgs/${orgId}/entities/${entityId}/assets`, { method: 'POST', ...json(body) }),
  runDepreciation: (orgId: string, entityId: string, throughMonth: string) =>
    request<DepreciationRun>(`/orgs/${orgId}/entities/${entityId}/depreciation-runs`, {
      method: 'POST',
      ...json({ throughMonth }),
    }),
  disposeAsset: (
    orgId: string,
    entityId: string,
    assetId: string,
    body: { disposalDate: string; proceeds?: Money; depositAccountId?: string; gainLossAccountId: string },
  ) => request<Asset>(`/orgs/${orgId}/entities/${entityId}/assets/${assetId}/dispose`, { method: 'POST', ...json(body) }),
  fixedAssetReport: (orgId: string, entityId: string, asOf: string) =>
    request<FixedAssetReport>(`/orgs/${orgId}/entities/${entityId}/reports/fixed-assets?asOf=${asOf}`),

  vehicles: (orgId: string, entityId: string) => request<Vehicle[]>(`/orgs/${orgId}/entities/${entityId}/vehicles`),
  createVehicle: (orgId: string, entityId: string, name: string) =>
    request<Vehicle>(`/orgs/${orgId}/entities/${entityId}/vehicles`, { method: 'POST', ...json({ name }) }),
  trips: (orgId: string, entityId: string, taxYear: number) =>
    request<Trip[]>(`/orgs/${orgId}/entities/${entityId}/mileage-trips?taxYear=${taxYear}`),
  addTrip: (
    orgId: string,
    entityId: string,
    body: { vehicleId: string; tripDate: string; miles: string; category: string; purpose?: string },
  ) => request<Trip>(`/orgs/${orgId}/entities/${entityId}/mileage-trips`, { method: 'POST', ...json(body) }),
  deleteTrip: (orgId: string, entityId: string, tripId: string) =>
    request<void>(`/orgs/${orgId}/entities/${entityId}/mileage-trips/${tripId}`, { method: 'DELETE' }),
  mileageReport: (orgId: string, entityId: string, taxYear: number) =>
    request<MileageReport>(`/orgs/${orgId}/entities/${entityId}/reports/mileage?taxYear=${taxYear}`),
  homeOffice: (orgId: string, entityId: string, taxYear: number) =>
    request<HomeOffice>(`/orgs/${orgId}/entities/${entityId}/home-office?taxYear=${taxYear}`),
  saveHomeOffice: (
    orgId: string,
    entityId: string,
    taxYear: number,
    body: { method: string; totalHomeSquareFeet: number; officeSquareFeet: number; monthsUsed?: number },
  ) => request<HomeOffice>(`/orgs/${orgId}/entities/${entityId}/home-office?taxYear=${taxYear}`, {
    method: 'PUT',
    ...json(body),
  }),
  homeOfficeReport: (orgId: string, entityId: string, taxYear: number) =>
    request<HomeOfficeReport>(`/orgs/${orgId}/entities/${entityId}/reports/home-office?taxYear=${taxYear}`),

  yearEndChecklist: (orgId: string, entityId: string, taxYear: number) =>
    request<YearEndChecklist>(`/orgs/${orgId}/entities/${entityId}/reports/year-end-checklist?taxYear=${taxYear}`),

  salesTaxRates: (orgId: string, entityId: string) =>
    request<SalesTaxRate[]>(`/orgs/${orgId}/entities/${entityId}/sales-tax-rates`),
  createSalesTaxRate: (
    orgId: string,
    entityId: string,
    body: { jurisdiction: string; ratePercent: string; liabilityAccountId: string; effectiveFrom: string; note?: string },
  ) => request<SalesTaxRate>(`/orgs/${orgId}/entities/${entityId}/sales-tax-rates`, { method: 'POST', ...json(body) }),
  deactivateSalesTaxRate: (orgId: string, entityId: string, rateId: string) =>
    request<SalesTaxRate>(`/orgs/${orgId}/entities/${entityId}/sales-tax-rates/${rateId}/deactivate`, {
      method: 'POST',
      ...json({}),
    }),
  salesTaxReport: (orgId: string, entityId: string, from: string, to: string) =>
    request<SalesTaxReport>(`/orgs/${orgId}/entities/${entityId}/reports/sales-tax?from=${from}&to=${to}`),

  updateCustomer: (
    orgId: string,
    entityId: string,
    customerId: string,
    patch: Record<string, string | boolean | null>,
  ) => request<Customer>(`/orgs/${orgId}/entities/${entityId}/customers/${customerId}`, {
    method: 'PATCH',
    ...json(patch),
  }),
  updateVendor: (
    orgId: string,
    entityId: string,
    vendorId: string,
    patch: Record<string, string | boolean | null>,
  ) => request<Vendor>(`/orgs/${orgId}/entities/${entityId}/vendors/${vendorId}`, {
    method: 'PATCH',
    ...json(patch),
  }),

  verifyJournal: (orgId: string, entityId: string) =>
    request<{ valid: boolean; postedEntries: number; firstInvalidSeq: number | null }>(
      `/orgs/${orgId}/entities/${entityId}/journal/verify`,
    ),
  recurringInvoices: (orgId: string, entityId: string) =>
    request<RecurringInvoice[]>(`/orgs/${orgId}/entities/${entityId}/recurring-invoices`),
  createRecurringInvoice: (
    orgId: string,
    entityId: string,
    body: {
      customerId: string;
      name: string;
      terms: string;
      frequency: string;
      startDate: string;
      dayOfMonth?: number;
      memo?: string;
      lines: { description: string; quantity: string; unitPrice: Money; incomeAccountId: string }[];
    },
  ) =>
    request<RecurringInvoice>(`/orgs/${orgId}/entities/${entityId}/recurring-invoices`, {
      method: 'POST',
      ...json(body),
    }),
  deactivateRecurringInvoice: (orgId: string, entityId: string, id: string) =>
    request<RecurringInvoice>(`/orgs/${orgId}/entities/${entityId}/recurring-invoices/${id}/deactivate`, {
      method: 'POST',
      ...json({}),
    }),
  runRecurringInvoices: (orgId: string, entityId: string, through: string) =>
    request<RecurringInvoiceRun>(
      `/orgs/${orgId}/entities/${entityId}/recurring-invoices/run?through=${through}`,
      { method: 'POST', ...json({}) },
    ),

  sessions: () => request<SessionInfo[]>('/auth/sessions'),
  revokeSession: (sessionId: string) =>
    request<void>(`/auth/sessions/${sessionId}`, { method: 'DELETE' }),
  revokeOtherSessions: () =>
    request<{ revoked: number }>('/auth/sessions/revoke-others', { method: 'POST', ...json({}) }),

  changePassword: (currentPassword: string, newPassword: string) =>
    request<void>('/auth/change-password', { method: 'POST', ...json({ currentPassword, newPassword }) }),
  resetPassword: (token: string, newPassword: string) =>
    request<void>('/auth/reset-password', { method: 'POST', ...json({ token, newPassword }) }),
  taxFigures: () => request<TaxFigure[]>('/instance/tax-figures'),
  taxFigureKeys: () =>
    request<{ key: string; unit: string; decimalPlaces: number }[]>('/instance/tax-figures/keys'),
  addTaxFigure: (figure: {
    key: string;
    taxYear: number;
    value: string;
    source: string;
    note?: string;
    supersede?: boolean;
  }) => request<TaxFigure>('/instance/tax-figures', { method: 'POST', ...json(figure) }),

  instanceUsers: () => request<User[]>('/instance/users'),
  issuePasswordReset: (userId: string) =>
    request<{ userId: string; email: string; expiresAt: string; token: string; resetPath: string }>(
      `/instance/users/${userId}/password-reset`,
      { method: 'POST', ...json({}) },
    ),

  changeMemberRole: (orgId: string, userId: string, role: string) =>
    request<Member>(`/orgs/${orgId}/members/${userId}`, { method: 'PATCH', ...json({ role }) }),
  removeMember: (orgId: string, userId: string) =>
    request<void>(`/orgs/${orgId}/members/${userId}`, { method: 'DELETE' }),

  invitations: (orgId: string) => request<Invitation[]>(`/orgs/${orgId}/invitations`),
  invite: (orgId: string, email: string, role: string) =>
    request<Invitation>(`/orgs/${orgId}/invitations`, { method: 'POST', ...json({ email, role }) }),
  revokeInvitation: (orgId: string, invitationId: string) =>
    request<void>(`/orgs/${orgId}/invitations/${invitationId}`, { method: 'DELETE' }),
  acceptInvitation: (token: string, displayName: string, password: string) =>
    request<{ organizationId: string; email: string; created: boolean }>('/auth/accept-invitation', {
      method: 'POST',
      ...json({ token, displayName, password }),
    }),

  setup: (orgId: string, entityId: string) =>
    request<Setup>(`/orgs/${orgId}/entities/${entityId}/setup`),
  updateEntity: (orgId: string, entityId: string, patch: { legalName?: string; accountingMethod?: string; homeState?: string }) =>
    request<Entity>(`/orgs/${orgId}/entities/${entityId}`, { method: 'PATCH', ...json(patch) }),

  search: (orgId: string, entityId: string, q: string) =>
    request<SearchResults>(`/orgs/${orgId}/entities/${entityId}/search?q=${encodeURIComponent(q)}`),

  overview: (orgId: string, from?: string, to?: string) =>
    request<Overview>(`/orgs/${orgId}/overview${from && to ? `?from=${from}&to=${to}` : ''}`),

  previewImport: (orgId: string, entityId: string, kind: ImportKind, csv: string) =>
    request<ImportResult>(`/orgs/${orgId}/entities/${entityId}/imports/${kind}/preview`, {
      method: 'POST',
      ...json({ csv }),
    }),
  runImport: (orgId: string, entityId: string, kind: ImportKind, csv: string) =>
    request<ImportResult>(
      `/orgs/${orgId}/entities/${entityId}/imports/${kind}`,
      { method: 'POST', ...json({ csv }) },
      [422],
    ),

  cashFlow: (orgId: string, entityId: string, from: string, to: string) =>
    request<CashFlow>(`/orgs/${orgId}/entities/${entityId}/reports/cash-flow?from=${from}&to=${to}`),

  profitAndLoss: (orgId: string, entityId: string, from: string, to: string) =>
    request<ProfitAndLoss>(`/orgs/${orgId}/entities/${entityId}/reports/profit-and-loss?from=${from}&to=${to}`),
  balanceSheet: (orgId: string, entityId: string, asOf: string) =>
    request<BalanceSheet>(`/orgs/${orgId}/entities/${entityId}/reports/balance-sheet?asOf=${asOf}`),
  trialBalance: (orgId: string, entityId: string, asOf: string) =>
    request<TrialBalance>(`/orgs/${orgId}/entities/${entityId}/reports/trial-balance?asOf=${asOf}`),
  taxLines: (orgId: string, entityId: string, taxYear: number) =>
    request<TaxLineReport>(`/orgs/${orgId}/entities/${entityId}/reports/tax-lines?taxYear=${taxYear}`),
  customerStatementPdfUrl: (orgId: string, entityId: string, customerId: string) =>
    `/api/v1/orgs/${orgId}/entities/${entityId}/customers/${customerId}/statement.pdf`,
  invoicePdfUrl: (orgId: string, entityId: string, invoiceId: string) =>
    `/api/v1/orgs/${orgId}/entities/${entityId}/invoices/${invoiceId}/pdf`,
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
