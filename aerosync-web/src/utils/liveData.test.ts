import { describe, expect, it } from 'vitest';
import { detectedDisruption, feed } from '../test/fixtures';
import {
  MODE_LABELS,
  ageLabel,
  ago,
  altitudeFeet,
  clockTime,
  confidencePercent,
  detectionSourceLabel,
  feedStatusLabel,
  feedSummary,
  feedTone,
  hasObservedTimes,
  isAdvisory,
  isNewDetection,
  isSnapshotStale,
  modeTone,
  originLabel,
  originTone,
  severityTone,
  sourceLabel,
  speedKnots,
  weatherTone,
} from './liveData';

const NOW = new Date('2026-11-10T10:00:00Z');

describe('data mode and sources', () => {
  it('names each data mode and never styles synthetic data like live data', () => {
    expect(MODE_LABELS.SYNTHETIC).toBe('Synthetic data');
    expect(MODE_LABELS.LIVE).toBe('Live data');
    expect(MODE_LABELS.HYBRID).toBe('Hybrid data');
    expect(modeTone('SYNTHETIC')).toBe('neutral');
    expect(modeTone('LIVE')).toBe('success');
    expect(modeTone('HYBRID')).toBe('info');
  });

  it('labels the source of a record and falls back to its origin', () => {
    expect(sourceLabel('OPENSKY')).toBe('OpenSky');
    expect(sourceLabel('SYNTHETIC_GENERATOR')).toBe('Synthetic');
    expect(sourceLabel('SOME_NEW_FEED')).toBe('SOME_NEW_FEED');
    expect(sourceLabel(undefined, 'IMPORTED')).toBe('External import');
    expect(sourceLabel(undefined, 'MANUAL')).toBe('Manual entry');
    // No information at all is treated as synthetic, never as live.
    expect(sourceLabel(undefined, null)).toBe('Synthetic');
    expect(originLabel('IMPORTED')).toBe('External');
    expect(originLabel(null)).toBe('Synthetic');
    expect(originTone('SYNTHETIC')).toBe('neutral');
    expect(originTone('IMPORTED')).toBe('success');
  });

  it('knows when a flight has observed times instead of a timetable', () => {
    expect(hasObservedTimes({ data_source: 'OPENSKY', schedule_basis: 'OBSERVED' })).toBe(true);
    expect(hasObservedTimes({ data_source: 'SYNTHETIC_GENERATOR', schedule_basis: 'PLANNED' })).toBe(false);
    expect(hasObservedTimes(null)).toBe(false);
  });
});

describe('feed freshness', () => {
  it('maps feed states to labels and tones', () => {
    expect(feedStatusLabel('LIVE')).toBe('Live');
    expect(feedStatusLabel('DISABLED')).toBe('Off');
    expect(feedStatusLabel('UNCONFIGURED')).toBe('Not configured');
    expect(feedTone('LIVE')).toBe('success');
    expect(feedTone('STALE')).toBe('warning');
    expect(feedTone('RATE_LIMITED')).toBe('warning');
    expect(feedTone('FAILING')).toBe('danger');
    expect(feedTone('DISABLED')).toBe('neutral');
  });

  it('formats how long ago something happened', () => {
    expect(ago('2026-11-10T09:59:40Z', NOW)).toBe('just now');
    expect(ago('2026-11-10T09:56:00Z', NOW)).toBe('4 min ago');
    expect(ago('2026-11-10T07:00:00Z', NOW)).toBe('3 h ago');
    expect(ago('2026-11-07T10:00:00Z', NOW)).toBe('3 d ago');
    expect(ago(null, NOW)).toBe('never');
    expect(ago('not a date', NOW)).toBe('unknown');
    expect(ago('2026-11-10T10:05:00Z', NOW)).toBe('just now');
    expect(clockTime(null)).toBe('—');
    expect(clockTime(new Date(2026, 10, 10, 9, 5, 7))).toBe('09:05:07');
  });

  it('summarises a feed with its last success and the reason it is not live', () => {
    expect(feedSummary(feed('LIVE_STATES', 'LIVE', { last_success_at: '2026-11-10T09:58:00Z' }), NOW))
      .toBe('Live aircraft: Live · last success 2 min ago');
    expect(feedSummary(feed('LIVE_STATES', 'RATE_LIMITED', {
      last_success_at: '2026-11-10T09:15:00Z',
      last_error: 'OpenSky rate limit reached; retry after 3600 s',
    }), NOW)).toBe('Live aircraft: Rate limited · last success 45 min ago · OpenSky rate limit reached; retry after 3600 s');
    expect(feedSummary(feed('FLIGHT_LEGS', 'WAITING'), NOW)).toBe('Flight records: Waiting · no successful run yet');
    expect(feedSummary(feed('WEATHER', 'UNCONFIGURED', { last_error: 'No API key configured (WEATHER_API_KEY)' }), NOW))
      .toBe('Weather: Not configured · No API key configured (WEATHER_API_KEY)');
  });

  it('treats a snapshot as stale after two and a half missed intervals', () => {
    const loaded = new Date('2026-11-10T10:00:00Z');
    expect(isSnapshotStale(loaded, 30_000, new Date('2026-11-10T10:01:00Z'))).toBe(false);
    expect(isSnapshotStale(loaded, 30_000, new Date('2026-11-10T10:01:16Z'))).toBe(true);
    expect(isSnapshotStale(null, 30_000, NOW)).toBe(false);
  });
});

