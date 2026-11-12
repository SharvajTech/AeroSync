import { LuCloudSun, LuRadar, LuTriangleAlert } from 'react-icons/lu';
import { api } from '../../api/endpoints';
import type { AirportLiveTraffic, AirportWeather, WeatherReport } from '../../api/types';
import type { PollState } from '../../hooks/poller';
import { usePolling } from '../../hooks/usePolling';
import {
  ageLabel,
  ago,
  altitudeFeet,
  clockTime,
  isSnapshotStale,
  phaseLabel,
  sourceLabel,
  speedKnots,
  weatherTone,
} from '../../utils/liveData';
import { Card } from '../common/Card';
import { StatusBadge } from '../common/StatusBadge';
import { ErrorState, LoadingState } from '../common/States';

/** How often the live cards refresh. */
export const LIVE_POLL_MS = 60_000;
const MAX_AIRCRAFT_ROWS = 12;

/**
 * Weather and live aircraft of one airport. The key includes the airport, so
 * switching airports drops the previous airport's data before the new one
 * arrives; the backend refuses airports the user is not assigned to.
 */
export function AirportLivePanel({ airport }: { airport: string }) {
  const weather = usePolling<AirportWeather>(`weather:${airport}`, () => api.airportWeather(airport), LIVE_POLL_MS);
  const traffic = usePolling<AirportLiveTraffic>(`live:${airport}`, () => api.liveAircraft(airport), LIVE_POLL_MS);
  const now = new Date();
  return (
    <div className="live-grid" data-testid="airport-live">
      <WeatherCard airport={airport} state={weather} now={now} />
      <LiveTrafficCard airport={airport} state={traffic} now={now} />
    </div>
  );
}

/** "Could not refresh" line shown when the last poll failed but older data is still on screen. */
function Outdated({ state, now }: { state: PollState<unknown>; now: Date }) {
  if (!state.data) return null;
  if (!state.error && !isSnapshotStale(state.lastUpdated, LIVE_POLL_MS, now)) return null;
  return (
    <p className="notice notice--warning" data-testid="live-outdated">
      <LuTriangleAlert aria-hidden /> Could not refresh. Showing what was received at {clockTime(state.lastUpdated)}.
    </p>
  );
}

function weatherLine(report: WeatherReport): string {
  const parts: string[] = [];
  if (report.temperature_c !== undefined) parts.push(`${Math.round(report.temperature_c)} °C`);
  if (report.wind_speed_ms !== undefined) parts.push(`wind ${Math.round(report.wind_speed_ms * 1.94384)} kt`);
  if (report.visibility_m !== undefined) parts.push(`visibility ${(report.visibility_m / 1000).toFixed(1)} km`);
  if (report.precipitation_mm !== undefined && report.precipitation_mm > 0) parts.push(`${report.precipitation_mm.toFixed(1)} mm`);
  return parts.join(' · ');
}

