import { describe, expect, it } from 'vitest';
import {
  clockInZone,
  delayLabel,
  durationLabel,
  flightStatusTone,
  formatDateLong,
  formatDateShort,
  isDelayed,
  localClock,
  nowInZone,
  pluralize,
  relationshipLabel,
  shiftDate,
  titleCase,
} from './format';

describe('dates', () => {
  it('formats operating dates without shifting the day', () => {
    expect(formatDateLong('2026-11-09')).toBe('Nov 9, 2026');
    expect(formatDateShort('2026-11-09')).toBe('Mon, Nov 9');
    expect(formatDateLong(null)).toBe('—');
  });

  it('shifts dates across month and year boundaries', () => {
    expect(shiftDate('2026-11-30', 1)).toBe('2026-12-01');
    expect(shiftDate('2027-01-01', -1)).toBe('2026-12-31');
  });

  it('computes the date and time of day in a time zone', () => {
    const instant = new Date('2026-11-08T20:00:00Z');
    expect(nowInZone('Asia/Kolkata', instant)).toEqual({ date: '2026-11-09', minutes: 90 });
  });
});

describe('times', () => {
  it('reads the local clock of *_local timestamps', () => {
    expect(localClock('2026-11-09T07:05:00+05:30')).toBe('07:05');
    expect(localClock(null)).toBe('—');
  });

  it('converts instants into an airport time zone', () => {
    expect(clockInZone('2026-11-09T01:30:00Z', 'Asia/Kolkata')).toBe('07:00');
    expect(clockInZone(null, 'Asia/Kolkata')).toBe('—');
  });

  it('labels block time', () => {
    expect(durationLabel('2026-11-09T01:30:00Z', '2026-11-09T03:10:00Z')).toBe('1h 40m');
    expect(durationLabel('2026-11-09T01:30:00Z', '2026-11-09T02:05:00Z')).toBe('35m');
  });
});

describe('status', () => {
  it('treats DELAYED or 15+ minutes as delayed', () => {
    expect(isDelayed('SCHEDULED', 14)).toBe(false);
    expect(isDelayed('SCHEDULED', 15)).toBe(true);
    expect(isDelayed('DELAYED', 0)).toBe(true);
  });

  it('uses red only for cancelled or diverted flights and amber for delays', () => {
    expect(flightStatusTone('SCHEDULED')).toBe('success');
    expect(flightStatusTone('SCHEDULED', 20)).toBe('warning');
    expect(flightStatusTone('CANCELLED')).toBe('danger');
    expect(flightStatusTone('DEPARTED')).toBe('info');
  });

  it('formats labels', () => {
    expect(delayLabel(0)).toBe('No delay');
    expect(delayLabel(25)).toBe('+25 min');
    expect(titleCase('PASSENGER_CONNECTION')).toBe('Passenger Connection');
    expect(relationshipLabel('DEPARTS_FROM')).toBe('Departs from');
    expect(pluralize(1, 'flight')).toBe('1 flight');
    expect(pluralize(3, 'dependency', 'dependencies')).toBe('3 dependencies');
  });
});
