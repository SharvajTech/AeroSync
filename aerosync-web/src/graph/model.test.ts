import { describe, expect, it } from 'vitest';
import { DEPENDENCY_TYPES, type DependencyType, type GraphResponse } from '../api/types';
import { AI101, AI202, AI505, E6100, affectedResponse, flight, networkResponse } from '../test/fixtures';
import {
  airportMovements,
  buildFlightGraph,
  buildRouteSummary,
  dataSources,
  dependencyCounts,
  disruptedFlights,
  hasOpenDisruption,
  neighbourhood,
  traversalRoles,
} from './model';

const ALL_TYPES = new Set<DependencyType>(DEPENDENCY_TYPES);
const MORNING = { startMinute: 7 * 60, endMinute: 9 * 60 };

function ids(nodes: { id: string }[]) {
  return nodes.map((n) => n.id).sort();
}

describe('buildFlightGraph', () => {
  it('draws airports and flights with route edges airport -> flight -> airport', () => {
    const graph = buildFlightGraph(networkResponse(), { dependencyTypes: ALL_TYPES });
    expect(graph.mode).toBe('flights');
    expect(graph.nodes.filter((n) => n.kind === 'flight')).toHaveLength(4);
    const departs = graph.edges.find((e) => e.relationship === 'DEPARTS_FROM' && e.target === AI101.id)!;
    const arrives = graph.edges.find((e) => e.relationship === 'ARRIVES_AT' && e.source === AI101.id)!;
    expect(departs.source).toBe('airport:BOM');
    expect(arrives.target).toBe('airport:BLR');
    expect(graph.edges.filter((e) => e.category === 'DEPENDENCY')).toHaveLength(3);
    expect(graph.hiddenFlightCount).toBe(0);
  });

  it('only draws airports used by visible flights unless asked for all', () => {
    const graph = buildFlightGraph(networkResponse(), { dependencyTypes: ALL_TYPES });
    expect(graph.nodes.some((n) => n.id === 'airport:PNQ')).toBe(false);
    const all = buildFlightGraph(networkResponse(), { dependencyTypes: ALL_TYPES, includeAllAirports: true });
    expect(all.nodes.some((n) => n.id === 'airport:PNQ')).toBe(true);
  });

  it('filters flights by local departure window and drops edges to hidden flights', () => {
    const graph = buildFlightGraph(networkResponse(), { dependencyTypes: ALL_TYPES, window: MORNING });
    expect(ids(graph.nodes.filter((n) => n.kind === 'flight'))).toEqual([AI101.id]);
    expect(graph.edges.every((e) => e.category === 'ROUTE')).toBe(true);
    expect(graph.hiddenFlightCount).toBe(3);
  });

  it('adds flights sharing a dependency with the window as context nodes', () => {
    const graph = buildFlightGraph(networkResponse(), { dependencyTypes: ALL_TYPES, window: MORNING, includeConnectedFlights: true });
    const flights = graph.nodes.filter((n) => n.kind === 'flight');
    expect(ids(flights)).toEqual([AI101.id, AI202.id, AI505.id].sort());
    expect(flights.find((n) => n.id === AI505.id)!.context).toBe(true);
    expect(flights.find((n) => n.id === AI101.id)!.context).toBeUndefined();
    expect(graph.edges.filter((e) => e.category === 'DEPENDENCY')).toHaveLength(2);
  });

  it('uses the arrival time for flights arriving at the focus airport', () => {
    const evening = { startMinute: 21 * 60, endMinute: 24 * 60 };
    const atBom = buildFlightGraph(networkResponse(), { dependencyTypes: ALL_TYPES, window: evening, focusAirport: 'BOM' });
    expect(ids(atBom.nodes.filter((n) => n.kind === 'flight'))).toEqual([E6100.id]);
    const national = buildFlightGraph(networkResponse(), { dependencyTypes: ALL_TYPES, window: evening });
    expect(national.nodes.filter((n) => n.kind === 'flight')).toHaveLength(0);
  });

  it('hides dependency types that are switched off', () => {
    const graph = buildFlightGraph(networkResponse(), { dependencyTypes: new Set<DependencyType>(['AIRCRAFT_ROTATION']) });
    const dependencies = graph.edges.filter((e) => e.category === 'DEPENDENCY');
    expect(dependencies.map((e) => e.relationship)).toEqual(['AIRCRAFT_ROTATION']);
    expect(dependencies[0].weight).toBe(1);
  });

  it('marks delayed flights and restricted airports with warning tones', () => {
    const graph = buildFlightGraph(networkResponse(), { dependencyTypes: ALL_TYPES });
    expect(graph.nodes.find((n) => n.id === E6100.id)!.tone).toBe('warning');
    expect(graph.nodes.find((n) => n.id === AI101.id)!.tone).toBe('normal');
    expect(graph.nodes.find((n) => n.id === 'airport:HYD')!.tone).toBe('warning');
  });

  it('assigns focus and downstream roles from a traversal response', () => {
    const graph = buildFlightGraph(affectedResponse(), { dependencyTypes: ALL_TYPES });
    const role = (id: string) => graph.nodes.find((n) => n.id === id)!.role;
    expect(role(AI101.id)).toBe('focus');
    expect(role(AI505.id)).toBe('downstream');
    expect(role(E6100.id)).toBe('downstream');
  });
});

