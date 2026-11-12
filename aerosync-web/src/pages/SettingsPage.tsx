import { apiBaseUrl } from '../api/config';
import type { FeedInfo } from '../api/types';
import { Card } from '../components/common/Card';
import { dataSources } from '../graph/model';
import { StatusBadge } from '../components/common/StatusBadge';
import { PageHeader } from '../components/layout/PageHeader';
import { roleLabel } from '../components/layout/ProfileMenu';
import type { BackendStatus } from '../hooks/useBackendStatus';
import { useAirport } from '../state/AirportContext';
import { useOperatingDate } from '../state/OperatingDate';
import { useSystemStatus } from '../state/SystemStatus';
import { formatDateLong } from '../utils/format';
import {
  MODE_LABELS,
  ago,
  feedName,
  feedStatusLabel,
  feedTone,
  modeTone,
  monitoringLabel,
  monitoringTone,
  sourceLabel,
} from '../utils/liveData';

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
  'GET /api/system/status',
  'GET /api/airports/{iata}/live-aircraft',
  'GET /api/airports/{iata}/weather',
];

/** Read-only environment information; nothing here changes backend state. */
export function SettingsPage({ status }: { status: BackendStatus }) {
  const { date, dataWindow, network } = useOperatingDate();
  const { user, airport } = useAirport();
  const pipeline = useSystemStatus();
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
            <dt>Sources on this date</dt>
            <dd>{dataSources(network.data).map((source) => sourceLabel(source)).join(', ') || '—'}</dd>
          </dl>
          {network.data?.data_notice && <p className="details__muted">{network.data.data_notice}</p>}
          <p className="details__muted">
            To change the backend location set <span className="mono">BACKEND_URL</span> for the dev server or the nginx
            container, or <span className="mono">VITE_API_BASE_URL</span> at build time.
          </p>
        </Card>
        <Card title="Data sources">
          {pipeline.data ? (
            <>
              <dl className="kv kv--wide" data-testid="settings-data-mode">
                <dt>Data mode</dt>
                <dd>
                  <StatusBadge tone={modeTone(pipeline.data.data_mode)}>{MODE_LABELS[pipeline.data.data_mode]}</StatusBadge>
                  <span className="kv__muted"> {pipeline.data.data_mode_description}</span>
                </dd>
                {pipeline.data.feeds.map((feed) => (
                  <FeedRow key={feed.feed} feed={feed} />
                ))}
                <dt>Disruption monitor</dt>
                <dd>
                  <StatusBadge tone={monitoringTone(pipeline.data.monitoring.status)}>{monitoringLabel(pipeline.data.monitoring.status)}</StatusBadge>
                  <span className="kv__muted">
                    {pipeline.data.monitoring.last_run_at
                      ? ` last check ${ago(pipeline.data.monitoring.last_run_at)}: ${pipeline.data.monitoring.detected ?? 0} detected, ${pipeline.data.monitoring.opened ?? 0} new, ${pipeline.data.monitoring.cleared ?? 0} closed`
                      : ' no check yet'}
                  </span>
                </dd>
                <dt>Database</dt>
                <dd>{pipeline.data.database.status === 'UP' ? 'Connected' : 'Not reachable'}</dd>
                <dt>Prediction service</dt>
                <dd>{pipeline.data.ml_service.status === 'UP' ? 'Connected' : `Not reachable${pipeline.data.ml_service.detail ? ` (${pipeline.data.ml_service.detail})` : ''}`}</dd>
              </dl>
              <p className="details__muted">
                The mode is set on the backend with <span className="mono">AEROSYNC_DATA_MODE</span>. Synthetic records are
                never shown as live; every flight carries its source.
              </p>
            </>
          ) : (
            <p className="details__muted">{pipeline.loading ? 'Checking data sources…' : 'Data source status is unavailable.'}</p>
          )}
        </Card>
      </div>
    </>
  );
}

function FeedRow({ feed }: { feed: FeedInfo }) {
  return (
    <>
      <dt>{feedName(feed.feed)}</dt>
      <dd>
        <StatusBadge tone={feedTone(feed.status)}>{feedStatusLabel(feed.status)}</StatusBadge>
        <span className="kv__muted">
          {' '}{sourceLabel(feed.data_source)}
          {feed.last_success_at ? ` · last success ${ago(feed.last_success_at)}` : ''}
          {feed.interval_seconds && feed.enabled ? ` · every ${Math.round(feed.interval_seconds / 60)} min` : ''}
          {feed.authenticated === false && feed.enabled ? ' · anonymous access' : ''}
          {feed.last_run ? ` · last run ${feed.last_run.records_inserted} new, ${feed.last_run.records_updated} updated, ${feed.last_run.records_skipped} skipped${feed.last_run.skip_details ? ` (${feed.last_run.skip_details})` : ''}` : ''}
          {feed.status !== 'LIVE' && feed.last_error ? ` · ${feed.last_error}` : ''}
        </span>
      </dd>
    </>
  );
}
