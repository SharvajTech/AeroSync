# Operational network graph (Stage 1)

AeroSync now has two separate graphs:

| | GNN feature graph (unchanged) | Operational network graph (new) |
|---|---|---|
| Purpose | Predict cascade risk | Represent the real structure of the network: airports, flights, dependencies |
| Lives in | `aerosync-ml/gnn` (numpy/joblib artefacts) | PostgreSQL tables + `com.aerosync.graph` |
| Nodes | One synthetic day of flights | `AIRPORT` and `FLIGHT` (one node per flight *instance*) |
| Airports | 9 (`AIRPORT_ENCODE`) | 15 (the 9 GNN airports + COK, JAI, LKO, GAU, NAG, BBI) |

The GNN, its encoders and its training data are not modified. The six extra
airports exist only in the operational graph.

## Graph model

```
airport:BOM ──DEPARTS_FROM──▶ flight:<AI101 instance> ──ARRIVES_AT──▶ airport:BLR
                                    │  │
            PASSENGER_CONNECTION ───┘  └─── AIRCRAFT_ROTATION
                     ▼                              ▼
     airport:BLR ──DEPARTS_FROM──▶ flight:<AI202>   flight:<AI505> (same tail as AI101)
                                        │
                                        └──ARRIVES_AT──▶ airport:DEL
```

* **Node ids** are stable: `airport:<IATA>` and `flight:<flight_instances.flight_id>`.
  A flight number is never an id (AI101 operates every day).
* **ROUTE edges** (`category: ROUTE`): exactly two per flight, derived from the
  schedule: `airport -DEPARTS_FROM-> flight` and `flight -ARRIVES_AT-> airport`.
  There are no airport-to-airport edges, so only routes that are actually flown
  appear (47 of 105 possible city pairs in the synthetic data).
* **DEPENDENCY edges** (`category: DEPENDENCY`): flight-to-flight rows of
  `flight_dependencies`: `AIRCRAFT_ROTATION`, `PASSENGER_CONNECTION`,
  `CREW_ROTATION`, `OPERATIONAL_CONSTRAINT`, with `impact_weight` (0..1),
  `minimum_connection_minutes` and JSON `metadata` in the edge metadata.
* Dependencies whose other flight is outside the requested scope (for example
  an overnight rotation into the next service date) are not returned as
  dangling edges; they are counted in `summary.omitted_dependency_count`.

Dependency rules (`com.aerosync.graph.DependencyRules`, applied by the
generator, the seeder and `FlightNetworkService`):

* source and target are different flights; `impact_weight` in [0, 1];
* rotation / passenger connection / crew rotation: the target departs from the
  airport where the source arrives, at least `minimum_connection_minutes` after
  its scheduled arrival and within 24 h;
* aircraft rotation: same aircraft when both are assigned;
* passenger connection: the target does not fly back to the source's origin;
* operational constraint: the flights share an airport and the target does not
  depart before the source.

## Schema (Flyway)

Migrations live in `src/main/resources/db/migration` and run before Hibernate.
An existing database without Flyway history is baselined at version 0
(`spring.flyway.baseline-on-migrate=true`) and migrated forward; nothing is
dropped or recreated. `ddl-auto=update` keeps managing the original tables
(`flights`, `crew`, `disruptions`).

| Migration | Content |
|---|---|
| `V1__operational_network_schema.sql` | `airports`, `aircraft`, `flight_schedules`, `flight_instances`, `flight_dependencies`; adds `airport_id` (FK) and `status` to the existing `gates` table |
| `V2__reference_airports.sql` | The 15 reference airports with deterministic UUIDs |

