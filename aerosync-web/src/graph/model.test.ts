import { describe, expect, it } from 'vitest';
import { DEPENDENCY_TYPES, type DependencyType } from '../api/types';
import { AI101, AI202, AI505, E6100, affectedResponse, networkResponse } from '../test/fixtures';
import {
  airportMovements,
  buildFlightGraph,
  buildRouteSummary,
  dependencyCounts,
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
