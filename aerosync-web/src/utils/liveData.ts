import type {
  AircraftPhase,
  DataMode,
  DataOrigin,
  Disruption,
  DisruptionSeverity,
  FeedInfo,
  FeedStatus,
  FlightProvenance,
  MonitoringInfo,
} from '../api/types';
import type { Tone } from './format';

/**
 * Presentation helpers for Stage 4 data: data mode, feed freshness,
 * provenance and disruption detection. Pure functions; nothing here invents
 * a value the backend did not send.
 */

export const MODE_LABELS: Record<DataMode, string> = {
  SYNTHETIC: 'Synthetic data',
  LIVE: 'Live data',
  HYBRID: 'Hybrid data',
};

export function modeTone(mode: DataMode): Tone {
  if (mode === 'LIVE') return 'success';
  return mode === 'HYBRID' ? 'info' : 'neutral';
}

const FEED_NAMES: Record<string, string> = {
  LIVE_STATES: 'Live aircraft',
  FLIGHT_LEGS: 'Flight records',
  WEATHER: 'Weather',
};

export function feedName(feed: string): string {
  return FEED_NAMES[feed] ?? feed;
}

const FEED_STATUS_LABELS: Record<FeedStatus, string> = {
  LIVE: 'Live',
  STALE: 'Stale',
  FAILING: 'Failing',
  RATE_LIMITED: 'Rate limited',
  WAITING: 'Waiting',
  DISABLED: 'Off',
  UNCONFIGURED: 'Not configured',
};

export function feedStatusLabel(status: FeedStatus): string {
  return FEED_STATUS_LABELS[status] ?? status;
}

export function feedTone(status: FeedStatus): Tone {
  switch (status) {
    case 'LIVE':
      return 'success';
    case 'STALE':
    case 'RATE_LIMITED':
      return 'warning';
    case 'FAILING':
      return 'danger';
    case 'WAITING':
      return 'info';
    default:
      return 'neutral';
  }
}

export function monitoringTone(status: MonitoringInfo['status']): Tone {
  if (status === 'RUNNING') return 'success';
  if (status === 'FAILING') return 'danger';
  if (status === 'STALE') return 'warning';
  return status === 'WAITING' ? 'info' : 'neutral';
}

export function monitoringLabel(status: MonitoringInfo['status']): string {
  switch (status) {
    case 'RUNNING':
      return 'Running';
    case 'FAILING':
      return 'Failing';
    case 'STALE':
      return 'Stale';
    case 'WAITING':
      return 'Starting';
    default:
      return 'Off';
  }
}

/** "just now", "4 min ago", "3 h ago", "2 d ago"; "never" without a timestamp. */
export function ago(iso: string | null | undefined, now: Date = new Date()): string {
  if (!iso) return 'never';
  const then = Date.parse(iso);
  if (Number.isNaN(then)) return 'unknown';
  const seconds = Math.max(0, Math.round((now.getTime() - then) / 1000));
  if (seconds < 60) return 'just now';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 48) return `${hours} h ago`;
  return `${Math.floor(hours / 24)} d ago`;
}