| Table | Key constraints |
|---|---|
| `airports` | PK `airport_id`; unique 3-letter `iata_code` (regex check); lat/long ranges; IANA `timezone` |
| `aircraft` | unique `registration`; FK `current_airport_id` |
| `flight_schedules` | unique leg `(flight_number, service_date, origin_airport_id)`; FKs to airports; origin ≠ destination; arrival > departure; flight-number format |
| `flight_instances` | PK `flight_id`; **unique `schedule_id`** (one operating instance per leg); FKs to schedule, aircraft, gate; `version` for optimistic locking; delay ≥ 0 |
| `flight_dependencies` | unique `(source, target, type)`; FKs to both flights; no self-loops; weight in [0,1]; minimum connection required except for operational constraints; `metadata` is a JSON object |
| `gates` (existing) | + `airport_id` (nullable FK, existing rows keep NULL), + `status` (existing rows set to `OPEN`), unique `(airport_id, terminal, gate_number)`. `id` is the gate id and `compatible_types` holds the supported aircraft types |

All timestamps are `TIMESTAMPTZ` written as UTC instants. `service_date` is the
local departure date at the origin; each airport keeps its time zone for
display (`*_local` fields in responses).

The original `flights` table is not converted: its `flight_id` is globally
unique (one row per flight number), it is regenerated daily and it carries the
ML feature columns used by the existing APIs, disruption flow and dashboard.
The new tables sit next to it; moving those flows onto `flight_instances` is a
later step.

## API

All endpoints are read-only `GET`s. Like `/api/flights`, they are public for
the dashboard; the JWT filter only exempts `GET`/`HEAD`/`OPTIONS` on these
prefixes, so future write endpoints under them will require a token.

| Endpoint | Parameters |
|---|---|
| `GET /api/graph/network` | `date` (yyyy-MM-dd, default today in `aerosync.operations.timezone`), `include_dependencies` (default `true`) |
| `GET /api/graph/airports/{iata}` | `date` (local day at the airport), `include_dependencies` |
| `GET /api/graph/flights/{flightId}` | `flightId` = instance UUID or `flight:<uuid>`; `depth` 1-5 (default 1) |
| `GET /api/graph/flights/{flightId}/affected` | `max_depth` 1-20 (6), `horizon_hours` 1-168 (24, `0` = none), `types` (comma separated), `min_impact_weight` (0), `max_flights` 1-2000 (500) |
| `GET /api/airports` | – |
| `GET /api/airports/{iata}/operations` | `date` |

`/affected` is structural reachability over active dependencies (BFS, each
flight once, cycle-safe). `path_impact_weight` is the product of the weights
along the path that first reached a flight; it is not a delay prediction.

Errors use the existing `{"error": "...", "status": 404}` shape: 404 for an
unknown airport/flight, 400 for a malformed IATA code, flight id (including a
flight number such as `AI101`), date or out-of-range parameter.

### Example: `GET /api/graph/network?date=2026-11-09` (lists trimmed)

