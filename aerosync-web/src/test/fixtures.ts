/**
 * TEST FIXTURES ONLY. Imported by *.test.ts(x) files, never by application
 * code, so they cannot reach the production dashboard. The shapes mirror the
 * Stage 1 graph DTOs (snake_case, *_local timestamps carry the airport offset).
 */
import type { AirportNode, AirportSummary, DependencyType, FlightNode, FlightStatus, GraphEdge, GraphResponse, LoginResponse, UserProfile } from '../api/types';

export function airport(iata: string, city: string, latitude: number, longitude: number, status: AirportNode['status'] = 'OPERATIONAL'): AirportNode {
  return {
    id: `airport:${iata}`,
    type: 'AIRPORT',
    airport_id: iata,
    iata_code: iata,
    airport_name: `${city} Airport`,
    city,
    timezone: 'Asia/Kolkata',
    latitude,
    longitude,
    status,
  };
}

/** Flight on 2026-11-09; departure/arrival are local IST clock times ("07:00"). */
export function flight(
  number: string,
  origin: string,
  destination: string,
  departure: string,
  arrival: string,
  options: { status?: FlightStatus; delay?: number; airline?: string; registration?: string } = {},
): FlightNode {
  const id = `fi-${number}`;
  const utc = (clock: string) => {
    const [h, m] = clock.split(':').map(Number);
    const minutes = h * 60 + m - 330;
    const date = new Date(Date.UTC(2026, 10, 9, 0, minutes));
    return date.toISOString().replace('.000Z', 'Z');
  };
  return {
    id: `flight:${id}`,
    type: 'FLIGHT',
    flight_instance_id: id,
    schedule_id: `sch-${number}`,
    flight_number: number,
    operating_airline: options.airline ?? number.slice(0, 2),
    operating_date: '2026-11-09',
    origin_airport: origin,
    destination_airport: destination,
    scheduled_departure: utc(departure),
    scheduled_arrival: utc(arrival),
    scheduled_departure_local: `2026-11-09T${departure}:00+05:30`,
    scheduled_arrival_local: `2026-11-09T${arrival}:00+05:30`,
    estimated_departure: null,
    estimated_arrival: null,
    actual_departure: null,
    actual_arrival: null,
    status: options.status ?? 'SCHEDULED',
    delay_minutes: options.delay ?? 0,
    aircraft: { aircraft_id: null, registration: options.registration ?? `VT-${number}`, aircraft_type: 'A320' },
    assigned_gate: null,
    data_origin: 'SYNTHETIC',
  };
}

export function routeEdges(f: FlightNode): GraphEdge[] {
  return [
    { id: `dep:${f.flight_instance_id}`, source: `airport:${f.origin_airport}`, target: f.id, relationship: 'DEPARTS_FROM', category: 'ROUTE', metadata: {} },
    { id: `arr:${f.flight_instance_id}`, source: f.id, target: `airport:${f.destination_airport}`, relationship: 'ARRIVES_AT', category: 'ROUTE', metadata: {} },
  ];
}

export function dependency(from: FlightNode, to: FlightNode, type: DependencyType, impactWeight = 0.8): GraphEdge {
  return {
    id: `dep-${type}-${from.flight_number}-${to.flight_number}`,
    source: from.id,
    target: to.id,
    relationship: type,
    category: 'DEPENDENCY',
    metadata: { impact_weight: impactWeight },
  };
}

export const BOM = airport('BOM', 'Mumbai', 19.09, 72.87);
export const PNQ = airport('PNQ', 'Pune', 18.58, 73.92);
export const BLR = airport('BLR', 'Bengaluru', 13.2, 77.71);
export const DEL = airport('DEL', 'Delhi', 28.56, 77.1);
export const HYD = airport('HYD', 'Hyderabad', 17.24, 78.43, 'RESTRICTED');

export const AI101 = flight('AI101', 'BOM', 'BLR', '07:00', '08:40', { registration: 'VT-A01' });
export const AI505 = flight('AI505', 'BLR', 'HYD', '09:30', '10:45', { registration: 'VT-A01' });
export const AI202 = flight('AI202', 'BLR', 'DEL', '10:30', '13:15');
export const E6100 = flight('6E100', 'DEL', 'BOM', '19:00', '21:10', { status: 'DELAYED', delay: 30, airline: '6E' });

