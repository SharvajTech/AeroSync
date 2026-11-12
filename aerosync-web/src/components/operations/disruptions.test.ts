import { describe, expect, it } from 'vitest';
import type { Disruption } from '../../api/types';
import { detectedDisruption } from '../../test/fixtures';
import { canActOn, disruptionRoute, disruptionTone, sortDisruptions } from './DisruptionsPanel';

function disruption(id: number, status: Disruption['status'], detectedAt: string): Disruption {
  return {
    id,
    flight_id: `AI-${id}`,
    type: 'WEATHER_DELAY',
    status,
    description: null,
    delay_minutes: 60,
    passengers_affected: 100,
    cost_impact: null,
    detected_at: detectedAt,
    resolved_at: null,
  };
}

describe('disruptions panel helpers', () => {
  it('lists open disruptions first, newest first', () => {
    const rows = sortDisruptions([
      disruption(1, 'RESOLVED', '2026-11-10T09:00:00'),
      disruption(2, 'DETECTED', '2026-11-10T07:00:00'),
      disruption(3, 'PROPOSED', '2026-11-10T08:00:00'),
    ]);
    expect(rows.map((r) => r.id)).toEqual([3, 2, 1]);
  });

  it('uses amber for open and green for resolved disruptions', () => {
    expect(disruptionTone('DETECTED')).toBe('warning');
    expect(disruptionTone('PROPOSED')).toBe('info');
    expect(disruptionTone('RESOLVED')).toBe('success');
    expect(disruptionTone('FAILED')).toBe('danger');
  });
});

// ── Stage 4: detected disruptions ───────────────────────────────────────────

describe('detected disruptions', () => {
  const flights = new Map([['AI-7', { origin: 'PNQ', destination: 'BOM' }]]);

  it('takes the route from the disruption itself, else from the flight list', () => {
    expect(disruptionRoute(detectedDisruption(1), flights)).toEqual({ origin: 'BOM', destination: 'DEL' });
    expect(disruptionRoute(detectedDisruption(7, { origin_airport: null, destination_airport: null }), flights))
      .toEqual({ origin: 'PNQ', destination: 'BOM' });
    expect(disruptionRoute(detectedDisruption(8, { origin_airport: null }), flights)).toBeUndefined();
  });

  it('offers the workflow only to controllers at the departure airport', () => {
    const row = detectedDisruption(1);
    const route = disruptionRoute(row, flights);
    expect(canActOn(row, route, 'BOM', true)).toBe(true);
    // Read-only role.
    expect(canActOn(row, route, 'BOM', false)).toBe(false);
    // The flight departs from another airport (visible here because it arrives).
    expect(canActOn(row, route, 'DEL', true)).toBe(false);
    // Route unknown: nothing is offered.
    expect(canActOn(row, undefined, 'BOM', true)).toBe(false);
    expect(canActOn(detectedDisruption(2, { status: 'RESOLVED' }), route, 'BOM', true)).toBe(false);
  });

  it('never offers actions on advisory disruptions of network flights', () => {
    const advisory = detectedDisruption(3, { type: 'TURNAROUND_CONFLICT', actionable: false, flight_instance_id: 'fi-AI101', detection_rule: 'TURNAROUND_CONFLICT' });
    expect(canActOn(advisory, disruptionRoute(advisory, flights), 'BOM', true)).toBe(false);
  });

  it('keeps disruptions from before Stage 4 working', () => {
    const legacy = disruption(9, 'DETECTED', '2026-11-10T07:00:00');
    expect(disruptionRoute(legacy, new Map([['AI-9', { origin: 'BOM', destination: 'DEL' }]]))?.origin).toBe('BOM');
    expect(canActOn(legacy, { origin: 'BOM', destination: 'DEL' }, 'BOM', true)).toBe(true);
  });
});
