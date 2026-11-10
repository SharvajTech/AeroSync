/**
 * Response types of the Stage 1 operational network APIs.
 *
 * They mirror the backend DTOs (com.aerosync.dto.graph / dto.operations)
 * exactly as Jackson serializes them: snake_case properties, ISO-8601 UTC
 * instants, yyyy-MM-dd dates. Fields annotated NON_NULL on the backend are
 * optional here.
 */

export type NodeType = 'AIRPORT' | 'FLIGHT';
export type EdgeCategory = 'ROUTE' | 'DEPENDENCY';
export type RouteRelationship = 'DEPARTS_FROM' | 'ARRIVES_AT';
export type DependencyType =
  | 'AIRCRAFT_ROTATION'
  | 'PASSENGER_CONNECTION'
  | 'CREW_ROTATION'
  | 'OPERATIONAL_CONSTRAINT';
export type RelationshipType = RouteRelationship | DependencyType;
export type GraphView = 'NATIONAL_NETWORK' | 'AIRPORT' | 'FLIGHT' | 'AFFECTED_FLIGHTS';
export type AirportStatus = 'OPERATIONAL' | 'RESTRICTED' | 'CLOSED';
export type FlightStatus =
  | 'SCHEDULED'
  | 'DELAYED'
  | 'BOARDING'
  | 'DEPARTED'
  | 'ARRIVED'
  | 'CANCELLED'
  | 'DIVERTED';
export type DataOrigin = 'SYNTHETIC' | 'IMPORTED' | 'MANUAL';
export type GateStatus = 'OPEN' | 'CLOSED' | 'MAINTENANCE';

export const DEPENDENCY_TYPES: readonly DependencyType[] = [
  'AIRCRAFT_ROTATION',
  'PASSENGER_CONNECTION',
  'CREW_ROTATION',
  'OPERATIONAL_CONSTRAINT',
];

export interface AirportNode {
  id: string;
  type: 'AIRPORT';
  airport_id: string;
  iata_code: string;
  airport_name: string;
  city: string;
  timezone: string;
  latitude: number;
  longitude: number;
  status: AirportStatus;
}

export interface AircraftInfo {
  aircraft_id: string | null;
  registration: string | null;
  aircraft_type: string | null;
}

export interface FlightNode {
  id: string;
  type: 'FLIGHT';
  flight_instance_id: string;
  schedule_id: string;
  flight_number: string;
  operating_airline: string;
  operating_date: string;
  origin_airport: string;
  destination_airport: string;
  scheduled_departure: string;
  scheduled_arrival: string;
  scheduled_departure_local: string | null;
  scheduled_arrival_local: string | null;
  estimated_departure: string | null;
  estimated_arrival: string | null;
  actual_departure: string | null;
  actual_arrival: string | null;
  status: FlightStatus;
  delay_minutes: number;
  aircraft: AircraftInfo;
  assigned_gate: string | null;
  data_origin: DataOrigin | null;
}

export type GraphNode = AirportNode | FlightNode;

export interface GraphEdge {
  id: string;
  source: string;
  target: string;
  relationship: RelationshipType;
  category: EdgeCategory;
  metadata: Record<string, unknown>;
}

export interface GraphSummary {
  airport_count: number;
  flight_count: number;
  route_edge_count: number;
  dependency_edge_count: number;
  dependency_edges_by_type: Record<string, number>;
  omitted_dependency_count: number;
}

export interface DataWindow {
  first_service_date: string | null;
  last_service_date: string | null;
}

export interface FlightReach {
  node_id: string;
  flight_instance_id: string;
  flight_number: string;
  operating_date: string;
  origin_airport: string;
  destination_airport: string;
  scheduled_departure: string;
  depth: number;
  parent_node_id: string;
  via_edge_id: string;
  via_dependency_type: DependencyType;
  path_impact_weight: number;
}

export interface Traversal {
  max_depth: number;
  horizon_hours?: number;
  min_impact_weight: number;
  dependency_types: DependencyType[];
  max_flights: number;
  truncated: boolean;
  depth_limit_reached: boolean;
  upstream?: FlightReach[];
  downstream?: FlightReach[];
}

export interface GraphResponse {
  view: GraphView;
  focus_node_id?: string;
  service_date?: string;
  data_window?: DataWindow;
  synthetic_data: boolean;
  data_notice?: string;
  traversal?: Traversal;
  summary: GraphSummary;
  nodes: GraphNode[];
  edges: GraphEdge[];
}

export interface AirportSummary {
  airport_id: string;
  node_id: string;
  iata_code: string;
  airport_name: string;
  city: string;
  timezone: string;
  latitude: number;
  longitude: number;
  status: AirportStatus;
}

export interface FlightMovement {
  node_id: string;
  flight_instance_id: string;
  flight_number: string;
  operating_airline: string;
  operating_date: string;
  counterpart_airport: string;
  scheduled_time: string;
  scheduled_time_local: string;
  estimated_time: string | null;
  actual_time: string | null;
  status: FlightStatus;
  delay_minutes: number;
  aircraft_registration: string | null;
  aircraft_type: string | null;
  assigned_gate: string | null;
  data_origin: DataOrigin | null;
}

export interface GateInfo {
  gate_id: number;
  terminal: string | null;
  gate_number: string | null;
  supported_aircraft_types: string[];
  status: GateStatus | null;
  available: boolean | null;
}

export interface AirportOperationsSummary {
  departure_count: number;
  arrival_count: number;
  delayed_departure_count: number;
  cancelled_departure_count: number;
  cancelled_arrival_count: number;
  average_departure_delay_minutes: number;
  departures_by_status: Record<string, number>;
  departures_by_airline: Record<string, number>;
}

export interface AirportOperations {
  airport: AirportSummary;
  service_date: string;
  timezone: string;
  window_start: string;
  window_end: string;
  synthetic_data: boolean;
  data_notice?: string;
  summary: AirportOperationsSummary;
  departures: FlightMovement[];
  arrivals: FlightMovement[];
  gates: GateInfo[];
}

/** Error body returned by the operational network endpoints. */
export interface ApiErrorBody {
  error?: string;
  status?: number;
}

export function isAirportNode(node: GraphNode): node is AirportNode {
  return node.type === 'AIRPORT';
}

export function isFlightNode(node: GraphNode): node is FlightNode {
  return node.type === 'FLIGHT';
}
