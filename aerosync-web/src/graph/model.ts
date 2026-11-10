import type {
  AirportNode,
  DependencyType,
  FlightNode,
  GraphEdge,
  GraphResponse,
  RelationshipType,
} from '../api/types';
import { isAirportNode, isFlightNode } from '../api/types';
import { inWindow, type TimeWindow } from '../utils/filters';
import { isDelayed } from '../utils/format';
import { placeFlights, projectAirports, type Point } from './layout';

/**
 * Turns Stage 1 graph responses into what the renderer draws. Pure functions,
 * no Cytoscape or React here, so the logic is unit-tested on its own.
 */

export type NodeTone = 'normal' | 'warning' | 'critical';
export type TraversalRole = 'focus' | 'upstream' | 'downstream';

export interface VisualNode {
  id: string;
  kind: 'airport' | 'flight';
  label: string;
  position: Point;
  tone: NodeTone;
  role?: TraversalRole;
  /** Shown only because it shares a dependency with a flight in the selected time window. */
  context?: boolean;
  ref: AirportNode | FlightNode;
}

export interface VisualEdge {
  id: string;
  source: string;
  target: string;
  relationship: RelationshipType | 'ROUTE_SUMMARY';
  category: 'ROUTE' | 'DEPENDENCY';
  /** impact_weight for dependencies, flight count for route summaries. */
  weight: number;
  label?: string;
  ref?: GraphEdge;
}

export interface VisualGraph {
  /** 'flights' = hybrid airport + flight graph; 'routes' = airports with aggregated routes. */
  mode: 'flights' | 'routes';
  nodes: VisualNode[];
  edges: VisualEdge[];
  /** Flights in the response that the current filters hide. */
  hiddenFlightCount: number;
}

export interface FlightGraphOptions {
  /** Only flights whose movement falls in this local-time window (null = all). */
  window?: TimeWindow | null;
  /** Airport view: use arrival time for flights arriving at this airport. */
  focusAirport?: string | null;
  /** Dependency types to draw. */
  dependencyTypes: ReadonlySet<DependencyType>;
  /** Draw every airport of the response, not only those used by visible flights. */
  includeAllAirports?: boolean;
  /** Also draw flights outside the window that share a dependency with a flight inside it. */
  includeConnectedFlights?: boolean;
}

export function airportNodes(response: GraphResponse | undefined): AirportNode[] {
  return response ? response.nodes.filter(isAirportNode) : [];
}

export function flightNodes(response: GraphResponse | undefined): FlightNode[] {
  return response ? response.nodes.filter(isFlightNode) : [];
}

export function flightTone(flight: FlightNode): NodeTone {
  if (flight.status === 'CANCELLED' || flight.status === 'DIVERTED') return 'critical';
  return isDelayed(flight.status, flight.delay_minutes) ? 'warning' : 'normal';
}

export function airportTone(airport: AirportNode): NodeTone {
  if (airport.status === 'CLOSED') return 'critical';
  return airport.status === 'RESTRICTED' ? 'warning' : 'normal';
}

/** Focus / upstream / downstream roles from a flight or affected-flights response. */
export function traversalRoles(response: GraphResponse | undefined): Map<string, TraversalRole> {
  const roles = new Map<string, TraversalRole>();
  if (!response?.traversal) return roles;
  response.traversal.upstream?.forEach((r) => roles.set(r.node_id, 'upstream'));
  response.traversal.downstream?.forEach((r) => roles.set(r.node_id, 'downstream'));
  if (response.focus_node_id?.startsWith('flight:')) roles.set(response.focus_node_id, 'focus');
  return roles;
}

function movementTime(flight: FlightNode, focusAirport: string | null | undefined): string | null {
  if (focusAirport && flight.destination_airport === focusAirport && flight.origin_airport !== focusAirport) {
    return flight.scheduled_arrival_local;
  }
  return flight.scheduled_departure_local;
}

