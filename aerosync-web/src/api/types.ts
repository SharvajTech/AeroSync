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
/** Stage 4: which records the backend produces and serves. */
export type DataMode = 'SYNTHETIC' | 'LIVE' | 'HYBRID';
/** PLANNED: scheduled times are a timetable. OBSERVED: no timetable; they are observed times. */
export type ScheduleBasis = 'PLANNED' | 'OBSERVED';

/** Where a flight record came from (Stage 4). */
export interface FlightProvenance {
  /** System that wrote the record: SYNTHETIC_GENERATOR, MANUAL_ENTRY, OPENSKY, ... */
  data_source: string;
  schedule_basis: ScheduleBasis;
  source_record_id?: string;
  callsign?: string;
  aircraft_icao24?: string;
  ingested_at?: string;
  source_updated_at?: string;
}

export type DisruptionSeverity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

/** The open disruption of a flight node (Stage 4), for highlighting in the graph. */
export interface FlightDisruptionInfo {
  disruption_id: number;
  disruption_type: DisruptionType;
  status: DisruptionStatus;
  severity: DisruptionSeverity | null;
  confidence: number | null;
  detection_rule: string | null;
  delay_minutes: number | null;
  detected_at: string | null;
}

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
  provenance?: FlightProvenance | null;
  disruption?: FlightDisruptionInfo | null;
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
  /** Stage 4: the backend's data mode and the sources of the flights in this response. */
  data_mode?: DataMode;
  data_sources?: string[];
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
  provenance?: FlightProvenance | null;
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
  data_mode?: DataMode;
}

/** Error body returned by the backend: {"error": "...", "status": 403, "code": "forbidden"}. */
export interface ApiErrorBody {
  error?: string;
  status?: number;
  /** Machine-readable reason on auth errors: authentication_required, token_expired, token_invalid, account_inactive, invalid_credentials, too_many_attempts, forbidden. */
  code?: string;
}

// ── Stage 3: authentication (AuthController) ──────────────────────────────

export type UserRole = 'OPERATIONS_CONTROLLER' | 'OPERATIONS_VIEWER';

/** GET /api/auth/me and the user part of the login response. */
export interface UserProfile {
  user_id: string;
  username: string;
  display_name: string;
  role: UserRole;
  /** False for read-only roles: resolution and disruption actions are refused by the backend. */
  can_operate: boolean;
  /** The airports this user is authorized for (never empty for a signed-in user). */
  airports: AirportSummary[];
}

/** POST /api/auth/login response. */
export interface LoginResponse {
  token: string;
  token_type: 'Bearer';
  expires_at: string;
  user: UserProfile;
}

// ── Stage 3: legacy operations endpoints used by the disruptions panel ─────

export type DisruptionType =
  | 'WEATHER_DELAY'
  | 'CREW_TIMEOUT'
  | 'AIRCRAFT_FAULT'
  | 'GATE_CONFLICT'
  | 'CASCADING_DELAY'
  // Stage 4: raised by the disruption monitor
  | 'FLIGHT_DELAY'
  | 'TURNAROUND_CONFLICT'
  | 'MISSED_CONNECTION'
  | 'AIRCRAFT_UNAVAILABLE';
export type DisruptionStatus = 'DETECTED' | 'RESOLVING' | 'RESOLVED' | 'FAILED' | 'PROPOSED';

export const DISRUPTION_TYPES: DisruptionType[] = [
  'WEATHER_DELAY',
  'CREW_TIMEOUT',
  'AIRCRAFT_FAULT',
  'GATE_CONFLICT',
  'CASCADING_DELAY',
];

/** GET /api/disruptions (com.aerosync.model.Disruption). */
export interface Disruption {
  id: number;
  flight_id: string;
  type: DisruptionType;
  status: DisruptionStatus;
  description: string | null;
  delay_minutes: number | null;
  passengers_affected: number | null;
  cost_impact: number | null;
  detected_at: string | null;
  resolved_at: string | null;
  // Stage 4 (absent on disruptions recorded before it)
  severity?: DisruptionSeverity | null;
  /** 0..1: how sure the detection is. */
  confidence?: number | null;
  /** MANUAL, SIMULATION, SYSTEM or MONITOR. */
  detection_source?: string | null;
  /** Monitoring rule, for example WEATHER_OBSERVED. */
  detection_rule?: string | null;
  data_origin?: DataOrigin | null;
  origin_airport?: string | null;
  destination_airport?: string | null;
  /** Set for operational-network flights; those disruptions are advisory. */
  flight_instance_id?: string | null;
  last_observed_at?: string | null;
  proposed_at?: string | null;
  approved_at?: string | null;
  approved_by?: string | null;
  rejected_at?: string | null;
  rejected_by?: string | null;
  rejection_count?: number | null;
  resolution_note?: string | null;
  /** False for advisory disruptions: there is nothing to propose or approve. */
  actionable?: boolean;
}

