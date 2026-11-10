import type { IconType } from 'react-icons';
import { LuChartColumn, LuHouse, LuNetwork, LuPlane, LuSettings, LuTowerControl } from 'react-icons/lu';
import type { BackendStatus } from '../../hooks/useBackendStatus';
import type { RouteName } from '../../hooks/useHashRoute';
import { formatHash } from '../../hooks/useHashRoute';

const NAV: { route: RouteName; label: string; icon: IconType }[] = [
  { route: 'dashboard', label: 'Dashboard', icon: LuHouse },
  { route: 'network', label: 'Network Graph', icon: LuNetwork },
  { route: 'airports', label: 'Airports', icon: LuTowerControl },
  { route: 'flights', label: 'Flights', icon: LuPlane },
  { route: 'operations', label: 'Operations', icon: LuChartColumn },
  { route: 'settings', label: 'Settings', icon: LuSettings },
];

const STATUS_TEXT: Record<BackendStatus, string> = {
  checking: 'Connecting…',
  online: 'System online',
  offline: 'Backend offline',
};

export function Sidebar({ active, status }: { active: RouteName; status: BackendStatus }) {
  return (
    <aside className="sidebar">
      <a className="brand" href={formatHash('dashboard')} aria-label="AeroSync dashboard">
        <LuPlane className="brand__mark" aria-hidden />
        <span className="brand__name">AeroSync</span>
      </a>
      <nav className="sidebar__nav" aria-label="Main">
        {NAV.map(({ route, label, icon: Icon }) => (
          <a
            key={route}
            href={formatHash(route)}
            className={`nav-item${active === route ? ' is-active' : ''}`}
            aria-current={active === route ? 'page' : undefined}
          >
            <Icon className="nav-item__icon" aria-hidden />
            <span className="nav-item__label">{label}</span>
          </a>
        ))}
      </nav>
      <div className="sidebar__footer" title="Checks GET /api/airports every 30 s">
        <span className={`status-dot status-dot--${status}`} aria-hidden />
        <span className="sidebar__status" data-testid="backend-status">{STATUS_TEXT[status]}</span>
      </div>
    </aside>
  );
}
