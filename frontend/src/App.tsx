import { useEffect, useState } from 'react';
import { Link, Navigate, Route, Routes, useLocation, useMatch } from 'react-router-dom';
import { useAuth } from './auth';
import { Loading } from './components';
import AcceptInvitationPage from './pages/AcceptInvitationPage';
import AccountPage from './pages/AccountPage';
import AccountsPage from './pages/AccountsPage';
import AssetsPage from './pages/AssetsPage';
import BankPage from './pages/BankPage';
import BudgetPage from './pages/BudgetPage';
import DashboardPage from './pages/DashboardPage';
import DeductionsPage from './pages/DeductionsPage';
import DocumentsPage from './pages/DocumentsPage';
import EntitiesPage from './pages/EntitiesPage';
import EntitySettingsPage from './pages/EntitySettingsPage';
import ImportPage from './pages/ImportPage';
import InstancePage from './pages/InstancePage';
import JournalPage from './pages/JournalPage';
import LoginPage from './pages/LoginPage';
import OrganizationPage from './pages/OrganizationPage';
import OrganizationsPage from './pages/OrganizationsPage';
import PurchasesPage from './pages/PurchasesPage';
import ReconcilePage from './pages/ReconcilePage';
import ReportsPage from './pages/ReportsPage';
import ResetPasswordPage from './pages/ResetPasswordPage';
import SalesPage from './pages/SalesPage';
import SearchPage from './pages/SearchPage';
import { Icon } from './ui/icons';
import { Sidebar } from './ui/Sidebar';

/**
 * The frame around every signed-in page: the top bar, and — inside an entity — the sidebar menu.
 * The pages themselves are listed in the <Routes> below; the sidebar's links are in src/ui/navigation.ts.
 */
function Shell() {
  const { state, logout } = useAuth();
  const email = state.status === 'ready' ? state.me.user.email : '';
  const location = useLocation();
  const entity = useMatch('/orgs/:orgId/entities/:entityId/*');
  const [menuOpen, setMenuOpen] = useState(false);

  // On a phone the menu is a drawer: close it once a page is picked, or when Escape is pressed.
  useEffect(() => setMenuOpen(false), [location.pathname]);
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => event.key === 'Escape' && setMenuOpen(false);
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  return (
    <>
      <header className="topbar">
        {entity && (
          <button
            type="button"
            className="secondary menu-button"
            aria-label={menuOpen ? 'Close menu' : 'Open menu'}
            aria-expanded={menuOpen}
            aria-controls="sidebar"
            onClick={() => setMenuOpen((open) => !open)}
          >
            <Icon name={menuOpen ? 'close' : 'menu'} size={20} />
          </button>
        )}
        <Link to="/" className="brand">
          <span className="brand-mark" aria-hidden="true" />
          Solid
        </Link>
        <span className="spacer" />
        <Link to="/account" className="who">
          {email}
        </Link>
        <button type="button" className="secondary" onClick={() => void logout()}>
          Sign out
        </button>
      </header>
      <div className="app-body">
        {entity && (
          <>
            <Sidebar orgId={entity.params.orgId ?? ''} entityId={entity.params.entityId ?? ''} open={menuOpen} />
            <div className={menuOpen ? 'backdrop visible' : 'backdrop'} onClick={() => setMenuOpen(false)} />
          </>
        )}
        <div className="content">
          <Routes>
            <Route path="/" element={<OrganizationsPage />} />
            <Route path="/account" element={<AccountPage />} />
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
            <Route path="/orgs/:orgId/entities/:entityId/import" element={<ImportPage />} />
            <Route path="/orgs/:orgId/entities/:entityId/search" element={<SearchPage />} />
            <Route path="/orgs/:orgId/entities/:entityId/settings" element={<EntitySettingsPage />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </div>
      </div>
    </>
  );
}

export default function App() {
  const { state } = useAuth();

  // An invitation link is for someone with no account yet, and a reset link is for someone who cannot sign
  // in: both are reachable before the sign-in gate.
  if (window.location.pathname === '/accept-invitation' || window.location.pathname === '/reset-password') {
    return (
      <Routes>
        <Route path="/accept-invitation" element={<AcceptInvitationPage />} />
        <Route path="/reset-password" element={<ResetPasswordPage />} />
      </Routes>
    );
  }

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
