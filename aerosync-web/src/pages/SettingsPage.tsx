import { apiBaseUrl } from '../api/config';
import { Card } from '../components/common/Card';
import { StatusBadge } from '../components/common/StatusBadge';
import { PageHeader } from '../components/layout/PageHeader';
import { roleLabel } from '../components/layout/ProfileMenu';
import type { BackendStatus } from '../hooks/useBackendStatus';
import { useAirport } from '../state/AirportContext';
import { useOperatingDate } from '../state/OperatingDate';
import { formatDateLong } from '../utils/format';

const ENDPOINTS = [
  'POST /api/auth/login',
  'GET /api/auth/me',
  'GET /api/graph/network?airports=',
  'GET /api/graph/airports/{iata}',
  'GET /api/graph/flights/{flightId}',
  'GET /api/graph/flights/{flightId}/affected',
  'GET /api/airports',
  'GET /api/airports/{iata}/operations',
  'GET /api/disruptions?airport=',
  'POST /api/agents/propose|approve|reject/{id}',
];

/** Read-only environment information; nothing here changes backend state. */
export function SettingsPage({ status }: { status: BackendStatus }) {
  const { date, dataWindow, network } = useOperatingDate();
  const { user, airport } = useAirport();
  const base = apiBaseUrl();
  return (
    <>
      <PageHeader title="Settings" subtitle="Connection and data information (read-only)" />
      <div className="settings-grid">
        <Card title="Backend connection">
          <dl className="kv kv--wide">
            <dt>API base URL</dt>
            <dd className="mono">{base || 'Same origin (/api proxied to the Spring Boot backend)'}</dd>
            <dt>Status</dt>
            <dd>
              <StatusBadge tone={status === 'online' ? 'success' : status === 'offline' ? 'danger' : 'neutral'}>
                {status === 'online' ? 'Online' : status === 'offline' ? 'Offline' : 'Checking'}
              </StatusBadge>
            </dd>
            <dt>Authentication</dt>
            <dd>Bearer token (JWT) from POST /api/auth/login, kept in this tab&apos;s session storage</dd>
          </dl>
          <h3 className="details__label">Endpoints used</h3>
          <ul className="plain-list mono">
            {ENDPOINTS.map((e) => <li key={e}>{e}</li>)}
          </ul>
        </Card>
        <Card title="Signed-in account">
          <dl className="kv kv--wide">
            <dt>User</dt>
            <dd>{user.display_name} <span className="mono">({user.username})</span></dd>
            <dt>Role</dt>
            <dd>{roleLabel(user.role)}</dd>
            <dt>Authorized airports</dt>
            <dd>{user.airports.map((a) => `${a.iata_code} · ${a.city}`).join(', ')}</dd>
            <dt>Selected airport</dt>
            <dd>{airport.iata_code} · {airport.airport_name}</dd>
          </dl>
          <p className="details__muted">
            The backend limits every request to these airports; other airports&apos; flights, gates, crew and resolution
            actions are refused (HTTP 403).
          </p>
        </Card>
        <Card title="Operational data">
          <dl className="kv kv--wide">
            <dt>Selected date</dt>
            <dd>{formatDateLong(date)}</dd>
            <dt>Dates with flights</dt>
            <dd>
              {dataWindow?.first_service_date
                ? `${formatDateLong(dataWindow.first_service_date)} – ${formatDateLong(dataWindow.last_service_date)}`
                : '—'}
            </dd>
            <dt>Data origin</dt>
            <dd>{network.data ? (network.data.synthetic_data ? 'Synthetic demonstration data' : 'Imported / manual') : '—'}</dd>
          </dl>
          {network.data?.data_notice && <p className="details__muted">{network.data.data_notice}</p>}
          <p className="details__muted">
            To change the backend location set <span className="mono">BACKEND_URL</span> for the dev server or the nginx
            container, or <span className="mono">VITE_API_BASE_URL</span> at build time.
          </p>
        </Card>
      </div>
    </>
  );
}
