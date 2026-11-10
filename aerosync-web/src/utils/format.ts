import type { AirportStatus, DependencyType, FlightStatus, RelationshipType } from '../api/types';

export type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger';

/** Departure delay (minutes) from which a flight counts as delayed; same threshold as the backend summary. */
export const DELAY_THRESHOLD_MINUTES = 15;

const LONG_DATE = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' });
const WEEKDAY_DATE = new Intl.DateTimeFormat('en-US', {
  weekday: 'short', month: 'short', day: 'numeric', timeZone: 'UTC',
});

function parseIsoDate(date: string): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  if (!match) return null;
  return new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
}

/** "2026-11-09" -> "Nov 9, 2026" */
export function formatDateLong(date: string | null | undefined): string {
  if (!date) return '—';
  const parsed = parseIsoDate(date);
  return parsed ? LONG_DATE.format(parsed) : date;
}

/** "2026-11-09" -> "Mon, Nov 9" */
export function formatDateShort(date: string | null | undefined): string {
  if (!date) return '—';
  const parsed = parseIsoDate(date);
  return parsed ? WEEKDAY_DATE.format(parsed) : date;
}

/** Current date (yyyy-MM-dd) and minutes after midnight in an IANA time zone. */
export function nowInZone(timeZone: string, now: Date = new Date()): { date: string; minutes: number } {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).formatToParts(now);
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? '00';
  return { date: `${get('year')}-${get('month')}-${get('day')}`, minutes: Number(get('hour')) * 60 + Number(get('minute')) };
}

/** Adds days to a yyyy-MM-dd date. */
export function shiftDate(date: string, days: number): string {
  const parsed = parseIsoDate(date);
  if (!parsed) return date;
  parsed.setUTCDate(parsed.getUTCDate() + days);
  return parsed.toISOString().slice(0, 10);
}

/**
 * "HH:mm" of an ISO timestamp that already carries the local offset
 * (the backend's *_local fields), e.g. "2026-11-09T07:00:00+05:30" -> "07:00".
 */
export function localClock(localIso: string | null | undefined): string {
  if (!localIso) return '—';
  const match = /T(\d{2}):(\d{2})/.exec(localIso);
  return match ? `${match[1]}:${match[2]}` : '—';
}

/** Minutes after local midnight of a *_local timestamp, or null. */
export function localMinutes(localIso: string | null | undefined): number | null {
  if (!localIso) return null;
  const match = /T(\d{2}):(\d{2})/.exec(localIso);
  return match ? Number(match[1]) * 60 + Number(match[2]) : null;
}

/** "HH:mm" of a UTC instant in an IANA time zone (for estimated/actual times). */
export function clockInZone(instant: string | null | undefined, timeZone: string): string {
  if (!instant) return '—';
  const date = new Date(instant);
  if (Number.isNaN(date.getTime())) return '—';
  return new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone }).format(date);
}

/** Block time between two instants: "1h 40m". */
export function durationLabel(fromIso: string, toIso: string): string {
  const minutes = Math.round((new Date(toIso).getTime() - new Date(fromIso).getTime()) / 60000);
  if (!Number.isFinite(minutes) || minutes < 0) return '—';
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return hours > 0 ? `${hours}h ${String(rest).padStart(2, '0')}m` : `${rest}m`;
}

export function isDelayed(status: FlightStatus, delayMinutes: number): boolean {
  return status === 'DELAYED' || delayMinutes >= DELAY_THRESHOLD_MINUTES;
}

export function delayLabel(delayMinutes: number | null | undefined): string {
  if (!delayMinutes || delayMinutes <= 0) return 'No delay';
  return `+${delayMinutes} min`;
}

export function titleCase(value: string): string {
  return value
    .toLowerCase()
    .split('_')
    .map((word) => (word ? word[0].toUpperCase() + word.slice(1) : word))
    .join(' ');
}

export function flightStatusTone(status: FlightStatus, delayMinutes = 0): Tone {
  if (status === 'CANCELLED' || status === 'DIVERTED') return 'danger';
  if (isDelayed(status, delayMinutes)) return 'warning';
  if (status === 'ARRIVED' || status === 'DEPARTED' || status === 'BOARDING') return 'info';
  return 'success';
}

export function airportStatusTone(status: AirportStatus): Tone {
  if (status === 'CLOSED') return 'danger';
  if (status === 'RESTRICTED') return 'warning';
  return 'success';
}

const RELATIONSHIP_LABELS: Record<RelationshipType, string> = {
  DEPARTS_FROM: 'Departs from',
  ARRIVES_AT: 'Arrives at',
  AIRCRAFT_ROTATION: 'Aircraft rotation',
  PASSENGER_CONNECTION: 'Passenger connection',
  CREW_ROTATION: 'Crew rotation',
  OPERATIONAL_CONSTRAINT: 'Operational constraint',
};

const DEPENDENCY_SHORT: Record<DependencyType, string> = {
  AIRCRAFT_ROTATION: 'Rotation',
  PASSENGER_CONNECTION: 'Connection',
  CREW_ROTATION: 'Crew',
  OPERATIONAL_CONSTRAINT: 'Constraint',
};

export function relationshipLabel(type: RelationshipType): string {
  return RELATIONSHIP_LABELS[type] ?? titleCase(type);
}

export function dependencyShortLabel(type: DependencyType): string {
  return DEPENDENCY_SHORT[type] ?? titleCase(type);
}

export function pluralize(count: number, singular: string, plural = `${singular}s`): string {
  return `${count} ${count === 1 ? singular : plural}`;
}

export function formatNumber(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : new Intl.NumberFormat('en-US').format(value);
}
