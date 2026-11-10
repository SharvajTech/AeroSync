import { describe, expect, it } from 'vitest';
import type { Disruption } from '../../api/types';
import { disruptionTone, sortDisruptions } from './DisruptionsPanel';

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
