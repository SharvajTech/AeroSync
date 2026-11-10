import { apiBaseUrl } from '../api/config';
import { Card } from '../components/common/Card';
import { StatusBadge } from '../components/common/StatusBadge';
import { PageHeader } from '../components/layout/PageHeader';
import type { BackendStatus } from '../hooks/useBackendStatus';
import { useOperatingDate } from '../state/OperatingDate';
import { formatDateLong } from '../utils/format';

const ENDPOINTS = [
  'GET /api/graph/network',
  'GET /api/graph/airports/{iata}',
  'GET /api/graph/flights/{flightId}',
  'GET /api/graph/flights/{flightId}/affected',
  'GET /api/airports',
  'GET /api/airports/{iata}/operations',
];

/** Read-only environment information; nothing here changes backend state. */
export function SettingsPage({ status }: { status: BackendStatus }) {
  const { date, dataWindow, network } = useOperatingDate();
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
            <dd>None — the views below use public, read-only endpoints</dd>
          </dl>
          <h3 className="details__label">Endpoints used</h3>
          <ul className="plain-list mono">
            {ENDPOINTS.map((e) => <li key={e}>{e}</li>)}
          </ul>
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
