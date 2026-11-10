import { useCallback } from 'react';
import { AuthProvider, resolveScreen, useAuth } from './auth/AuthContext';
import type { AuthStore } from './auth/authStore';
import type { KeyValueStorage } from './auth/session';
import { Sidebar } from './components/layout/Sidebar';
import { TopBar } from './components/layout/TopBar';
import { useBackendStatus, type BackendStatus } from './hooks/useBackendStatus';
import { formatHash, useHashRoute } from './hooks/useHashRoute';
import { AirportsPage } from './pages/AirportsPage';
import { DashboardPage } from './pages/DashboardPage';
import { FlightsPage } from './pages/FlightsPage';
import { LoginPage, SplashScreen } from './pages/LoginPage';
import { NetworkGraphPage } from './pages/NetworkGraphPage';
import { OperationsPage } from './pages/OperationsPage';
import { SettingsPage } from './pages/SettingsPage';
import { AirportProvider, useAirport } from './state/AirportContext';
import { OperatingDateProvider, useOperatingDate } from './state/OperatingDate';

function Shell({ status, expiresAt, onLogout }: { status: BackendStatus; expiresAt: string | null; onLogout: () => void }) {
  const [route, navigate] = useHashRoute();
  const { date, setDate, dataWindow } = useOperatingDate();
  const { airport, airports, setAirport, user } = useAirport();

  let page;
  switch (route.name) {
    case 'network':
      page = <NetworkGraphPage params={route.params} navigate={navigate} />;
      break;
    case 'airports':
      page = <AirportsPage navigate={navigate} />;
      break;
    case 'flights':
      page = <FlightsPage params={route.params} navigate={navigate} />;
      break;
    case 'operations':
      page = <OperationsPage params={route.params} navigate={navigate} />;
      break;
    case 'settings':
      page = <SettingsPage status={status} />;
      break;
    default:
      page = <DashboardPage navigate={navigate} />;
  }

  return (
    <div className="app">
      <Sidebar active={route.name} status={status} />
      <div className="app__main">
        <TopBar
          date={date}
          onDateChange={setDate}
          dataWindow={dataWindow}
          airport={airport}
          airports={airports}
          onAirportChange={setAirport}
          user={user}
          expiresAt={expiresAt}
          onLogout={onLogout}
        />
        {/* Keyed by airport: switching airports remounts the page, so no graph,
            selection or filter state of the previous airport survives. */}
        <main className="page" key={`${route.name}:${airport.iata_code}`}>{page}</main>
      </div>
    </div>
  );
}

/** Every route is protected: without a session the login page is shown instead. */
export function Root({ storage }: { storage: KeyValueStorage }) {
  const auth = useAuth();
  const status = useBackendStatus();
  const { store } = auth;
  const login = useCallback((username: string, password: string) => store.login(username, password), [store]);
  const logout = useCallback(() => {
    store.logout();
    // The next user starts from the dashboard, not from this user's deep link.
    if (typeof window !== 'undefined') window.location.hash = formatHash('dashboard');
  }, [store]);

  const screen = resolveScreen(auth.status);
  if (screen === 'splash') return <SplashScreen />;
  if (screen === 'login' || !auth.session) return <LoginPage notice={auth.notice} onLogin={login} status={status} />;

  const { user, expiresAt } = auth.session;
  return (
    <AirportProvider key={user.user_id} user={user} storage={storage}>
      <OperatingDateProvider>
        <Shell status={status} expiresAt={expiresAt} onLogout={logout} />
      </OperatingDateProvider>
    </AirportProvider>
  );
}

export function App({ store, storage }: { store: AuthStore; storage: KeyValueStorage }) {
  return (
    <AuthProvider store={store}>
      <Root storage={storage} />
    </AuthProvider>
  );
}
