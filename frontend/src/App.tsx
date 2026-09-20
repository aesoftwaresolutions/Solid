import { Link, Navigate, Route, Routes, useParams } from 'react-router-dom';
import { useAuth } from './auth';
import { Loading } from './components';
import AccountsPage from './pages/AccountsPage';
import AssetsPage from './pages/AssetsPage';
import BankPage from './pages/BankPage';
import BudgetPage from './pages/BudgetPage';
import DashboardPage from './pages/DashboardPage';
import DeductionsPage from './pages/DeductionsPage';
import DocumentsPage from './pages/DocumentsPage';
import EntitiesPage from './pages/EntitiesPage';
import InstancePage from './pages/InstancePage';
import JournalPage from './pages/JournalPage';
import LoginPage from './pages/LoginPage';
import OrganizationPage from './pages/OrganizationPage';
import OrganizationsPage from './pages/OrganizationsPage';
import PurchasesPage from './pages/PurchasesPage';
import ReconcilePage from './pages/ReconcilePage';
import ReportsPage from './pages/ReportsPage';
import SalesPage from './pages/SalesPage';

function EntityNav() {
  const { orgId, entityId } = useParams();
  if (!orgId || !entityId) {
    return null;
  }
  const base = `/orgs/${orgId}/entities/${entityId}`;
  return (
    <>
      <Link to={base}>Dashboard</Link>
      <Link to={`${base}/accounts`}>Accounts</Link>
      <Link to={`${base}/bank`}>Bank</Link>
      <Link to={`${base}/reconcile`}>Reconcile</Link>
      <Link to={`${base}/journal`}>Journal</Link>
      <Link to={`${base}/sales`}>Sales</Link>
      <Link to={`${base}/purchases`}>Purchases</Link>
      <Link to={`${base}/documents`}>Documents</Link>
      <Link to={`${base}/assets`}>Assets</Link>
      <Link to={`${base}/deductions`}>Deductions</Link>
      <Link to={`${base}/budget`}>Budget</Link>
      <Link to={`${base}/reports`}>Reports</Link>
    </>
  );
}

function Shell() {
  const { state, logout } = useAuth();
  const email = state.status === 'ready' ? state.me.user.email : '';
  return (
    <>
      <nav className="top">
        <Link to="/">
          <strong>Solid</strong>
        </Link>
        <Routes>
          <Route path="/orgs/:orgId/entities/:entityId/*" element={<EntityNav />} />
          <Route path="*" element={null} />
        </Routes>
        <span className="spacer" />
        <span className="muted">{email}</span>
        <button type="button" className="secondary" onClick={() => void logout()}>
          Sign out
        </button>
      </nav>
      <Routes>
        <Route path="/" element={<OrganizationsPage />} />
        <Route path="/instance" element={<InstancePage />} />
        <Route path="/orgs/:orgId" element={<EntitiesPage />} />
        <Route path="/orgs/:orgId/settings" element={<OrganizationPage />} />
        <Route path="/orgs/:orgId/entities/:entityId" element={<DashboardPage />} />
        <Route path="/orgs/:orgId/entities/:entityId/accounts" element={<AccountsPage />} />
        <Route path="/orgs/:orgId/entities/:entityId/bank" element={<BankPage />} />
        <Route path="/orgs/:orgId/entities/:entityId/reconcile" element={<ReconcilePage />} />
        <Route path="/orgs/:orgId/entities/:entityId/journal" element={<JournalPage />} />
        <Route path="/orgs/:orgId/entities/:entityId/assets" element={<AssetsPage />} />
        <Route path="/orgs/:orgId/entities/:entityId/deductions" element={<DeductionsPage />} />
        <Route path="/orgs/:orgId/entities/:entityId/budget" element={<BudgetPage />} />
        <Route path="/orgs/:orgId/entities/:entityId/sales" element={<SalesPage />} />
        <Route path="/orgs/:orgId/entities/:entityId/purchases" element={<PurchasesPage />} />
        <Route path="/orgs/:orgId/entities/:entityId/documents" element={<DocumentsPage />} />
        <Route path="/orgs/:orgId/entities/:entityId/reports" element={<ReportsPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </>
  );
}

export default function App() {
  const { state } = useAuth();

  if (state.status === 'loading') {
    return (
      <main>
        <Loading what="Solid" />
      </main>
    );
  }
  if (state.status === 'anonymous' || state.status === 'mfaPending') {
    return <LoginPage />;
  }
  return <Shell />;
}
