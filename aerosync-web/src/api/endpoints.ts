import { RequestCache } from './cache';
import { getJson, postJson } from './client';
import type {
  AirportLiveTraffic,
  AirportOperations,
  AirportSummary,
  AirportWeather,
  DependencyType,
  Disruption,
  DisruptionType,
  GraphResponse,
  LoginResponse,
  OperationsFlight,
  ResolutionProposal,
  ResolutionResult,
  SystemStatus,
  UserProfile,
} from './types';

/**
 * One shared cache for all views: switching pages or panels never refetches
 * fresh data. Keys include the airport, and the whole cache is cleared on
 * sign-out, so data of one user or airport is never shown for another.
 */
export const apiCache = new RequestCache(60_000);

export interface AffectedOptions {
  maxDepth?: number;
  horizonHours?: number;
  types?: DependencyType[];
  minImpactWeight?: number;
  maxFlights?: number;
}

export const keys = {
  network: (airport: string, date: string | null) => `network:${airport}:${date ?? 'default'}`,
  airports: () => 'airports',
  airportGraph: (iata: string, date: string | null) => `airport-graph:${iata}:${date ?? 'default'}`,
  airportOperations: (iata: string, date: string | null) => `airport-ops:${iata}:${date ?? 'default'}`,
  flightGraph: (flightId: string, depth: number) => `flight-graph:${flightId}:${depth}`,
  affected: (flightId: string, o: AffectedOptions) =>
    `affected:${flightId}:${o.maxDepth ?? ''}:${o.horizonHours ?? ''}:${(o.types ?? []).join(',')}:${o.minImpactWeight ?? ''}:${o.maxFlights ?? ''}`,
  disruptions: (airport: string) => `disruptions:${airport}`,
  operationsFlights: (airport: string) => `ops-flights:${airport}`,
};