export function WeatherCard({ airport, state, now }: { airport: string; state: PollState<AirportWeather>; now: Date }) {
  const data = state.data;
  let body;
  if (!data) {
    body = state.loading ? <LoadingState label="Loading weather…" /> : <ErrorState error={state.error} />;
  } else if (data.status === 'UNAVAILABLE' || !data.observation) {
    body = (
      <p className="details__muted" data-testid="weather-unavailable">
        No current observation. {data.note ?? ''} Weather is not estimated when the source is unavailable.
      </p>
    );
  } else {
    const observation = data.observation;
    body = (
      <>
        <div className="live-weather">
          <StatusBadge tone={data.status === 'STALE' ? 'neutral' : weatherTone(observation.severity_score)}>
            {observation.severity_label}
          </StatusBadge>
          <span className="live-weather__text">{observation.description ?? 'No description'}</span>
        </div>
        <p className="details__muted">{weatherLine(observation)}</p>
        <p className="details__muted" data-testid="weather-observed">
          Observed {ago(observation.valid_at, now)} ({clockTime(observation.valid_at)}) · {sourceLabel(observation.data_source)}
        </p>
        {data.status === 'STALE' && (
          <p className="notice notice--warning" data-testid="weather-stale">
            <LuTriangleAlert aria-hidden /> {data.note ?? 'This observation is old and is not used for predictions or detection.'}
          </p>
        )}
      </>
    );
  }
  const forecast = data?.forecast ?? [];
  return (
    <Card
      title={<><LuCloudSun aria-hidden /> Weather at {airport}</>}
      actions={data ? (
        <StatusBadge tone={data.status === 'LIVE' ? 'success' : data.status === 'STALE' ? 'warning' : 'neutral'}>
          {data.status === 'LIVE' ? 'Observation' : data.status === 'STALE' ? 'Stale' : 'Unavailable'}
        </StatusBadge>
      ) : undefined}
    >
      <Outdated state={state} now={now} />
      {body}
      {forecast.length > 0 && (
        <div className="live-forecast" data-testid="weather-forecast">
          <h3 className="details__label">Forecast (prediction, not observed)</h3>
          <ul className="live-forecast__list">
            {forecast.map((step) => (
              <li key={step.valid_at}>
                <span className="mono">{clockTime(step.valid_at).slice(0, 5)}</span>
                <StatusBadge tone={weatherTone(step.severity_score)}>{step.severity_label}</StatusBadge>
                <span className="details__muted">
                  {step.description ?? ''}
                  {step.precipitation_probability !== undefined ? ` · ${Math.round(step.precipitation_probability * 100)}% rain` : ''}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </Card>
  );
}

const TRAFFIC_BADGE: Record<AirportLiveTraffic['status'], { tone: 'success' | 'warning' | 'neutral' | 'info'; label: string }> = {
  LIVE: { tone: 'success', label: 'Live' },
  STALE: { tone: 'warning', label: 'Stale' },
  DISABLED: { tone: 'neutral', label: 'Off' },
  NO_DATA: { tone: 'info', label: 'Waiting' },
};

export function LiveTrafficCard({ airport, state, now }: { airport: string; state: PollState<AirportLiveTraffic>; now: Date }) {
  const data = state.data;
  let body;
  if (!data) {
    body = state.loading ? <LoadingState label="Loading live aircraft…" /> : <ErrorState error={state.error} />;
  } else if (data.status === 'DISABLED') {
    body = (
      <p className="details__muted" data-testid="live-disabled">
        {data.note ?? 'Live aircraft data is not fetched.'} No positions are simulated in its place.
      </p>
    );
  } else {
    const rows = data.aircraft.slice(0, MAX_AIRCRAFT_ROWS);
    body = (
      <>
        <p className="details__muted" data-testid="live-summary">
          {data.aircraft.length === 0
            ? `No aircraft reported within ${data.radius_km} km.`
            : `${data.on_ground} on the ground, ${data.airborne} airborne within ${data.radius_km} km.`}
          {' '}Last delivery {ago(data.last_success_at, now)} · {sourceLabel(data.data_source)}
        </p>
        {data.note && (
          <p className={`notice notice--${data.status === 'STALE' ? 'warning' : 'info'}`} data-testid="live-note">{data.note}</p>
        )}
        {rows.length > 0 && (
          <div className="table-wrap">
            <table className="table table--compact" data-testid="live-aircraft-table">
              <thead>
                <tr>
                  <th>Callsign</th>
                  <th title="Derived from the callsign when it is a flight number">Flight</th>
                  <th>Phase</th>
                  <th>Altitude</th>
                  <th>Speed</th>
                  <th>Distance</th>
                  <th title="Time since the aircraft was last heard from">Seen</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((aircraft) => (
                  <tr key={aircraft.icao24} className={aircraft.stale ? 'is-stale' : undefined}>
                    <td className="mono" title={`Transponder ${aircraft.icao24}`}>{aircraft.callsign ?? aircraft.icao24}</td>
                    <td>{aircraft.flight_number ?? <span className="text-muted" title="The callsign is not a flight number">—</span>}</td>
                    <td>{phaseLabel(aircraft.phase)}</td>
                    <td>{aircraft.on_ground ? '—' : altitudeFeet(aircraft.baro_altitude_m ?? aircraft.geo_altitude_m)}</td>
                    <td>{speedKnots(aircraft.velocity_ms)}</td>
                    <td>{aircraft.distance_km !== undefined ? `${aircraft.distance_km.toFixed(0)} km` : '—'}</td>
                    <td className={aircraft.stale ? 'text-warning' : 'text-muted'}>{ageLabel(aircraft.age_seconds)} ago</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data.aircraft.length > rows.length && (
          <p className="details__muted">{data.aircraft.length - rows.length} more not shown.</p>
        )}
      </>
    );
  }
  const badge = data ? TRAFFIC_BADGE[data.status] : null;
  return (
    <Card
      title={<><LuRadar aria-hidden /> Live aircraft near {airport}</>}
      actions={badge ? <StatusBadge tone={badge.tone}>{badge.label}</StatusBadge> : undefined}
    >
      <Outdated state={state} now={now} />
      {body}
    </Card>
  );
}
