import { Link, Navigate, Route, Routes, useParams } from 'react-router-dom';
import { useAuth } from './auth';
import { Loading } from './components';
import AccountsPage from './pages/AccountsPage';
import BankPage from './pages/BankPage';
import DashboardPage from './pages/DashboardPage';
import EntitiesPage from './pages/EntitiesPage';
import LoginPage from './pages/LoginPage';
import OrganizationsPage from './pages/OrganizationsPage';
import ReportsPage from './pages/ReportsPage';

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
        <Route path="/orgs/:orgId" element={<EntitiesPage />} />
        <Route path="/orgs/:orgId/entities/:entityId" element={<DashboardPage />} />
        <Route path="/orgs/:orgId/entities/:entityId/accounts" element={<AccountsPage />} />
        <Route path="/orgs/:orgId/entities/:entityId/bank" element={<BankPage />} />
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
