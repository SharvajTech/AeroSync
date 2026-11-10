import { describe, expect, it } from 'vitest';
import { AI101, AI202, AI505, BLR, BOM, DEL, E6100, HYD } from '../test/fixtures';
import {
  ALL_DAY,
  EMPTY_FLIGHT_FILTERS,
  TIME_WINDOWS,
  filterAirports,
  filterFlights,
  inWindow,
  sortFlightsByDeparture,
  windowContaining,
  windowLabel,
} from './filters';

const FLIGHTS = [E6100, AI202, AI101, AI505];

describe('time windows', () => {
  it('cover the whole day without gaps or overlaps', () => {
    expect(TIME_WINDOWS[0].startMinute).toBe(0);
    expect(TIME_WINDOWS[TIME_WINDOWS.length - 1].endMinute).toBe(24 * 60);
    for (let i = 1; i < TIME_WINDOWS.length; i++) {
      expect(TIME_WINDOWS[i].startMinute).toBe(TIME_WINDOWS[i - 1].endMinute);
    }
  });

  it('finds the window of a time of day', () => {
    expect(windowLabel(windowContaining(7 * 60))).toBe('07:00–09:00');
    expect(windowLabel(windowContaining(8 * 60 + 59))).toBe('07:00–09:00');
    expect(windowLabel(windowContaining(23 * 60 + 59))).toBe('21:00–24:00');
    expect(windowLabel(ALL_DAY)).toBe('All day');
  });

  it('checks local timestamps against a window (end exclusive)', () => {
    const morning = { startMinute: 420, endMinute: 540 };
    expect(inWindow('2026-11-09T07:00:00+05:30', morning)).toBe(true);
    expect(inWindow('2026-11-09T09:00:00+05:30', morning)).toBe(false);
    expect(inWindow(null, morning)).toBe(false);
  });
});

describe('filterFlights', () => {
  it('returns everything with empty filters', () => {
    expect(filterFlights(FLIGHTS, EMPTY_FLIGHT_FILTERS)).toHaveLength(4);
  });

  it('searches flight number, registration and city pair', () => {
    const search = (text: string) => filterFlights(FLIGHTS, { ...EMPTY_FLIGHT_FILTERS, search: text }).map((f) => f.flight_number);
    expect(search('ai1')).toEqual(['AI101']);
    expect(search('VT-A01')).toEqual(['AI101', 'AI505']);
    expect(search('blr-del')).toEqual(['AI202']);
  });

  it('filters by airport (either end), status, airline and delay', () => {
    expect(filterFlights(FLIGHTS, { ...EMPTY_FLIGHT_FILTERS, airport: 'BOM' }).map((f) => f.flight_number)).toEqual(['6E100', 'AI101']);
    expect(filterFlights(FLIGHTS, { ...EMPTY_FLIGHT_FILTERS, status: 'DELAYED' }).map((f) => f.flight_number)).toEqual(['6E100']);
    expect(filterFlights(FLIGHTS, { ...EMPTY_FLIGHT_FILTERS, airline: 'AI' })).toHaveLength(3);
    expect(filterFlights(FLIGHTS, { ...EMPTY_FLIGHT_FILTERS, delay: 'delayed' }).map((f) => f.flight_number)).toEqual(['6E100']);
    expect(filterFlights(FLIGHTS, { ...EMPTY_FLIGHT_FILTERS, delay: 'on_time' })).toHaveLength(3);
  });

  it('sorts by scheduled departure', () => {
    expect(sortFlightsByDeparture(FLIGHTS).map((f) => f.flight_number)).toEqual(['AI101', 'AI505', 'AI202', '6E100']);
  });
});

describe('filterAirports', () => {
  const airports = [BOM, BLR, DEL, HYD];

  it('matches code, name or city case-insensitively', () => {
    expect(filterAirports(airports, { search: 'mum', status: 'all' }).map((a) => a.iata_code)).toEqual(['BOM']);
    expect(filterAirports(airports, { search: 'del', status: 'all' }).map((a) => a.iata_code)).toEqual(['DEL']);
  });

  it('filters by status', () => {
    expect(filterAirports(airports, { search: '', status: 'RESTRICTED' }).map((a) => a.iata_code)).toEqual(['HYD']);
  });
});
