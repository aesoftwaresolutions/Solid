/*
  The sidebar's contents, as plain data. src/ui/Sidebar.tsx draws whatever is listed here.

  To add a page to the sidebar:
    1. Add its <Route> in src/App.tsx, e.g. path="/orgs/:orgId/entities/:entityId/payroll".
    2. Add one line to the right group below:  { label: 'Payroll', path: 'payroll', icon: 'people' },
       - `path` is the part after /orgs/<org>/entities/<entity>/ . Leave it '' for the dashboard.
       - `icon` must be a name that exists in src/ui/icons.tsx.
  That's all. Order here is the order on screen.
*/
import type { IconName } from './icons';

export interface NavItem {
  label: string;
  path: string;
  icon: IconName;
}

export interface NavGroup {
  heading: string;
  items: NavItem[];
}

export const ENTITY_NAV: NavGroup[] = [
  {
    heading: 'Overview',
    items: [
      { label: 'Dashboard', path: '', icon: 'home' },
      { label: 'Search', path: 'search', icon: 'search' },
    ],
  },
  {
    heading: 'Money in and out',
    items: [
      { label: 'Bank', path: 'bank', icon: 'bank' },
      { label: 'Reconcile', path: 'reconcile', icon: 'check' },
      { label: 'Sales', path: 'sales', icon: 'arrow-in' },
      { label: 'Purchases', path: 'purchases', icon: 'arrow-out' },
    ],
  },
  {
    heading: 'Books',
    items: [
      { label: 'Accounts', path: 'accounts', icon: 'list' },
      { label: 'Journal', path: 'journal', icon: 'book' },
      { label: 'Documents', path: 'documents', icon: 'file' },
      { label: 'Assets', path: 'assets', icon: 'box' },
    ],
  },
  {
    heading: 'Tax and planning',
    items: [
      { label: 'Deductions', path: 'deductions', icon: 'percent' },
      { label: 'Budget', path: 'budget', icon: 'target' },
      { label: 'Reports', path: 'reports', icon: 'chart' },
    ],
  },
  {
    heading: 'Setup',
    items: [
      { label: 'Import', path: 'import', icon: 'upload' },
      { label: 'Settings', path: 'settings', icon: 'gear' },
    ],
  },
];