```json
{
  "view": "NATIONAL_NETWORK",
  "service_date": "2026-11-09",
  "data_window": { "first_service_date": "2026-11-09", "last_service_date": "2026-11-15" },
  "synthetic_data": true,
  "data_notice": "Contains SYNTHETIC demonstration data generated by AeroSync; it is not real flight operations data.",
  "summary": {
    "airport_count": 15, "flight_count": 410, "route_edge_count": 820, "dependency_edge_count": 583,
    "dependency_edges_by_type": { "AIRCRAFT_ROTATION": 345, "PASSENGER_CONNECTION": 206, "CREW_ROTATION": 17, "OPERATIONAL_CONSTRAINT": 15 },
    "omitted_dependency_count": 65
  },
  "nodes": [
    { "id": "airport:BOM", "type": "AIRPORT", "airport_id": "d16bf4e0-1953-355b-babd-56426f6ff492",
      "iata_code": "BOM", "airport_name": "Chhatrapati Shivaji Maharaj International Airport", "city": "Mumbai",
      "timezone": "Asia/Kolkata", "latitude": 19.0896, "longitude": 72.8656, "status": "OPERATIONAL" },
    { "id": "flight:2612af7f-d701-3bb8-b0c2-2c19eaac6724", "type": "FLIGHT",
      "flight_instance_id": "2612af7f-d701-3bb8-b0c2-2c19eaac6724", "schedule_id": "dd97c455-8386-3cde-acbc-6001ba39d82c",
      "flight_number": "AI101", "operating_airline": "AI", "operating_date": "2026-11-09",
      "origin_airport": "BOM", "destination_airport": "BLR",
      "scheduled_departure": "2026-11-09T01:30:00Z", "scheduled_arrival": "2026-11-09T03:10:00Z",
      "scheduled_departure_local": "2026-11-09T07:00:00+05:30", "scheduled_arrival_local": "2026-11-09T08:40:00+05:30",
      "estimated_departure": null, "estimated_arrival": null, "actual_departure": null, "actual_arrival": null,
      "status": "SCHEDULED", "delay_minutes": 0,
      "aircraft": { "aircraft_id": "11418688-2a03-3fd5-8df4-6a7e4aa73fb1", "registration": "VT-A01", "aircraft_type": "A320" },
      "assigned_gate": null, "data_origin": "SYNTHETIC" }
  ],
  "edges": [
    { "id": "departs_from:2612af7f-d701-3bb8-b0c2-2c19eaac6724", "source": "airport:BOM",
      "target": "flight:2612af7f-d701-3bb8-b0c2-2c19eaac6724", "relationship": "DEPARTS_FROM", "category": "ROUTE",
      "metadata": { "scheduled_time": "2026-11-09T01:30:00Z" } },
    { "id": "arrives_at:2612af7f-d701-3bb8-b0c2-2c19eaac6724", "source": "flight:2612af7f-d701-3bb8-b0c2-2c19eaac6724",
      "target": "airport:BLR", "relationship": "ARRIVES_AT", "category": "ROUTE",
      "metadata": { "scheduled_time": "2026-11-09T03:10:00Z" } },
    { "id": "dependency:fade0d2f-e480-3072-b0be-2b6d8228bedc", "source": "flight:2612af7f-d701-3bb8-b0c2-2c19eaac6724",
      "target": "flight:ea7c4768-520b-3ebd-b4f3-f496476c2ddb", "relationship": "AIRCRAFT_ROTATION", "category": "DEPENDENCY",
      "metadata": { "dependency_id": "fade0d2f-e480-3072-b0be-2b6d8228bedc", "dependency_type": "AIRCRAFT_ROTATION",
        "impact_weight": 0.933, "minimum_connection_minutes": 30, "active": true, "data_origin": "SYNTHETIC",
        "synthetic": true, "aircraft_registration": "VT-A01", "aircraft_type": "A320", "station": "BLR",
        "scheduled_ground_minutes": 50, "minimum_turnaround_minutes": 30, "slack_minutes": 20,
        "overnight": false, "crew_continuity": true } },
    { "id": "dependency:f0bab993-0621-39d3-837a-acf89ae3c221", "source": "flight:2612af7f-d701-3bb8-b0c2-2c19eaac6724",
      "target": "flight:e2b2d671-ae9b-39cf-915f-7e78ba1140b8", "relationship": "PASSENGER_CONNECTION", "category": "DEPENDENCY",
      "metadata": { "dependency_id": "f0bab993-0621-39d3-837a-acf89ae3c221", "dependency_type": "PASSENGER_CONNECTION",
        "impact_weight": 0.449, "minimum_connection_minutes": 45, "active": true, "data_origin": "SYNTHETIC",
        "synthetic": true, "hub": "BLR", "connecting_passengers": 26, "scheduled_connection_minutes": 110 } }
  ]
}
```

### Example: `GET /api/graph/flights/flight:2612af7f-d701-3bb8-b0c2-2c19eaac6724` (AI101, `traversal` part)

