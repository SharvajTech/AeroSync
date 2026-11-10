import { Sidebar } from './components/layout/Sidebar';
import { TopBar } from './components/layout/TopBar';
import { useBackendStatus } from './hooks/useBackendStatus';
import { useHashRoute } from './hooks/useHashRoute';
import { AirportsPage } from './pages/AirportsPage';
import { DashboardPage } from './pages/DashboardPage';
import { FlightsPage } from './pages/FlightsPage';
import { NetworkGraphPage } from './pages/NetworkGraphPage';
import { OperationsPage } from './pages/OperationsPage';
import { SettingsPage } from './pages/SettingsPage';
import { OperatingDateProvider, useOperatingDate } from './state/OperatingDate';

function Shell() {
  const [route, navigate] = useHashRoute();
  const status = useBackendStatus();
  const { date, setDate, dataWindow } = useOperatingDate();

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
        <TopBar date={date} onDateChange={setDate} dataWindow={dataWindow} />
        <main className="page" key={route.name}>{page}</main>
      </div>
    </div>
  );
}

export function App() {
  return (
    <OperatingDateProvider>
      <Shell />
    </OperatingDateProvider>
  );
}