/** National network for 2026-11-09: 5 airports, 4 flights, 3 dependencies. */
export function networkResponse(): GraphResponse {
  const flights = [AI101, AI505, AI202, E6100];
  const edges = [
    ...flights.flatMap(routeEdges),
    dependency(AI101, AI505, 'AIRCRAFT_ROTATION', 1),
    dependency(AI101, AI202, 'PASSENGER_CONNECTION', 0.6),
    dependency(AI202, E6100, 'CREW_ROTATION', 0.9),
  ];
  return {
    view: 'NATIONAL_NETWORK',
    service_date: '2026-11-09',
    data_window: { first_service_date: '2026-11-09', last_service_date: '2026-11-15' },
    synthetic_data: true,
    summary: {
      airport_count: 5,
      flight_count: flights.length,
      route_edge_count: flights.length * 2,
      dependency_edge_count: 3,
      dependency_edges_by_type: { AIRCRAFT_ROTATION: 1, PASSENGER_CONNECTION: 1, CREW_ROTATION: 1 },
      omitted_dependency_count: 0,
    },
    nodes: [BOM, PNQ, BLR, DEL, HYD, ...flights],
    edges,
  };
}

/** Affected-flights view centred on AI101 (downstream AI505, AI202, then 6E100). */
export function affectedResponse(): GraphResponse {
  const base = networkResponse();
  return {
    ...base,
    view: 'AFFECTED_FLIGHTS',
    focus_node_id: AI101.id,
    traversal: {
      max_depth: 6,
      horizon_hours: 24,
      min_impact_weight: 0,
      dependency_types: ['AIRCRAFT_ROTATION', 'PASSENGER_CONNECTION', 'CREW_ROTATION', 'OPERATIONAL_CONSTRAINT'],
      max_flights: 500,
      truncated: false,
      depth_limit_reached: false,
      downstream: [AI505, AI202, E6100].map((f, index) => ({
        node_id: f.id,
        flight_instance_id: f.flight_instance_id,
        flight_number: f.flight_number,
        operating_date: f.operating_date,
        origin_airport: f.origin_airport,
        destination_airport: f.destination_airport,
        scheduled_departure: f.scheduled_departure,
        depth: index < 2 ? 1 : 2,
        parent_node_id: index < 2 ? AI101.id : AI202.id,
        via_edge_id: 'x',
        via_dependency_type: index === 0 ? 'AIRCRAFT_ROTATION' : index === 1 ? 'PASSENGER_CONNECTION' : 'CREW_ROTATION',
        path_impact_weight: 0.5,
      })),
    },
  };
}

// ── Stage 3: users and sessions (test data only) ────────────────────────────

export function airportSummary(iata: string, city: string): AirportSummary {
  const node = airport(iata, city, 0, 0);
  return {
    airport_id: `id-${iata}`,
    node_id: node.id,
    iata_code: iata,
    airport_name: node.airport_name,
    city,
    timezone: 'Asia/Kolkata',
    latitude: 0,
    longitude: 0,
    status: 'OPERATIONAL',
  };
}

export function profile(username: string, airports: string[], role: UserProfile['role'] = 'OPERATIONS_CONTROLLER'): UserProfile {
  const cities: Record<string, string> = { BOM: 'Mumbai', DEL: 'Delhi', BLR: 'Bengaluru' };
  return {
    user_id: `user-${username}`,
    username,
    display_name: `${username} (test)`,
    role,
    can_operate: role === 'OPERATIONS_CONTROLLER',
    airports: airports.map((code) => airportSummary(code, cities[code] ?? code)),
  };
}

export function loginResponse(username: string, airports: string[], expiresAt = '2099-01-01T00:00:00Z'): LoginResponse {
  return { token: `token-of-${username}`, token_type: 'Bearer', expires_at: expiresAt, user: profile(username, airports) };
}
