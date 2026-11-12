import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { ApiError } from '../../api/client';
import type { AirportLiveTraffic, AirportWeather } from '../../api/types';
import type { PollState } from '../../hooks/poller';
import { liveAircraft, liveTraffic, weather } from '../../test/fixtures';
import { LiveTrafficCard, WeatherCard } from './AirportLivePanel';

const NOW = new Date('2026-11-10T10:00:00Z');

function state<T>(overrides: Partial<PollState<T>>): PollState<T> {
  return { data: undefined, error: undefined, loading: false, lastUpdated: NOW, ...overrides };
}

function weatherHtml(overrides: Partial<PollState<AirportWeather>>, now = NOW): string {
  return renderToStaticMarkup(<WeatherCard airport="BOM" state={state(overrides)} now={now} />);
}

function trafficHtml(overrides: Partial<PollState<AirportLiveTraffic>>, now = NOW): string {
  return renderToStaticMarkup(<LiveTrafficCard airport="BOM" state={state(overrides)} now={now} />);
}

describe('airport weather card', () => {
  it('shows a current observation with its time and source', () => {
    const html = weatherHtml({ data: weather('BOM', 'LIVE') });
    expect(html).toContain('Weather at BOM');
    expect(html).toContain('Moderate: delays likely');
    expect(html).toContain('moderate rain');
    expect(html).toContain('27 °C');
    expect(html).toContain('wind 12 kt');
    expect(html).toContain('visibility 4.0 km');
    expect(html).toContain('data-testid="weather-observed"');
    expect(html).toContain('Observed 8 min ago');
    expect(html).toContain('OpenWeatherMap');
    expect(html).toContain('>Observation<');
    expect(html.includes('data-testid="weather-stale"')).toBe(false);
  });

  it('does not estimate weather when the source is unavailable', () => {
    const html = weatherHtml({ data: weather('BOM', 'UNAVAILABLE', { note: 'No API key configured.' }) });
    expect(html).toContain('data-testid="weather-unavailable"');
    expect(html).toContain('No API key configured.');
    expect(html).toContain('Weather is not estimated when the source is unavailable.');
    expect(html.includes('data-testid="weather-observed"')).toBe(false);
    expect(html).toContain('>Unavailable<');
  });

  it('marks an old observation as stale', () => {
    const html = weatherHtml({
      data: weather('BOM', 'STALE', { observation_age_minutes: 190, note: 'The last observation is 190 minutes old.' }),
    });
    expect(html).toContain('data-testid="weather-stale"');
    expect(html).toContain('The last observation is 190 minutes old.');
    expect(html).toContain('>Stale<');
  });

  it('keeps forecasts apart from the observation and labels them as predictions', () => {
    const html = weatherHtml({
      data: weather('BOM', 'LIVE', {
        forecast: [{
          kind: 'FORECAST',
          valid_at: '2026-11-10T12:00:00Z',
          fetched_at: '2026-11-10T09:55:00Z',
          severity_score: 3,
          severity_label: 'Severe: thunderstorm',
          description: 'thunderstorm',
          precipitation_probability: 0.8,
          data_source: 'OPENWEATHERMAP',
        }],
      }),
    });
    expect(html).toContain('data-testid="weather-forecast"');
    expect(html).toContain('Forecast (prediction, not observed)');
    expect(html).toContain('80% rain');
    // The headline stays the observation, not the more severe forecast.
    const observed = html.slice(0, html.indexOf('data-testid="weather-forecast"'));
    expect(observed).toContain('Moderate: delays likely');
    expect(observed.includes('Severe: thunderstorm')).toBe(false);
  });

  it('shows no forecast block when none was delivered', () => {
    expect(weatherHtml({ data: weather('BOM', 'LIVE') }).includes('data-testid="weather-forecast"')).toBe(false);
  });

  it('keeps the last reading and says the refresh failed', () => {
    const html = weatherHtml({ data: weather('BOM', 'LIVE'), error: new Error('offline') });
    expect(html).toContain('data-testid="live-outdated"');
    expect(html).toContain('Could not refresh');
    expect(html).toContain('moderate rain');
  });

  it('shows a not-authorized error for an airport outside the user scope', () => {
    const html = weatherHtml({ error: new ApiError('forbidden', 'You are not authorized for airport DEL', '/api/airports/DEL', 403, 'forbidden'), lastUpdated: null });
    expect(html).toContain('Not authorized');
    expect(html.includes('moderate rain')).toBe(false);
  });
});