/** Hybrid airport + flight graph with route and dependency edges, filtered for readability. */
export function buildFlightGraph(response: GraphResponse, options: FlightGraphOptions): VisualGraph {
  const airports = airportNodes(response);
  const flights = flightNodes(response);
  const windowFlights = options.window
    ? flights.filter((f) => inWindow(movementTime(f, options.focusAirport), options.window as TimeWindow))
    : flights;
  const visibleFlightIds = new Set(windowFlights.map((f) => f.id));
  const contextIds = new Set<string>();
  if (options.includeConnectedFlights && options.window) {
    for (const edge of response.edges) {
      if (edge.category !== 'DEPENDENCY' || !options.dependencyTypes.has(edge.relationship as DependencyType)) continue;
      if (visibleFlightIds.has(edge.source) && !visibleFlightIds.has(edge.target)) contextIds.add(edge.target);
      if (visibleFlightIds.has(edge.target) && !visibleFlightIds.has(edge.source)) contextIds.add(edge.source);
    }
    contextIds.forEach((id) => visibleFlightIds.add(id));
  }
  const visibleFlights = flights.filter((f) => visibleFlightIds.has(f.id));

  const usedAirports = new Set<string>();
  visibleFlights.forEach((f) => {
    usedAirports.add(f.origin_airport);
    usedAirports.add(f.destination_airport);
  });
  if (options.focusAirport) usedAirports.add(options.focusAirport);
  const visibleAirports = options.includeAllAirports ? airports : airports.filter((a) => usedAirports.has(a.iata_code));

  // Project every airport of the response so positions stay stable while filters change.
  const airportPositions = projectAirports(airports);
  const flightPositions = placeFlights(visibleFlights, airportPositions);
  const roles = traversalRoles(response);

  const nodes: VisualNode[] = [
    ...visibleAirports.map<VisualNode>((airport) => ({
      id: airport.id,
      kind: 'airport',
      label: airport.iata_code,
      position: airportPositions.get(airport.iata_code) ?? { x: 0, y: 0 },
      tone: airportTone(airport),
      ref: airport,
    })),
    ...visibleFlights.map<VisualNode>((flight) => ({
      id: flight.id,
      kind: 'flight',
      label: flight.flight_number,
      position: flightPositions.get(flight.id) ?? { x: 0, y: 0 },
      tone: flightTone(flight),
      role: roles.get(flight.id),
      context: contextIds.has(flight.id) || undefined,
      ref: flight,
    })),
  ];
  const nodeIds = new Set(nodes.map((n) => n.id));

  const edges: VisualEdge[] = [];
  for (const edge of response.edges) {
    if (!nodeIds.has(edge.source) || !nodeIds.has(edge.target)) continue;
    if (edge.category === 'DEPENDENCY' && !options.dependencyTypes.has(edge.relationship as DependencyType)) continue;
    const weight = typeof edge.metadata?.impact_weight === 'number' ? (edge.metadata.impact_weight as number) : 1;
    edges.push({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      relationship: edge.relationship,
      category: edge.category,
      weight,
      ref: edge,
    });
  }

  return {
    mode: 'flights',
    nodes,
    edges,
    hiddenFlightCount: flights.length - visibleFlightIds.size,
  };
}

/**
 * Overview of a whole day: airports plus one line per served city pair,
 * weighted by the number of flights (both directions). Flights are not drawn.
 */
export function buildRouteSummary(response: GraphResponse): VisualGraph {
  const airports = airportNodes(response);
  const positions = projectAirports(airports);
  const counts = new Map<string, { a: string; b: string; count: number }>();
  for (const flight of flightNodes(response)) {
    const [a, b] = [flight.origin_airport, flight.destination_airport].sort();
    const key = `${a}-${b}`;
    const entry = counts.get(key);
    if (entry) entry.count += 1;
    else counts.set(key, { a, b, count: 1 });
  }
  const nodes: VisualNode[] = airports.map((airport) => ({
    id: airport.id,
    kind: 'airport',
    label: airport.iata_code,
    position: positions.get(airport.iata_code) ?? { x: 0, y: 0 },
    tone: airportTone(airport),
    ref: airport,
  }));
  const airportIds = new Map(airports.map((a) => [a.iata_code, a.id]));
  const edges: VisualEdge[] = [];
  for (const [key, { a, b, count }] of counts) {
    const source = airportIds.get(a);
    const target = airportIds.get(b);
    if (!source || !target) continue;
    edges.push({
      id: `route:${key}`,
      source,
      target,
      relationship: 'ROUTE_SUMMARY',
      category: 'ROUTE',
      weight: count,
      label: `${count}`,
    });
  }
  return { mode: 'routes', nodes, edges, hiddenFlightCount: flightNodes(response).length };
}

/** Ids of the node, its neighbours and the edges between them. */
export function neighbourhood(graph: VisualGraph, nodeId: string): { nodes: Set<string>; edges: Set<string> } {
  const nodes = new Set<string>([nodeId]);
  const edges = new Set<string>();
  for (const edge of graph.edges) {
    if (edge.source === nodeId || edge.target === nodeId) {
      edges.add(edge.id);
      nodes.add(edge.source);
      nodes.add(edge.target);
    }
  }
  return { nodes, edges };
}

/** Dependency counts (by type) of the dependency edges touching any of the given flights. */
export function dependencyCounts(response: GraphResponse | undefined, flightIds: ReadonlySet<string>): Record<DependencyType, number> {
  const counts: Record<DependencyType, number> = {
    AIRCRAFT_ROTATION: 0,
    PASSENGER_CONNECTION: 0,
    CREW_ROTATION: 0,
    OPERATIONAL_CONSTRAINT: 0,
  };
  for (const edge of response?.edges ?? []) {
    if (edge.category !== 'DEPENDENCY') continue;
    if (flightIds.has(edge.source) || flightIds.has(edge.target)) {
      counts[edge.relationship as DependencyType] += 1;
    }
  }
  return counts;
}

/** Flights departing from / arriving at an airport in a response. */
export function airportMovements(response: GraphResponse | undefined, iata: string): { departures: FlightNode[]; arrivals: FlightNode[] } {
  const flights = flightNodes(response);
  return {
    departures: flights.filter((f) => f.origin_airport === iata),
    arrivals: flights.filter((f) => f.destination_airport === iata),
  };
}
