import { RequestCache } from './cache';
import { getJson } from './client';
import type { AirportOperations, AirportSummary, DependencyType, GraphResponse } from './types';

/** One shared cache for all views: switching pages or panels never refetches fresh data. */
export const apiCache = new RequestCache(60_000);

export interface AffectedOptions {
  maxDepth?: number;
  horizonHours?: number;
  types?: DependencyType[];
  minImpactWeight?: number;
  maxFlights?: number;
}

export const keys = {
  network: (date: string | null) => `network:${date ?? 'default'}`,
  airports: () => 'airports',
  airportGraph: (iata: string, date: string | null) => `airport-graph:${iata}:${date ?? 'default'}`,
  airportOperations: (iata: string, date: string | null) => `airport-ops:${iata}:${date ?? 'default'}`,
  flightGraph: (flightId: string, depth: number) => `flight-graph:${flightId}:${depth}`,
  affected: (flightId: string, o: AffectedOptions) =>
    `affected:${flightId}:${o.maxDepth ?? ''}:${o.horizonHours ?? ''}:${(o.types ?? []).join(',')}:${o.minImpactWeight ?? ''}:${o.maxFlights ?? ''}`,
};

/** Typed access to the Stage 1 endpoints (GraphController and AirportOperationsController). */
export const api = {
  /** GET /api/graph/network?date= — without a date the backend uses "today" in its operations time zone. */
  network(date: string | null): Promise<GraphResponse> {
    return apiCache.get(keys.network(date), async () => {
      const response = await getJson<GraphResponse>('/api/graph/network', { date, include_dependencies: true });
      if (!date && response.service_date) apiCache.put(keys.network(response.service_date), response);
      return response;
    });
  },

  /** GET /api/airports */
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
};