describe('traversalRoles', () => {
  it('is empty for responses without a traversal', () => {
    expect(traversalRoles(networkResponse()).size).toBe(0);
    expect(traversalRoles(undefined).size).toBe(0);
  });
});

describe('buildRouteSummary', () => {
  it('aggregates flights per city pair in both directions', () => {
    const response = networkResponse();
    const graph = buildRouteSummary(response);
    expect(graph.mode).toBe('routes');
    expect(graph.nodes.every((n) => n.kind === 'airport')).toBe(true);
    expect(graph.nodes).toHaveLength(5);
    const bomBlr = graph.edges.find((e) => e.id === 'route:BLR-BOM')!;
    expect(bomBlr.weight).toBe(1);
    expect(graph.edges).toHaveLength(4);
    expect(graph.hiddenFlightCount).toBe(4);
  });
});

describe('neighbourhood', () => {
  it('returns the node, its neighbours and the connecting edges', () => {
    const graph = buildFlightGraph(networkResponse(), { dependencyTypes: ALL_TYPES });
    const { nodes, edges } = neighbourhood(graph, AI101.id);
    expect([...nodes].sort()).toEqual(['airport:BLR', 'airport:BOM', AI101.id, AI202.id, AI505.id].sort());
    expect(edges.size).toBe(4);
  });
});

describe('dependencyCounts and airportMovements', () => {
  it('counts dependencies by type for the given flights', () => {
    expect(dependencyCounts(networkResponse(), new Set([AI202.id]))).toEqual({
      AIRCRAFT_ROTATION: 0,
      PASSENGER_CONNECTION: 1,
      CREW_ROTATION: 1,
      OPERATIONAL_CONSTRAINT: 0,
    });
  });

  it('splits an airport\'s flights into departures and arrivals', () => {
    const { departures, arrivals } = airportMovements(networkResponse(), 'BLR');
    expect(departures.map((f) => f.flight_number).sort()).toEqual(['AI202', 'AI505']);
    expect(arrivals.map((f) => f.flight_number)).toEqual(['AI101']);
  });
});

// ── Stage 4: live data, provenance and disruptions in the graph ─────────────