```json
{
  "view": "FLIGHT",
  "focus_node_id": "flight:2612af7f-d701-3bb8-b0c2-2c19eaac6724",
  "service_date": "2026-11-09",
  "synthetic_data": true,
  "traversal": {
    "max_depth": 1, "min_impact_weight": 0.0,
    "dependency_types": ["AIRCRAFT_ROTATION", "PASSENGER_CONNECTION", "CREW_ROTATION", "OPERATIONAL_CONSTRAINT"],
    "max_flights": 2000, "truncated": false, "depth_limit_reached": true,
    "upstream": [],
    "downstream": [
      { "node_id": "flight:ea7c4768-520b-3ebd-b4f3-f496476c2ddb", "flight_number": "AI505", "operating_date": "2026-11-09",
        "origin_airport": "BLR", "destination_airport": "HYD", "scheduled_departure": "2026-11-09T04:00:00Z", "depth": 1,
        "parent_node_id": "flight:2612af7f-d701-3bb8-b0c2-2c19eaac6724",
        "via_edge_id": "dependency:fade0d2f-e480-3072-b0be-2b6d8228bedc", "via_dependency_type": "AIRCRAFT_ROTATION",
        "path_impact_weight": 0.933 },
      { "node_id": "flight:e2b2d671-ae9b-39cf-915f-7e78ba1140b8", "flight_number": "AI202", "operating_date": "2026-11-09",
        "origin_airport": "BLR", "destination_airport": "DEL", "scheduled_departure": "2026-11-09T05:00:00Z", "depth": 1,
        "parent_node_id": "flight:2612af7f-d701-3bb8-b0c2-2c19eaac6724",
        "via_edge_id": "dependency:f0bab993-0621-39d3-837a-acf89ae3c221", "via_dependency_type": "PASSENGER_CONNECTION",
        "path_impact_weight": 0.449 }
    ]
  },
  "summary": { "airport_count": 4, "flight_count": 3, "route_edge_count": 6, "dependency_edge_count": 2,
    "dependency_edges_by_type": { "AIRCRAFT_ROTATION": 1, "PASSENGER_CONNECTION": 1, "CREW_ROTATION": 0, "OPERATIONAL_CONSTRAINT": 0 },
    "omitted_dependency_count": 0 },
  "nodes": ["… AI101, AI505, AI202 and airports BOM, BLR, HYD, DEL …"],
  "edges": ["… 6 ROUTE edges and the 2 DEPENDENCY edges above …"]
}
```

### Example: `GET /api/airports/BLR/operations?date=2026-11-09` (lists trimmed)

```json
{
  "airport": { "airport_id": "5d202b31-74c9-310b-ae98-1944e9919764", "node_id": "airport:BLR", "iata_code": "BLR",
    "airport_name": "Kempegowda International Airport", "city": "Bengaluru", "timezone": "Asia/Kolkata",
    "latitude": 13.1986, "longitude": 77.7066, "status": "OPERATIONAL" },
  "service_date": "2026-11-09",
  "timezone": "Asia/Kolkata",
  "window_start": "2026-11-08T18:30:00Z",
  "window_end": "2026-11-09T18:30:00Z",
  "synthetic_data": true,
  "data_notice": "Contains SYNTHETIC demonstration data generated by AeroSync; it is not real flight operations data.",
  "summary": { "departure_count": 63, "arrival_count": 63, "delayed_departure_count": 0, "cancelled_departure_count": 0,
    "cancelled_arrival_count": 0, "average_departure_delay_minutes": 0.0,
    "departures_by_status": { "SCHEDULED": 63, "DELAYED": 0, "BOARDING": 0, "DEPARTED": 0, "ARRIVED": 0, "CANCELLED": 0, "DIVERTED": 0 },
    "departures_by_airline": { "6E": 32, "AI": 18, "IX": 9, "QP": 3, "SG": 1 } },
  "departures": [
    { "node_id": "flight:daca155c-7083-37ed-90f0-6f41aa7aa517", "flight_instance_id": "daca155c-7083-37ed-90f0-6f41aa7aa517",
      "flight_number": "6E2204", "operating_airline": "6E", "operating_date": "2026-11-09", "counterpart_airport": "CCU",
      "scheduled_time": "2026-11-09T00:05:00Z", "scheduled_time_local": "2026-11-09T05:35:00+05:30",
      "estimated_time": null, "actual_time": null, "status": "SCHEDULED", "delay_minutes": 0,
      "aircraft_registration": "VT-I33", "aircraft_type": "A320", "assigned_gate": null, "data_origin": "SYNTHETIC" }
  ],
  "arrivals": ["…"],
  "gates": []
}
```

