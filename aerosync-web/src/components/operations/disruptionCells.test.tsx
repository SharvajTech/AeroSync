import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Disruption } from '../../api/types';
import { detectedDisruption } from '../../test/fixtures';
import { DisruptionSummaryCells } from './DisruptionsPanel';

const NOW = new Date(2026, 10, 10, 10, 0, 0);

function render(row: Disruption): string {
  return renderToStaticMarkup(
    <table><tbody><tr><DisruptionSummaryCells row={row} now={NOW} /></tr></tbody></table>,
  );
}

describe('disruption row', () => {
  it('shows type, severity, confidence, source and detection time of a detected disruption', () => {
    const html = render(detectedDisruption(1));
    expect(html).toContain('Weather Delay');
    expect(html).toContain('Observed weather at BOM may delay the flight.');
    expect(html).toContain('badge--danger');
    expect(html).toContain('>High<');
    expect(html).toContain('85% confidence');
    expect(html).toContain('90 min');
    expect(html).toContain('Monitor · observed weather');
    expect(html).toContain('5 min ago');
    expect(html).toContain('data-testid="new-detection"');
  });

  it('does not mark older or already handled disruptions as new', () => {
    expect(render(detectedDisruption(2, { detected_at: '2026-11-10T08:00:00' })).includes('data-testid="new-detection"')).toBe(false);
    expect(render(detectedDisruption(3, { status: 'PROPOSED' })).includes('data-testid="new-detection"')).toBe(false);
  });

  it('shows the new disruption types raised by the monitor', () => {
    expect(render(detectedDisruption(4, { type: 'TURNAROUND_CONFLICT', detection_rule: 'TURNAROUND_CONFLICT', severity: 'MEDIUM' })))
      .toContain('Turnaround Conflict');
    expect(render(detectedDisruption(5, { type: 'MISSED_CONNECTION', detection_rule: 'MISSED_CONNECTION', severity: 'LOW' })))
      .toContain('Monitor · passenger connection');
    expect(render(detectedDisruption(6, { type: 'AIRCRAFT_UNAVAILABLE', detection_rule: 'AIRCRAFT_UNAVAILABLE', severity: 'CRITICAL' })))
      .toContain('>Critical<');
  });

  it('shows dashes for a disruption without severity, confidence or delay', () => {
    const html = render(detectedDisruption(7, {
      severity: null,
      confidence: null,
      delay_minutes: null,
      detection_source: null,
      detection_rule: null,
      detected_at: null,
    }));
    expect(html.includes('confidence')).toBe(false);
    expect(html.includes('badge--danger')).toBe(false);
    expect(html).toContain('Not recorded');
    expect(html).toContain('time not recorded');
  });

  it('labels disruptions reported by a controller', () => {
    const html = render(detectedDisruption(8, { detection_source: 'MANUAL', detection_rule: null, confidence: 1 }));
    expect(html).toContain('Reported by a controller');
    expect(html).toContain('100% confidence');
  });
});
