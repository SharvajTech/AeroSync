import type { AirportStatus, AirportSummary, FlightNode, FlightStatus } from '../api/types';
import { isDelayed, localMinutes } from './format';

/** Local-time window [startMinute, endMinute) after midnight, e.g. 05:00-08:00. */
export interface TimeWindow {
  startMinute: number;
  endMinute: number;
}

export const ALL_DAY: TimeWindow = { startMinute: 0, endMinute: 24 * 60 };

/** Two-hour departure banks offered by the graph views (the first covers the early morning). */
export const TIME_WINDOWS: readonly TimeWindow[] = [
  { startMinute: 0, endMinute: 7 * 60 },
  ...[7, 9, 11, 13, 15, 17, 19].map((hour) => ({ startMinute: hour * 60, endMinute: (hour + 2) * 60 })),
  { startMinute: 21 * 60, endMinute: 24 * 60 },
];

/** The window containing `minute` (minutes after local midnight). */
export function windowContaining(minute: number): TimeWindow {
  return TIME_WINDOWS.find((w) => minute >= w.startMinute && minute < w.endMinute) ?? TIME_WINDOWS[0];
}

export function windowLabel(window: TimeWindow): string {
  if (window.startMinute === 0 && window.endMinute === 24 * 60) return 'All day';
  const clock = (m: number) => `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
  return `${clock(window.startMinute)}–${clock(window.endMinute)}`;
}

export function windowKey(window: TimeWindow): string {
  return `${window.startMinute}-${window.endMinute}`;
}

export function inWindow(localIso: string | null | undefined, window: TimeWindow): boolean {
  const minutes = localMinutes(localIso);
  return minutes !== null && minutes >= window.startMinute && minutes < window.endMinute;
}

export type DelayFilter = 'all' | 'on_time' | 'delayed';

export interface FlightFilters {
  search: string;
  airport: string;
  status: FlightStatus | 'all';
  delay: DelayFilter;
  airline: string;
}

export const EMPTY_FLIGHT_FILTERS: FlightFilters = { search: '', airport: '', status: 'all', delay: 'all', airline: '' };

export function filterFlights(flights: readonly FlightNode[], filters: FlightFilters): FlightNode[] {
  const search = filters.search.trim().toUpperCase();
  return flights.filter((flight) => {
    if (search) {
      const haystack = [
        flight.flight_number,
        flight.aircraft?.registration ?? '',
        `${flight.origin_airport}-${flight.destination_airport}`,
      ].join(' ').toUpperCase();
      if (!haystack.includes(search)) return false;
    }
    if (filters.airport && flight.origin_airport !== filters.airport && flight.destination_airport !== filters.airport) {
      return false;
    }
    if (filters.status !== 'all' && flight.status !== filters.status) return false;
    if (filters.airline && flight.operating_airline !== filters.airline) return false;
    if (filters.delay === 'delayed' && !isDelayed(flight.status, flight.delay_minutes)) return false;
    if (filters.delay === 'on_time' && isDelayed(flight.status, flight.delay_minutes)) return false;
    return true;
  });
}

export function sortFlightsByDeparture(flights: readonly FlightNode[]): FlightNode[] {
  return [...flights].sort((a, b) =>
    a.scheduled_departure.localeCompare(b.scheduled_departure) || a.flight_number.localeCompare(b.flight_number));
}

export interface AirportFilters {
  search: string;
  status: AirportStatus | 'all';
}

export function filterAirports<T extends Pick<AirportSummary, 'iata_code' | 'airport_name' | 'city' | 'status'>>(
  airports: readonly T[], filters: AirportFilters,
): T[] {
  const search = filters.search.trim().toLowerCase();
  return airports.filter((airport) => {
    if (filters.status !== 'all' && airport.status !== filters.status) return false;
    if (!search) return true;
    return [airport.iata_code, airport.airport_name, airport.city].some((v) => v.toLowerCase().includes(search));
  });
}