/** GET /api/flights (com.aerosync.model.Flight, the legacy operations flight). */
export interface OperationsFlight {
  id: number;
  flight_id: string;
  origin: string;
  destination: string;
  aircraft_type: string | null;
  scheduled_departure: string | null;
  status: string;
  assigned_gate: string | null;
  assigned_crew_id: string | null;
  risk_level: string | null;
}

/** One decision of a proposal (gate, crew, passengers, aircraft). */
export interface ResolutionSection {
  action?: string;
  reason?: string;
  [key: string]: unknown;
}

/** POST /api/agents/propose/{id} response; sent back unchanged to approve. */
export interface ResolutionProposal {
  disruption_id?: number;
  flight_id?: string;
  severity?: string;
  estimated_new_delay_mins?: number;
  actions_proposed?: string[];
  ai_explanation?: string;
  requires_approval?: boolean;
  gate_resolution?: ResolutionSection;
  crew_resolution?: ResolutionSection;
  passenger_resolution?: ResolutionSection;
  aircraft_resolution?: ResolutionSection;
  [key: string]: unknown;
}

/** POST /api/agents/approve/{id} response. */
export interface ResolutionResult extends ResolutionProposal {
  status?: string;
  cascade_result?: {
    flights_cascaded?: number;
    cascade_scope_note?: string;
    [key: string]: unknown;
  };
}

export function isAirportNode(node: GraphNode): node is AirportNode {
  return node.type === 'AIRPORT';
}

export function isFlightNode(node: GraphNode): node is FlightNode {
  return node.type === 'FLIGHT';
}

// ── Stage 4: pipeline status, live aircraft, weather ────────────────────────

export type FeedStatus = 'LIVE' | 'STALE' | 'FAILING' | 'RATE_LIMITED' | 'WAITING' | 'DISABLED' | 'UNCONFIGURED';

export interface ComponentStatus {
  status: 'UP' | 'DOWN';
  checked_at?: string;
  detail?: string;
}

export interface FeedRun {
  status: string;
  started_at: string;
  finished_at?: string;
  records_fetched: number;
  records_inserted: number;
  records_updated: number;
  records_unchanged: number;
  records_skipped: number;
  api_calls: number;
  skip_details?: string;
}

export interface FeedInfo {
  feed: string;
  data_source: string;
  description: string;
  status: FeedStatus;
  enabled: boolean;
  authenticated?: boolean;
  last_success_at?: string;
  last_attempt_at?: string;
  last_error?: string;
  interval_seconds?: number;
  next_attempt_at?: string;
  consecutive_failures?: number;
  rate_limit_remaining?: number;
  last_run?: FeedRun;
}

export interface MonitoringInfo {
  status: 'RUNNING' | 'WAITING' | 'FAILING' | 'STALE' | 'DISABLED';
  enabled: boolean;
  last_run_at?: string;
  interval_seconds?: number;
  detected?: number;
  opened?: number;
  refreshed?: number;
  suppressed?: number;
  cleared?: number;
  last_error?: string;
}

/** GET /api/system/status */
export interface SystemStatus {
  generated_at: string;
  data_mode: DataMode;
  data_mode_description: string;
  database: ComponentStatus;
  ml_service: ComponentStatus;
  feeds: FeedInfo[];
  monitoring: MonitoringInfo;
}

export type AircraftPhase = 'ON_GROUND' | 'AIRBORNE' | 'CLIMBING' | 'DESCENDING' | 'LEVEL';

export interface LiveAircraft {
  icao24: string;
  callsign?: string;
  /** Derived from the callsign; absent when the callsign is not a flight number. */
  flight_number?: string;
  operating_airline?: string;
  origin_country?: string;
  latitude: number;
  longitude: number;
  baro_altitude_m?: number;
  geo_altitude_m?: number;
  on_ground: boolean;
  velocity_ms?: number;
  true_track_deg?: number;
  vertical_rate_ms?: number;
  phase: AircraftPhase;
  distance_km?: number;
  last_contact: string;
  age_seconds: number;
  stale: boolean;
  data_source: string;
}

/** GET /api/airports/{iata}/live-aircraft */
export interface AirportLiveTraffic {
  airport: string;
  status: 'LIVE' | 'STALE' | 'DISABLED' | 'NO_DATA';
  data_source: string;
  radius_km: number;
  last_success_at?: string;
  generated_at: string;
  on_ground: number;
  airborne: number;
  aircraft: LiveAircraft[];
  note?: string;
}

export interface WeatherReport {
  kind: 'OBSERVATION' | 'FORECAST';
  /** Observation time, or the time a forecast applies to. */
  valid_at: string;
  fetched_at: string;
  severity_score: number;
  severity_label: string;
  condition_code?: number;
  description?: string;
  wind_speed_ms?: number;
  visibility_m?: number;
  temperature_c?: number;
  precipitation_mm?: number;
  precipitation_probability?: number;
  data_source: string;
}

/** GET /api/airports/{iata}/weather */
export interface AirportWeather {
  airport: string;
  status: 'LIVE' | 'STALE' | 'UNAVAILABLE';
  source_configured: boolean;
  observation?: WeatherReport;
  observation_age_minutes?: number;
  forecast?: WeatherReport[];
  note?: string;
}