describe('disruption detection', () => {
  it('shows who raised a disruption', () => {
    expect(detectionSourceLabel(detectedDisruption(1))).toBe('Monitor · observed weather');
    expect(detectionSourceLabel(detectedDisruption(1, { detection_rule: 'TURNAROUND_CONFLICT' }))).toBe('Monitor · aircraft turnaround');
    expect(detectionSourceLabel(detectedDisruption(1, { detection_rule: 'NEW_RULE' }))).toBe('Monitor · NEW_RULE');
    expect(detectionSourceLabel(detectedDisruption(1, { detection_source: 'MANUAL', detection_rule: null }))).toBe('Reported by a controller');
    expect(detectionSourceLabel(detectedDisruption(1, { detection_source: 'SIMULATION' }))).toBe('Simulation');
    expect(detectionSourceLabel(detectedDisruption(1, { detection_source: null }))).toBe('Not recorded');
  });

  it('formats severity and confidence without inventing values', () => {
    expect(severityTone('CRITICAL')).toBe('danger');
    expect(severityTone('HIGH')).toBe('danger');
    expect(severityTone('MEDIUM')).toBe('warning');
    expect(severityTone('LOW')).toBe('info');
    expect(severityTone(null)).toBe('neutral');
    expect(confidencePercent(0.85)).toBe('85%');
    expect(confidencePercent(1)).toBe('100%');
    expect(confidencePercent(null)).toBe('—');
    expect(confidencePercent(undefined)).toBe('—');
  });

  it('marks only recently detected, still open disruptions as new', () => {
    const now = new Date(2026, 10, 10, 10, 0, 0);
    expect(isNewDetection(detectedDisruption(1, { detected_at: '2026-11-10T09:50:00' }), now)).toBe(true);
    expect(isNewDetection(detectedDisruption(1, { detected_at: '2026-11-10T09:40:00' }), now)).toBe(false);
    expect(isNewDetection(detectedDisruption(1, { detected_at: '2026-11-10T09:58:00', status: 'PROPOSED' }), now)).toBe(false);
    expect(isNewDetection(detectedDisruption(1, { detected_at: null }), now)).toBe(false);
  });

  it('recognises advisory disruptions of network flights', () => {
    expect(isAdvisory(detectedDisruption(1))).toBe(false);
    expect(isAdvisory(detectedDisruption(1, { actionable: false, flight_instance_id: 'fi-1' }))).toBe(true);
    expect(isAdvisory(detectedDisruption(1, { actionable: undefined, flight_instance_id: 'fi-1' }))).toBe(true);
  });
});

describe('live aircraft and weather formatting', () => {
  it('converts units and keeps missing values missing', () => {
    expect(altitudeFeet(900)).toBe('3,000 ft');
    expect(altitudeFeet(undefined)).toBe('—');
    expect(speedKnots(75)).toBe('146 kt');
    expect(speedKnots(null)).toBe('—');
    expect(ageLabel(20)).toBe('20 s');
    expect(ageLabel(240)).toBe('4 min');
    expect(weatherTone(3)).toBe('danger');
    expect(weatherTone(2)).toBe('warning');
    expect(weatherTone(0)).toBe('success');
  });
});