The values above come from the synthetic data generated for a start date of
2026-11-09 (identifiers are name-based, so they change with the start date).
`gates` is empty because no per-airport gates are seeded (see below).

## Synthetic data

On startup `SyntheticNetworkSeeder` fills the operational tables **only if they
are all empty** (aircraft, schedules, instances, dependencies), in one
transaction. With the defaults it creates, for 7 service dates starting today
(IST):

| Item | Count |
|---|---|
| Airports | 15 (from V2; the seeder inserts any that are missing) |
| Aircraft | 65 synthetic tails, 6.3 legs per day each |
| Flight instances / schedules | 2,870 (410 per day, the same daily timetable every day) |
| Aircraft rotations | 2,805 (incl. overnight links) |
| Passenger connections | 1,393 (incl. AI101 -> AI202 every day) |
| Crew rotations | 136 |
| Operational constraints | 87 |

How it is generated (`SyntheticNetworkGenerator`, deterministic for a seed):
each aircraft flies a daily *line of flying* from its base (first departure
05:30-07:30, back home by 23:59) along a fixed list of 47 served city pairs,
choosing legs by remaining route frequency. Block time = 35 min + distance at
750 km/h (ATR72 at 480 km/h, only on routes under 450 km), rounded to 5 min;
ground time = minimum turnaround (ATR72 20, A320/B737 30, A321 35, B777 70 min)
plus 0-30 min. Passenger connections are same-airline connections at the six
hubs inside a minimum connection time (60 min DEL/BOM, 45 min elsewhere) to
+240 min; crew rotations are crews changing aircraft at hubs (40-180 min);
operational constraints are consecutive same-airline departures within 5-20 min
sharing a ground-handling team at DEL/BOM/BLR.

How it is marked as synthetic: `data_origin = 'SYNTHETIC'` on every aircraft,
schedule and dependency; `"synthetic": true` in dependency metadata;
`synthetic_data` + `data_notice` in API responses; registrations contain a digit
(`VT-A01`), which no real Indian registration does. Only planned data is
generated: every instance is `SCHEDULED` with no estimated/actual times and no
delay. Airline designators follow the existing demo data (AI, 6E, IX, QP, SG);
flight numbers, times, tails and dependencies are invented.

Configuration (environment variables):

| Variable | Default | Meaning |
|---|---|---|
| `AEROSYNC_SYNTHETIC_NETWORK_ENABLED` | `true` | seed on startup when the tables are empty |
| `AEROSYNC_SYNTHETIC_NETWORK_DAYS` | `7` | number of service dates (1-31) |
| `AEROSYNC_SYNTHETIC_NETWORK_START_DATE` | today | first service date, `yyyy-MM-dd` |
| `AEROSYNC_SYNTHETIC_NETWORK_SEED` | `20261109` | random seed |
| `AEROSYNC_OPERATIONS_TIMEZONE` | `Asia/Kolkata` | defines "today" for `/api/graph/network` |
| `JPA_SHOW_SQL` | `true` | set `false` to silence SQL logging during the first (seeding) start |

To regenerate (development databases only):

```sql
TRUNCATE flight_dependencies, flight_instances, flight_schedules, aircraft;
```

then restart the backend.

## Tests

* `com.aerosync.graph.*Test`, `com.aerosync.synthetic.*Test`: plain JUnit, no
  Spring (graph construction, traversal, dependency rules, identifiers,
  generator properties, V2 ↔ `ReferenceAirports` consistency).
* `com.aerosync.network.*IntegrationTest`: Spring Boot + H2 (PostgreSQL mode,
  profile `test`) for repositories, constraints, validation, graph queries,
  HTTP contract and the seeder.
* `com.aerosync.security.JwtFilterGraphAccessTest`: public read-only paths.
* `aerosync-ml/tests/test_gnn_compatibility.py`: the saved GNN still reproduces
  its recorded test ROC-AUC, its airport encoding is unchanged, and every GNN
  airport and route exists in the operational network.

The Flyway SQL is PostgreSQL-specific and is not executed by the H2 tests.