/** Local wall-clock time "HH:mm:ss" of a Date or ISO timestamp; "—" when missing. */
export function clockTime(value: Date | string | null | undefined): string {
  if (!value) return '—';
  const date = typeof value === 'string' ? new Date(value) : value;
  if (Number.isNaN(date.getTime())) return '—';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

/**
 * Whether what is on screen is out of date: the last successful refresh is
 * older than two and a half polling intervals (one missed poll is tolerated).
 */
export function isSnapshotStale(lastUpdated: Date | null, intervalMs: number, now: Date = new Date()): boolean {
  if (!lastUpdated) return false;
  return now.getTime() - lastUpdated.getTime() > intervalMs * 2.5;
}

/** One line for a feed: status, last success and why it is not live. */
export function feedSummary(feed: FeedInfo, now: Date = new Date()): string {
  const parts = [`${feedName(feed.feed)}: ${feedStatusLabel(feed.status)}`];
  if (feed.last_success_at) parts.push(`last success ${ago(feed.last_success_at, now)}`);
  else if (feed.enabled) parts.push('no successful run yet');
  if (feed.status !== 'LIVE' && feed.last_error) parts.push(feed.last_error);
  return parts.join(' · ');
}

const SOURCE_LABELS: Record<string, string> = {
  SYNTHETIC_GENERATOR: 'Synthetic',
  MANUAL_ENTRY: 'Manual entry',
  EXTERNAL_IMPORT: 'External import',
  OPENSKY: 'OpenSky',
  OPENWEATHERMAP: 'OpenWeatherMap',
};

/** Human name of a data source; unknown sources are shown as they are. */
export function sourceLabel(dataSource: string | null | undefined, origin?: DataOrigin | null): string {
  if (dataSource) return SOURCE_LABELS[dataSource] ?? dataSource;
  if (origin === 'IMPORTED') return 'External import';
  if (origin === 'MANUAL') return 'Manual entry';
  return 'Synthetic';
}

/** Synthetic records are neutral, external ones stand out, so the two are never confused. */
export function originTone(origin: DataOrigin | null | undefined): Tone {
  if (origin === 'IMPORTED') return 'success';
  return origin === 'MANUAL' ? 'info' : 'neutral';
}

export function originLabel(origin: DataOrigin | null | undefined): string {
  if (origin === 'IMPORTED') return 'External';
  return origin === 'MANUAL' ? 'Manual' : 'Synthetic';
}

/** True when the flight has no timetable: its "scheduled" times are observed times and no delay is known. */
export function hasObservedTimes(provenance: FlightProvenance | null | undefined): boolean {
  return provenance?.schedule_basis === 'OBSERVED';
}

export function severityTone(severity: DisruptionSeverity | null | undefined): Tone {
  switch (severity) {
    case 'CRITICAL':
    case 'HIGH':
      return 'danger';
    case 'MEDIUM':
      return 'warning';
    case 'LOW':
      return 'info';
    default:
      return 'neutral';
  }
}

/** "85%"; "—" when the backend sent no confidence. */
export function confidencePercent(confidence: number | null | undefined): string {
  if (confidence === null || confidence === undefined || Number.isNaN(confidence)) return '—';
  return `${Math.round(Math.min(1, Math.max(0, confidence)) * 100)}%`;
}

const RULE_LABELS: Record<string, string> = {
  WEATHER_OBSERVED: 'observed weather',
  CREW_DUTY_LIMIT: 'crew duty hours',
  ML_DELAY_RISK: 'delay model',
  NETWORK_FLIGHT_DELAY: 'recorded delay',
  TURNAROUND_CONFLICT: 'aircraft turnaround',
  CREW_ROTATION_CONFLICT: 'crew rotation',
  MISSED_CONNECTION: 'passenger connection',
  CASCADING_DELAY: 'cascading delay',
  AIRCRAFT_UNAVAILABLE: 'aircraft availability',
};

/** Who raised a disruption, e.g. "Monitor · observed weather" or "Reported by a controller". */
export function detectionSourceLabel(disruption: Pick<Disruption, 'detection_source' | 'detection_rule'>): string {
  switch (disruption.detection_source) {
    case 'MONITOR': {
      const rule = disruption.detection_rule;
      return rule ? `Monitor · ${RULE_LABELS[rule] ?? rule}` : 'Monitor';
    }
    case 'MANUAL':
      return 'Reported by a controller';
    case 'SIMULATION':
      return 'Simulation';
    case 'SYSTEM':
      return 'Raised by a resolution (cascade)';
    default:
      return 'Not recorded';
  }
}

/** A detected disruption counts as new for this long (the Operations page marks it). */
export const NEW_DETECTION_MINUTES = 15;

export function isNewDetection(
  disruption: Pick<Disruption, 'status' | 'detected_at'>,
  now: Date = new Date(),
): boolean {
  if (disruption.status !== 'DETECTED' || !disruption.detected_at) return false;
  const detected = Date.parse(disruption.detected_at);
  if (Number.isNaN(detected)) return false;
  const minutes = (now.getTime() - detected) / 60_000;
  return minutes >= 0 && minutes <= NEW_DETECTION_MINUTES;
}

/** Advisory disruptions (operational-network flights) have nothing to propose or approve. */
export function isAdvisory(disruption: Pick<Disruption, 'actionable' | 'flight_instance_id'>): boolean {
  return disruption.actionable === false || Boolean(disruption.flight_instance_id);
}

const PHASE_LABELS: Record<AircraftPhase, string> = {
  ON_GROUND: 'On ground',
  AIRBORNE: 'Airborne',
  CLIMBING: 'Climbing',
  DESCENDING: 'Descending',
  LEVEL: 'Level',
};

export function phaseLabel(phase: AircraftPhase): string {
  return PHASE_LABELS[phase] ?? phase;
}

/** Metres to feet, rounded to the nearest 100 ft; "—" when not reported. */
export function altitudeFeet(metres: number | null | undefined): string {
  if (metres === null || metres === undefined) return '—';
  return `${(Math.round((metres * 3.28084) / 100) * 100).toLocaleString('en-US')} ft`;
}

/** m/s to knots; "—" when not reported. */
export function speedKnots(metresPerSecond: number | null | undefined): string {
  if (metresPerSecond === null || metresPerSecond === undefined) return '—';
  return `${Math.round(metresPerSecond * 1.94384)} kt`;
}

/** "45 s", "3 min" */
export function ageLabel(seconds: number): string {
  return seconds < 90 ? `${Math.max(0, Math.round(seconds))} s` : `${Math.round(seconds / 60)} min`;
}

export function weatherTone(score: number): Tone {
  if (score >= 3) return 'danger';
  if (score === 2) return 'warning';
  return score === 1 ? 'info' : 'success';
}