describe('live data in the graph', () => {
  const OPEN = {
    disruption_id: 41,
    disruption_type: 'WEATHER_DELAY' as const,
    status: 'DETECTED' as const,
    severity: 'HIGH' as const,
    confidence: 0.85,
    detection_rule: 'WEATHER_OBSERVED',
    delay_minutes: 90,
    detected_at: '2026-11-09T06:00:00',
  };

  function withStage4(): GraphResponse {
    const base = networkResponse();
    return {
      ...base,
      data_mode: 'HYBRID',
      data_sources: ['SYNTHETIC_GENERATOR', 'OPENSKY'],
      nodes: base.nodes.map((node) => {
        if (node.type !== 'FLIGHT') return node;
        if (node.id === AI101.id) return { ...node, disruption: OPEN };
        if (node.id === AI202.id) return { ...node, disruption: { ...OPEN, disruption_id: 42, severity: 'CRITICAL' as const } };
        if (node.id === AI505.id) return { ...node, disruption: { ...OPEN, disruption_id: 43, status: 'RESOLVED' as const } };
        if (node.id === E6100.id) {
          return { ...node, data_origin: 'IMPORTED' as const, provenance: { data_source: 'OPENSKY', schedule_basis: 'OBSERVED' as const, callsign: 'IGO100' } };
        }
        return node;
      }),
    };
  }

  it('marks flights with an open disruption and flights from an external source', () => {
    const graph = buildFlightGraph(withStage4(), { dependencyTypes: ALL_TYPES });
    const node = (id: string) => graph.nodes.find((n) => n.id === id)!;
    expect(node(AI101.id).disrupted).toBe(true);
    expect(node(AI202.id).disrupted).toBe(true);
    // A resolved disruption no longer highlights the flight.
    expect(node(AI505.id).disrupted).toBeUndefined();
    expect(node(E6100.id).external).toBe(true);
    expect(node(AI101.id).external).toBeUndefined();
  });

  it('leaves synthetic flights without Stage 4 fields unmarked', () => {
    const graph = buildFlightGraph(networkResponse(), { dependencyTypes: ALL_TYPES });
    expect(graph.nodes.filter((n) => n.disrupted || n.external)).toHaveLength(0);
    expect(disruptedFlights(networkResponse())).toHaveLength(0);
    expect(dataSources(networkResponse())).toEqual([]);
  });

  it('lists disrupted flights most severe first', () => {
    expect(disruptedFlights(withStage4()).map((f) => f.flight_number)).toEqual(['AI202', 'AI101']);
    expect(disruptedFlights(undefined)).toEqual([]);
    expect(hasOpenDisruption(AI101)).toBe(false);
  });

  it('reports the data sources of a response, from the response or from its flights', () => {
    expect(dataSources(withStage4())).toEqual(['OPENSKY', 'SYNTHETIC_GENERATOR']);
    const withoutList = { ...withStage4(), data_sources: undefined };
    expect(dataSources(withoutList)).toEqual(['OPENSKY']);
    expect(dataSources(undefined)).toEqual([]);
  });

  it('changes the drawn graph when a refreshed response arrives', () => {
    const before = buildFlightGraph(networkResponse(), { dependencyTypes: ALL_TYPES });
    const refreshed = withStage4();
    // The refresh also brought a status change and a flight that was not there before.
    refreshed.nodes = refreshed.nodes.map((node) =>
      node.type === 'FLIGHT' && node.id === AI101.id ? { ...node, status: 'DELAYED' as const, delay_minutes: 90 } : node);
    const added = { ...flight('6E777', 'BOM', 'DEL', '11:00', '13:05', { airline: '6E' }), data_origin: 'IMPORTED' as const };
    refreshed.nodes = [...refreshed.nodes, added];
    const after = buildFlightGraph(refreshed, { dependencyTypes: ALL_TYPES });

    const tone = (graph: typeof before, id: string) => graph.nodes.find((n) => n.id === id)?.tone;
    expect(tone(before, AI101.id)).toBe('normal');
    expect(tone(after, AI101.id)).toBe('warning');
    expect(before.nodes.some((n) => n.id === added.id)).toBe(false);
    expect(after.nodes.find((n) => n.id === added.id)?.external).toBe(true);
    expect(after.nodes.filter((n) => n.kind === 'flight')).toHaveLength(5);
  });
});
