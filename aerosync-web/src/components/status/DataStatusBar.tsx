import { LuRefreshCw, LuTriangleAlert } from 'react-icons/lu';
import type { FeedInfo, SystemStatus } from '../../api/types';
import type { PollState } from '../../hooks/poller';
import { STATUS_POLL_MS, useSystemStatus } from '../../state/SystemStatus';
import {
  MODE_LABELS,
  ago,
  clockTime,
  feedName,
  feedStatusLabel,
  feedSummary,
  feedTone,
  isSnapshotStale,
  modeTone,
  monitoringLabel,
  monitoringTone,
} from '../../utils/liveData';
import { StatusBadge } from '../common/StatusBadge';

/**
 * Strip under the top bar: data mode, the state and last success of every
 * external feed, the disruption monitor, and when this information was last
 * refreshed. Everything shown comes from GET /api/system/status.
 */
export function DataStatusBar() {
  const status = useSystemStatus();
  return <DataStatusView state={status} onRefresh={status.refresh} now={new Date()} />;
}

function FeedChip({ feed, now }: { feed: FeedInfo; now: Date }) {
  const detail = feed.last_success_at ? ago(feed.last_success_at, now) : feed.enabled ? 'no data yet' : null;
  return (
    <span className="status-chip" title={`${feed.description}. ${feedSummary(feed, now)} (source: ${feed.data_source})`} data-testid={`feed-${feed.feed}`}>
      <span className="status-chip__label">{feedName(feed.feed)}</span>
      <StatusBadge tone={feedTone(feed.status)}>{feedStatusLabel(feed.status)}</StatusBadge>
      {detail && <span className="status-chip__detail">{detail}</span>}
    </span>
  );
}

/** Presentational part, separate so it can be rendered in tests without the provider. */
export function DataStatusView({ state, onRefresh, now }: {
  state: PollState<SystemStatus>;
  onRefresh?: () => void;
  now: Date;
}) {
  const { data, error, loading, lastUpdated } = state;
  if (!data) {
    return (
      <div className="status-bar" role="status" data-testid="data-status">
        {loading ? (
          <span className="status-bar__muted">Checking data sources…</span>
        ) : (
          <span className="status-bar__warning" data-testid="status-unavailable">
            <LuTriangleAlert aria-hidden /> Data source status is unavailable. Live and synthetic data cannot be told apart right now.
          </span>
        )}
      </div>
    );
  }

  // Out of date when the last refresh failed or is long overdue: say so instead of looking current.
  const outdated = Boolean(error) || isSnapshotStale(lastUpdated, STATUS_POLL_MS, now);
  const monitoring = data.monitoring;
  return (
    <div className="status-bar" role="status" data-testid="data-status">
      <StatusBadge tone={modeTone(data.data_mode)} title={data.data_mode_description}>
        <span data-testid="data-mode">{MODE_LABELS[data.data_mode] ?? data.data_mode}</span>
      </StatusBadge>
      {data.feeds.map((feed) => <FeedChip key={feed.feed} feed={feed} now={now} />)}
      <span
        className="status-chip"
        data-testid="monitoring-status"
        title={monitoring.enabled
          ? `Disruption monitor: last check ${ago(monitoring.last_run_at, now)}${monitoring.last_error ? ` · ${monitoring.last_error}` : ''}. It only detects; nothing is executed without approval.`
          : 'The disruption monitor is switched off.'}
      >
        <span className="status-chip__label">Monitor</span>
        <StatusBadge tone={monitoringTone(monitoring.status)}>{monitoringLabel(monitoring.status)}</StatusBadge>
        {monitoring.enabled && monitoring.last_run_at && (
          <span className="status-chip__detail">checked {ago(monitoring.last_run_at, now)}</span>
        )}
      </span>
      {data.database.status === 'DOWN' && <StatusBadge tone="danger">Database down</StatusBadge>}
      {data.ml_service.status === 'DOWN' && (
        <StatusBadge tone="warning" title={data.ml_service.detail ?? 'Delay predictions use the fallback until it is back.'}>
          Prediction service down
        </StatusBadge>
      )}
      <span className="status-bar__spacer" />
      {outdated ? (
        <span className="status-bar__warning" data-testid="status-stale" title="The status could not be refreshed; the values shown may be out of date.">
          <LuTriangleAlert aria-hidden /> Not refreshed since {clockTime(lastUpdated)}
        </span>
      ) : (
        <span className="status-bar__muted" data-testid="status-refreshed">Refreshed {clockTime(lastUpdated)}</span>
      )}
      {onRefresh && (
        <button type="button" className="icon-button icon-button--small" onClick={onRefresh} aria-label="Refresh data source status" title="Refresh now">
          <LuRefreshCw aria-hidden />
        </button>
      )}
    </div>
  );
}