/** Typed access to the backend. Every call except login sends the session token. */
export const api = {
  /** POST /api/auth/login (public). */
  login(username: string, password: string): Promise<LoginResponse> {
    return postJson<LoginResponse>('/api/auth/login', { username, password }, {}, { auth: false });
  },

  /** GET /api/auth/me — the signed-in user and their authorized airports. */
  me(): Promise<UserProfile> {
    return getJson<UserProfile>('/api/auth/me');
  },

  /**
   * GET /api/graph/network?date=&airports= — flights departing from or
   * arriving at the airport (the backend refuses airports outside the user's
   * assignment). Without a date the backend uses "today".
   */
  network(date: string | null, airport: string): Promise<GraphResponse> {
    return apiCache.get(keys.network(airport, date), async () => {
      const response = await getJson<GraphResponse>('/api/graph/network', {
        date,
        include_dependencies: true,
        airports: airport,
      });
      if (!date && response.service_date) apiCache.put(keys.network(airport, response.service_date), response);
      return response;
    });
  },

  /**
   * Fetches the network again, bypassing the cache, and stores the answer
   * (Stage 4 background refresh). On failure the cached value is left as it
   * is, so the graph keeps showing the last good data.
   */
  async refreshNetwork(date: string, airport: string): Promise<GraphResponse> {
    const response = await getJson<GraphResponse>('/api/graph/network', {
      date,
      include_dependencies: true,
      airports: airport,
    });
    apiCache.put(keys.network(airport, date), response);
    return response;
  },

  /** GET /api/airports — the airports of the signed-in user. */
  airports(): Promise<AirportSummary[]> {
    return apiCache.get(keys.airports(), () => getJson<AirportSummary[]>('/api/airports'));
  },

  /** GET /api/graph/airports/{iata}?date= */
  airportGraph(iata: string, date: string | null): Promise<GraphResponse> {
    return apiCache.get(keys.airportGraph(iata, date), () =>
      getJson<GraphResponse>(`/api/graph/airports/${encodeURIComponent(iata)}`, { date, include_dependencies: true }),
    );
  },

  /** GET /api/airports/{iata}/operations?date= */
  airportOperations(iata: string, date: string | null): Promise<AirportOperations> {
    return apiCache.get(keys.airportOperations(iata, date), () =>
      getJson<AirportOperations>(`/api/airports/${encodeURIComponent(iata)}/operations`, { date }),
    );
  },

  /** GET /api/graph/flights/{flightId}?depth= (flightId = instance UUID or flight:<uuid>) */
  flightGraph(flightId: string, depth = 1): Promise<GraphResponse> {
    return apiCache.get(keys.flightGraph(flightId, depth), () =>
      getJson<GraphResponse>(`/api/graph/flights/${encodeURIComponent(flightId)}`, { depth }),
    );
  },

  /** GET /api/graph/flights/{flightId}/affected */
  affected(flightId: string, options: AffectedOptions = {}): Promise<GraphResponse> {
    return apiCache.get(keys.affected(flightId, options), () =>
      getJson<GraphResponse>(`/api/graph/flights/${encodeURIComponent(flightId)}/affected`, {
        max_depth: options.maxDepth,
        horizon_hours: options.horizonHours,
        types: options.types?.length ? options.types.join(',') : undefined,
        min_impact_weight: options.minImpactWeight,
        max_flights: options.maxFlights,
      }),
    );
  },

  // ── Disruptions and resolutions (legacy operations endpoints) ───────────

  /** GET /api/disruptions?airport= — disruptions of flights departing from or arriving at the airport. */
  disruptions(airport: string): Promise<Disruption[]> {
    return apiCache.get(keys.disruptions(airport), () => getJson<Disruption[]>('/api/disruptions', { airport }));
  },

  /** GET /api/flights?airport= — operations flights of the airport (for reporting a disruption). */
  operationsFlights(airport: string): Promise<OperationsFlight[]> {
    return apiCache.get(keys.operationsFlights(airport), () => getJson<OperationsFlight[]>('/api/flights', { airport }));
  },

  // ── Stage 4: live data and pipeline status ──────────────────────────────
  // Never cached: these are polled, and the answer is only useful when fresh.

  /** GET /api/system/status — data mode, feed freshness, dependency health, monitor. */
  systemStatus(): Promise<SystemStatus> {
    return getJson<SystemStatus>('/api/system/status');
  },

  /** GET /api/airports/{iata}/live-aircraft — aircraft observed at or near one of the user's airports. */
  liveAircraft(iata: string): Promise<AirportLiveTraffic> {
    return getJson<AirportLiveTraffic>(`/api/airports/${encodeURIComponent(iata)}/live-aircraft`);
  },

  /** GET /api/airports/{iata}/weather — latest observation and upcoming forecast steps. */
  airportWeather(iata: string): Promise<AirportWeather> {
    return getJson<AirportWeather>(`/api/airports/${encodeURIComponent(iata)}/weather`);
  },

  /** POST /api/disruptions/trigger — needs an operator role at the flight's departure airport. */
  reportDisruption(flightId: string, type: DisruptionType, delayMinutes: number): Promise<Disruption> {
    return postJson<Disruption>('/api/disruptions/trigger', undefined, { flightId, type, delayMinutes });
  },

  /** POST /api/agents/propose/{id} — recommendation only, nothing is changed until approved. */
  proposeResolution(disruptionId: number): Promise<ResolutionProposal> {
    return postJson<ResolutionProposal>(`/api/agents/propose/${disruptionId}`);
  },

  /** POST /api/agents/approve/{id} — applies the approved proposal. */
  approveResolution(disruptionId: number, proposal: ResolutionProposal): Promise<ResolutionResult> {
    return postJson<ResolutionResult>(`/api/agents/approve/${disruptionId}`, proposal);
  },

  /** POST /api/agents/reject/{id} — the agent proposes again without the rejected resource. */
  rejectResolution(disruptionId: number, reason: string): Promise<ResolutionProposal> {
    return postJson<ResolutionProposal>(`/api/agents/reject/${disruptionId}`, { reason });
  },
};