describe('live aircraft card', () => {
  it('lists observed aircraft with phase, altitude, speed and age', () => {
    const html = trafficHtml({
      data: liveTraffic('BOM', 'LIVE', [
        liveAircraft('800c1a'),
        liveAircraft('800d2b', { callsign: 'AIC5BH', flight_number: undefined, on_ground: true, phase: 'ON_GROUND', velocity_ms: 4, distance_km: 0.6, age_seconds: 45 }),
      ], { last_success_at: '2026-11-10T09:58:00Z' }),
    });
    expect(html).toContain('Live aircraft near BOM');
    expect(html).toContain('data-testid="live-aircraft-table"');
    expect(html).toContain('1 on the ground, 1 airborne within 60 km.');
    expect(html).toContain('Last delivery 2 min ago');
    expect(html).toContain('OpenSky');
    expect(html).toContain('IGO1454');
    expect(html).toContain('6E1454');
    expect(html).toContain('3,000 ft');
    expect(html).toContain('146 kt');
    expect(html).toContain('20 s ago');
    // An ATC callsign is shown as received, without a made-up flight number.
    expect(html).toContain('AIC5BH');
    expect(html).toContain('The callsign is not a flight number');
    expect(html).toContain('>Live<');
  });

  it('says that nothing is simulated when the live feed is off', () => {
    const html = trafficHtml({
      data: liveTraffic('BOM', 'DISABLED', [], { note: 'Live aircraft data is switched off in SYNTHETIC mode.' }),
    });
    expect(html).toContain('data-testid="live-disabled"');
    expect(html).toContain('Live aircraft data is switched off in SYNTHETIC mode.');
    expect(html).toContain('No positions are simulated in its place.');
    expect(html.includes('data-testid="live-aircraft-table"')).toBe(false);
    expect(html).toContain('>Off<');
  });

  it('warns when the feed is stale and marks old positions', () => {
    const html = trafficHtml({
      data: liveTraffic('BOM', 'STALE', [liveAircraft('800c1a', { stale: true, age_seconds: 1500 })], {
        last_success_at: '2026-11-10T09:30:00Z',
        note: 'No delivery for 30 minutes; positions are out of date.',
      }),
    });
    expect(html).toContain('data-testid="live-note"');
    expect(html).toContain('notice--warning');
    expect(html).toContain('positions are out of date');
    expect(html).toContain('is-stale');
    expect(html).toContain('25 min ago');
    expect(html).toContain('>Stale<');
  });

  it('reports an empty sky without inventing aircraft', () => {
    const html = trafficHtml({ data: liveTraffic('BOM', 'LIVE', [], { last_success_at: '2026-11-10T09:59:00Z' }) });
    expect(html).toContain('No aircraft reported within 60 km.');
    expect(html.includes('data-testid="live-aircraft-table"')).toBe(false);
  });

  it('keeps the last positions and says the refresh failed', () => {
    const html = trafficHtml({ data: liveTraffic('BOM', 'LIVE', [liveAircraft('800c1a')]), error: new Error('offline') });
    expect(html).toContain('data-testid="live-outdated"');
    expect(html).toContain('IGO1454');
  });

  it('shows a not-authorized error instead of data of another airport', () => {
    const html = trafficHtml({ error: new ApiError('forbidden', 'You are not authorized for airport DEL', '/api/airports/DEL', 403, 'forbidden'), lastUpdated: null });
    expect(html).toContain('Not authorized');
    expect(html.includes('data-testid="live-aircraft-table"')).toBe(false);
  });
});
