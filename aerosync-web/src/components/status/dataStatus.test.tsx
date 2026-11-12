import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { SystemStatus } from '../../api/types';
import type { PollState } from '../../hooks/poller';
import { feed, systemStatus } from '../../test/fixtures';
import { DataStatusView } from './DataStatusBar';

const NOW = new Date('2026-11-10T10:00:00Z');

function render(state: Partial<PollState<SystemStatus>>, now = NOW): string {
  return renderToStaticMarkup(
    <DataStatusView state={{ data: undefined, error: undefined, loading: false, lastUpdated: null, ...state }} now={now} />,
  );
}

/** Text content of the element with the given test id (first match). */
function textOf(html: string, testId: string): string | null {
  const start = html.indexOf(`data-testid="${testId}"`);
  if (start < 0) return null;
  const open = html.indexOf('>', start);
  // Elements used here hold text and inline children only; strip the tags up to the closing of the parent chip.
  const rest = html.slice(open + 1);
  const end = rest.search(/<\/(span|div|p)>/);
  return rest.slice(0, end < 0 ? undefined : end).replace(/<[^>]+>/g, '').trim();
}

const SYNTHETIC = systemStatus('SYNTHETIC', [
  feed('LIVE_STATES', 'DISABLED'),
  feed('FLIGHT_LEGS', 'DISABLED'),
  feed('WEATHER', 'UNCONFIGURED', { last_error: 'No API key configured (WEATHER_API_KEY)' }),
]);

const HYBRID = systemStatus('HYBRID', [
  feed('LIVE_STATES', 'LIVE', { last_success_at: '2026-11-10T09:57:00Z', last_run: { status: 'SUCCESS', started_at: '2026-11-10T09:57:00Z', records_fetched: 41, records_inserted: 3, records_updated: 30, records_unchanged: 8, records_skipped: 0, api_calls: 1 } }),
  feed('FLIGHT_LEGS', 'WAITING'),
  feed('WEATHER', 'STALE', { last_success_at: '2026-11-10T07:00:00Z' }),
]);

describe('data status bar', () => {
  it('shows a placeholder while the first status is loading', () => {
    const html = render({ loading: true });
    expect(html).toContain('Checking data sources');
    expect(html.includes('data-testid="data-mode"')).toBe(false);
  });

  it('says so when the status cannot be loaded instead of guessing a mode', () => {
    const html = render({ error: new Error('offline') });
    expect(html).toContain('data-testid="status-unavailable"');
    expect(html).toContain('Data source status is unavailable');
    expect(html.includes('data-testid="data-mode"')).toBe(false);
    expect(html.includes('Live data')).toBe(false);
  });

  it('labels synthetic mode and never shows a feed as live in it', () => {
    const html = render({ data: SYNTHETIC, lastUpdated: NOW });
    expect(textOf(html, 'data-mode')).toBe('Synthetic data');
    expect(html).toContain('badge--neutral');
    expect(html).toContain('data-testid="feed-LIVE_STATES"');
    expect(html).toContain('Not configured');
    expect(html.includes('>Live<')).toBe(false);
    expect(html.includes('Live data')).toBe(false);
  });

  it('labels hybrid mode and shows each feed with its last successful ingestion', () => {
    const html = render({ data: HYBRID, lastUpdated: NOW });
    expect(textOf(html, 'data-mode')).toBe('Hybrid data');
    expect(html).toContain('Live aircraft');
    expect(html).toContain('3 min ago');
    expect(html).toContain('no data yet');
    expect(html).toContain('Stale');
    expect(html).toContain('3 h ago');
    expect(html).toContain('source: OPENSKY');
    expect(html).toContain('source: OPENWEATHERMAP');
  });

  it('labels live mode', () => {
    const html = render({ data: systemStatus('LIVE', [feed('LIVE_STATES', 'LIVE', { last_success_at: '2026-11-10T09:59:50Z' })]), lastUpdated: NOW });
    expect(textOf(html, 'data-mode')).toBe('Live data');
    expect(html).toContain('badge--success');
  });

  it('shows a failing or rate-limited feed with the reason', () => {
    const html = render({
      data: systemStatus('HYBRID', [
        feed('LIVE_STATES', 'RATE_LIMITED', { last_success_at: '2026-11-10T09:30:00Z', last_error: 'OpenSky rate limit reached' }),
        feed('FLIGHT_LEGS', 'FAILING', { last_error: 'OpenSky did not answer in time', consecutive_failures: 3 }),
      ]),
      lastUpdated: NOW,
    });
    expect(html).toContain('Rate limited');
    expect(html).toContain('OpenSky rate limit reached');
    expect(html).toContain('Failing');
    expect(html).toContain('OpenSky did not answer in time');
  });

  it('shows the monitor state and when it last checked', () => {
    const running = render({ data: HYBRID, lastUpdated: NOW });
    expect(running).toContain('data-testid="monitoring-status"');
    expect(running).toContain('checked 1 min ago');
    expect(running).toContain('nothing is executed without approval');

    const off = render({
      data: systemStatus('SYNTHETIC', [], { monitoring: { status: 'DISABLED', enabled: false } }),
      lastUpdated: NOW,
    });
    expect(off).toContain('The disruption monitor is switched off.');
    expect(off.includes('checked ')).toBe(false);
  });

  it('flags an unreachable database or prediction service', () => {
    const html = render({
      data: systemStatus('SYNTHETIC', [], { database: { status: 'DOWN' }, ml_service: { status: 'DOWN', detail: 'connection refused' } }),
      lastUpdated: NOW,
    });
    expect(html).toContain('Database down');
    expect(html).toContain('Prediction service down');
    expect(render({ data: SYNTHETIC, lastUpdated: NOW }).includes('Database down')).toBe(false);
  });

  it('shows the refresh time while current, and a warning when the refresh failed', () => {
    const current = render({ data: HYBRID, lastUpdated: NOW });
    expect(current).toContain('data-testid="status-refreshed"');
    expect(current.includes('data-testid="status-stale"')).toBe(false);

    const failed = render({ data: HYBRID, lastUpdated: NOW, error: new Error('offline') });
    expect(failed).toContain('data-testid="status-stale"');
    expect(failed).toContain('Not refreshed since');
    expect(failed.includes('data-testid="status-refreshed"')).toBe(false);
    // The last known values stay on screen.
    expect(textOf(failed, 'data-mode')).toBe('Hybrid data');
  });

  it('marks the status as out of date when no refresh arrived for a long time', () => {
    const later = new Date(NOW.getTime() + 5 * 60_000);
    const html = render({ data: HYBRID, lastUpdated: NOW }, later);
    expect(html).toContain('data-testid="status-stale"');
  });
});
