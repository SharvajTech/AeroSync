# Live data, disruption monitoring and data modes (Stage 4)

This document describes how AeroSync brings external aviation data into the
existing operational network, how it watches for disruptions, and what the
data can and cannot tell. It complements
[`OPERATIONAL_NETWORK.md`](OPERATIONAL_NETWORK.md) (the graph, Stage 1) and
[`SECURITY.md`](SECURITY.md) (sign-in and airport scope, Stage 3).

## 1. Architecture

```
OpenSky REST API ──┐                                     ┌─> /api/graph/*, /api/airports/*/operations
OpenWeatherMap ────┤                                     │   (existing services, now with provenance)
                   ▼                                     │
  OpenSkyClient / OpenWeatherClient   (timeouts, retries, rate limits, OAuth2)
                   ▼                                     │
  OpenSkyResponseParser               (validation: malformed rows are counted, not stored)
                   ▼                                     │
  OpenSkyNormalizer                   (callsign mapping, airport association, reject reasons)
                   ▼                                     │
  LiveStateStore / FlightLegStore / WeatherService ──> PostgreSQL ──┤
                   ▲                                     │          ├─> DisruptionMonitorService
  IngestionScheduler / BackgroundJobs (scheduler threads)│          │     (detects, never acts)
                                                         │          └─> /api/system/status,
                                                         │              /api/airports/{iata}/live-aircraft|weather
                                                         └─> React dashboard (polling)
```

* Everything runs inside the existing Spring Boot backend on its scheduler
  thread pool (`spring.task.scheduling.pool.size=4`). No request thread ever
  waits for OpenSky or OpenWeatherMap.
* No new infrastructure. Kafka, Redis or a separate worker were not needed:
  the feeds are low volume (one bounding-box request every few minutes, a few
  dozen flight queries a day), PostgreSQL holds the state, and the in-process
  scheduler gives retries and backoff. Revisit only if several backend
  instances must share the ingestion work (then a leader lock is needed, see
  limitations).
* Migration `V4__live_data_and_monitoring.sql` adds the provenance columns and
  four tables (`live_aircraft_states`, `weather_reports`, `ingestion_runs`,
  `ingestion_checkpoints`). It only adds columns, tables, indexes and
  constraints; no existing row is deleted. The one relaxation is
  `flight_schedules.aircraft_type`, which becomes nullable because OpenSky does
  not report aircraft types.

## 2. Data modes

`AEROSYNC_DATA_MODE` selects what the application serves:

| Mode | Synthetic generator | OpenSky ingestion | Served records |
|------|---------------------|-------------------|----------------|
| `SYNTHETIC` (default) | runs (seeders, `generate-schedule`) | off | synthetic + manual |
| `HYBRID` | runs | on | synthetic + imported + manual |
| `LIVE` | off (seeders skipped, `POST /api/flights/generate-schedule` answers 409) | on | imported + manual |

* The demo works with no external API at all: `SYNTHETIC` needs no key.
* Every record carries its provenance. Flights: `data_origin`
  (`SYNTHETIC`, `IMPORTED`, `MANUAL`), `data_source` (`SYNTHETIC_GENERATOR`,
  `MANUAL_ENTRY`, `EXTERNAL_IMPORT`, `OPENSKY`), `schedule_basis` (`PLANNED`
  or `OBSERVED`), `source_record_id`, `callsign`, `aircraft_icao24`,
  `ingested_at`, `source_updated_at`. Live aircraft states and weather
  reports store their `data_source` and the time the source reported.
  Disruptions store `data_origin` and `detection_source`.
* API responses expose it: graph flight nodes and operations rows have
  `data_origin` and `provenance`; graph responses have `data_mode` and
  `data_sources`; `/api/system/status` reports the mode.
* Synthetic data is never presented as live: the dashboard labels every
  imported flight with its source, shows the mode in the status bar, and
  never fills a missing live value with a generated one.
* Switching mode does not delete anything; it changes what is generated,
  fetched and served.

## 3. OpenSky Network integration

Documentation: <https://openskynetwork.github.io/opensky-api/rest.html>.

### 3.1 Access, credits and rate limits

| Item | What AeroSync does |
|------|--------------------|
| Authentication | OAuth2 client credentials only (OpenSky no longer accepts username/password). `OPENSKY_CLIENT_ID` and `OPENSKY_CLIENT_SECRET` from the environment; the token (about 30 min) is cached and renewed shortly before it expires, and once more on HTTP 401. Without credentials the client runs anonymously. |
| Credits | OpenSky's documented daily credits per endpoint family: 400 anonymous, 4000 for a standard account (`OPENSKY_DAILY_CREDITS` overrides). Ingestion plans with 90 % of them. |
| `/states/all` | One request per poll for one bounding box around all network airports (±0.75°). The box costs 1–4 credits by area; the poll interval is raised automatically so the day's polls fit the budget (e.g. a 2-credit box and an anonymous client → at least every 480 s). |
| `/flights/departure` | Costs up to 30 credits per query (one UTC day each). A per-UTC-day call budget (`credits × 0.9 / 30`: 12 anonymous, 120 with an account) is counted from the recorded runs, so it also holds across restarts; the cycle also stops at `OPENSKY_FLIGHT_LEGS_MAX_CALLS`. |
| HTTP 429 | Never retried. The feed waits the `X-Rate-Limit-Retry-After-Seconds` OpenSky returns (status `RATE_LIMITED`). `X-Rate-Limit-Remaining` is recorded with every run. |
| Timeouts, 5xx | Retried with exponential backoff (`OPENSKY_MAX_ATTEMPTS`, default 3); a failed cycle backs off 60 s, doubling to 30 min. |
| Other 4xx | Not retried; recorded as a failed run. |
| Secrets in logs | The client secret and token are never logged; error messages pass through `LogRedaction`. |

### 3.2 Fields used, not available, and derived

**State vectors** (`/states/all`, one row per aircraft in `live_aircraft_states`, updated in place):

| OpenSky field | Use |
|---------------|-----|
| `icao24` | key of the row (one row per aircraft, so repeated polls never duplicate) |
| `callsign` | stored trimmed; mapped to a flight number when possible (below) |
| `origin_country` | stored |
| `time_position`, `last_contact` | stored; `last_contact` decides staleness (states older than `OPENSKY_STATES_MAX_AGE_SECONDS`, default 300 s, are not stored) |
| `longitude`, `latitude` | stored; states without a position are rejected (`NO_POSITION`) |
| `baro_altitude`, `geo_altitude`, `velocity`, `true_track`, `vertical_rate`, `on_ground` | stored as reported (metres, m/s, degrees) |
| `squawk`, `position_source` | stored |
| `sensors`, `spi`, `category` | not used |

**Flights** (`/flights/departure`, stored as flight legs in `flight_schedules` + `flight_instances`):

| OpenSky field | Use |
|---------------|-----|
| `icao24` | `aircraft_icao24`; with `firstSeen` it forms `source_record_id` (`icao24:firstSeen`), unique per source |
| `firstSeen`, `lastSeen` | stored as `actual_departure` / `actual_arrival` and as the schedule times of the leg, marked `schedule_basis = OBSERVED` |
| `estDepartureAirport`, `estArrivalAirport` | mapped from ICAO to the network airports (`airports.icao_code`); legs to or from airports outside the network are skipped |
| `callsign` | mapped to the flight number |
| distance and candidate-count fields | not used |

**Not available from OpenSky** (and therefore never shown as known for imported data):
scheduled departure/arrival times (timetables), delays, flight status
(scheduled/boarding/cancelled), aircraft type, registration, gates, crew,
passenger numbers, and the origin/destination of an aircraft in a state
vector. Imported legs therefore have status `ARRIVED`, delay 0, no aircraft
type, and the dashboard shows their delay as "n/a".

**Derived or estimated by AeroSync** (labelled as such):

* *Flight number from callsign*: only callsigns of the form `<3-letter ICAO
  airline><number>` with a known airline (AIC→AI, IGO→6E, SEJ→SG, AXB→IX,
  AKJ→QP, VTI→UK, IAD→I5, LLR→9I, SDG→S5; more via `OPENSKY_AIRLINE_CODES`).
  ATC-style callsigns such as `AIC5BH` are kept as callsigns without a flight
  number. Legs without a mappable callsign are skipped (`UNMAPPED_CALLSIGN`).
* *Nearest airport* of a live aircraft: great-circle distance to the network
  airports, within `OPENSKY_VICINITY_KM` (default 60 km).
* *Phase* (on ground, climbing, descending, level) from `on_ground` and
  `vertical_rate` (±1.5 m/s).
* *Departure and arrival airport* of a leg are OpenSky's own estimates
  (`estDepartureAirport`, `estArrivalAirport`).

### 3.3 Duplicates and idempotency

* Live states: one row per `icao24`; a poll updates it only when the
  reported `last_contact` is newer. Rows older than 6 h are pruned.
* Flight legs: unique `(data_source, source_record_id)`. A record seen again
  updates the leg only when something changed, otherwise it is counted as
  unchanged. A leg with the same flight number, date and origin from another
  source (e.g. the synthetic generator) is never overwritten or relabelled
  (`LEG_EXISTS_FROM_OTHER_SOURCE`); live observations are never attached to
  synthetic flights.
* Checkpoints (`ingestion_checkpoints`, per airport and UTC day): `COMPLETE`
  and `EMPTY` periods are not fetched again. Yesterday is `PENDING` while
  OpenSky's nightly batch has not delivered it and is retried (at most 12
  times); older empty days are closed.
* A storage failure rolls back that airport/day only; it stays `PENDING`
  and is retried, everything stored before stays stored.

## 4. Ingestion workflow

1. `IngestionScheduler` ticks every 15 s (live states) and every 5 min
   (flight legs); a feed runs only when due (its interval, backoff or
   rate-limit wait has passed) and only in `LIVE` or `HYBRID` mode with
   `AEROSYNC_OPENSKY_ENABLED=true`. The first ticks come 20 s and 45 s after
   start-up.
2. The client fetches (timeouts, retries, token handling).
3. The parser validates the JSON; malformed entries are counted
   (`records_skipped`, reason `MALFORMED`) and dropped.
4. The normalizer validates values and maps them (reject reasons are
   counted per run, e.g. `UNMAPPED_CALLSIGN=11`).
5. The stores write with upsert semantics in short transactions.
6. Every attempt is recorded in `ingestion_runs` (status `SUCCESS`,
   `PARTIAL`, `FAILED`, `RATE_LIMITED`, `SKIPPED`, counts, API calls, remaining
   credits, error) and logged as one line:
   `event=ingestion_run source=OPENSKY feed=LIVE_STATES status=SUCCESS fetched=212 inserted=9 updated=171 ...`.
   Runs older than 14 days are pruned.
7. Existing services read the stored records like any other: the graph,
   the operations boards and the monitor.

Feed state (`LIVE`, `STALE`, `FAILING`, `RATE_LIMITED`, `WAITING`, `DISABLED`,
`UNCONFIGURED`) is computed by `SystemStatusService`; a feed is `STALE` when
its last success is older than two intervals plus a minute.

## 5. Weather

* By coordinates for every network airport (`/weather` and `/forecast` of
  OpenWeatherMap with `WEATHER_API_KEY`), stored in `weather_reports` with
  `kind` = `OBSERVATION` or `FORECAST`, the time the source reports
  (`valid_at`) and the fetch time.
* An observation is reused for 30 min (`AEROSYNC_WEATHER_REFRESH_MINUTES`);
  older than 120 min (`AEROSYNC_WEATHER_MAX_AGE_MINUTES`) it is shown as stale
  and not used for prediction or detection.
* Forecasts are display-only. They are never used as an observed condition,
  not by the delay model and not by the monitor.
* When the key is missing or the API fails, nothing is invented: the airport
  shows "No current observation", the delay model keeps its earlier default
  score of 1, and a failure pauses weather calls for 5 min.

## 6. Disruption monitoring

`DisruptionMonitorService.runCycle()` runs every 120 s
(`AEROSYNC_MONITORING_ENABLED`, `AEROSYNC_MONITORING_INTERVAL_MS`). It
**detects only**: it stores disruptions as `DETECTED` and never changes a
flight, never proposes, approves or executes. The existing
proposal → approval → execution workflow is unchanged and still requires an
`OPERATIONS_CONTROLLER` of the departure airport.

| Rule | Data | Type | Severity | Confidence |
|------|------|------|----------|------------|
| `WEATHER_OBSERVED` | current **observation** at the origin with score ≥ 2 | `WEATHER_DELAY` | 2 → MEDIUM, 3 → HIGH | 0.65 / 0.85, −0.10 if older than 60 min |
| `CREW_DUTY_LIMIT` | recorded crew duty hours ≥ 11 (limit 12) | `CREW_TIMEOUT` | MEDIUM, HIGH at the limit | 0.80 |
| `ML_DELAY_RISK` | delay-model probability ≥ 0.80 | `FLIGHT_DELAY` | MEDIUM, HIGH from 0.90 | the probability |
| `NETWORK_FLIGHT_DELAY` | recorded delay ≥ 15 min on a network flight | `FLIGHT_DELAY` | by delay (<30 LOW, <90 MEDIUM, <180 HIGH, else CRITICAL) | 1.0 |
| `TURNAROUND_CONFLICT` / `CREW_ROTATION_CONFLICT` | the delay of the inbound flight exceeds the slack of an aircraft or crew rotation | `TURNAROUND_CONFLICT` | by propagated delay | 0.90 × 0.85 per hop |
| `MISSED_CONNECTION` | a passenger connection becomes shorter than its minimum | `MISSED_CONNECTION` | by shortfall | as above × 0.9 |
| `CASCADING_DELAY` | the same, two or more hops downstream | `TURNAROUND_CONFLICT` | by propagated delay | as above |
| `AIRCRAFT_UNAVAILABLE` | the assigned aircraft is in maintenance or grounded | `AIRCRAFT_UNAVAILABLE` | HIGH | 0.95 |

The first three rules watch the flights of the resolution engine (table
`flights`, departing within 3 h). The network rules work on the operational
graph (`flight_instances`, dependencies) and produce **advisory**
disruptions: the resolution engine only changes `flights`, so propose/approve
on them answers 409 `not_available` and the dashboard shows them without
actions. Delay minutes of the first three rules are planning assumptions
(45/90/60/30 min) and are labelled so in the description.

Rules against duplicates and noise: one open disruption per detection key
(partial unique index on `detection_key` for open statuses); no new one when
the flight already has an open disruption from any source; a cleared key is
not reopened for 6 h; at most 25 new disruptions per cycle (most severe
first); a monitor disruption still `DETECTED` and no longer detected for
10 min is closed with a resolution note. Each disruption stores severity,
confidence, `detection_source` (`MONITOR`, `MANUAL`, `SIMULATION`, `SYSTEM`),
`detection_rule`, detection and last-observed time, the airports and the
flight, and the decision trail (`proposed_at`, `approved_at/by`,
`rejected_at/by`, `rejection_count`).

## 7. API additions

| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| `GET` | `/api/system/status` | signed in | data mode, database and ML health, every feed with last success/attempt, interval, last run counts, monitor state |
| `GET` | `/api/airports/{iata}/live-aircraft` | the airport must be in the user's scope (400 invalid code, 404 unknown, 403 outside scope) | aircraft within the vicinity of the airport, with age and staleness |
| `GET` | `/api/airports/{iata}/weather` | same | latest observation (with age and status) and the forecast steps, separately |
| `GET` | `/api/health/ready` | public | 200 `UP` when the database answers, 503 `DOWN` otherwise (no details) |

Existing responses gained fields only (no field was removed or renamed):
graph nodes (`provenance`, `disruption`), graph responses (`data_mode`,
`data_sources`), operations rows (`provenance`), operations response
(`data_mode`), disruptions (severity, confidence, detection and decision
fields, `actionable`).

## 8. Configuration

All values come from the environment (`.env` with Docker Compose; see
`.env.example`). Secrets have no default.

| Variable | Default | Purpose |
|----------|---------|---------|
| `AEROSYNC_DATA_MODE` | `SYNTHETIC` | `SYNTHETIC`, `LIVE` or `HYBRID` |
| `OPENSKY_CLIENT_ID`, `OPENSKY_CLIENT_SECRET` | empty | OpenSky API client (secret) |
| `AEROSYNC_OPENSKY_ENABLED` | `true` | master switch for OpenSky ingestion |
| `OPENSKY_STATES_POLL_SECONDS` | `300` | wanted live-state interval (raised to fit the credits) |
| `OPENSKY_BOUNDING_BOX` | empty | `minLat,minLon,maxLat,maxLon`; empty = around the network |
| `OPENSKY_STATES_MAX_AGE_SECONDS` | `300` | older states are not stored |
| `OPENSKY_VICINITY_KM` | `60` | radius for "aircraft near an airport" |
| `OPENSKY_FLIGHT_LEGS_ENABLED` | `true` | import of past flights |
| `OPENSKY_FLIGHT_LEGS_CHECK_MINUTES` | `60` | how often missing days are looked for |
| `OPENSKY_FLIGHT_LEGS_LOOKBACK_DAYS` | `2` | past UTC days imported (1–7) |
| `OPENSKY_FLIGHT_LEGS_MAX_CALLS` | `20` | API calls per cycle |
| `OPENSKY_DAILY_CREDITS` | `0` | credits per day to plan with (0 = documented default) |
| `OPENSKY_AIRLINE_CODES` | empty | extra `ICAO=IATA` airline prefixes |
| `OPENSKY_CONNECT_TIMEOUT_SECONDS`, `OPENSKY_REQUEST_TIMEOUT_SECONDS` | `5`, `15` | HTTP timeouts |
| `OPENSKY_MAX_ATTEMPTS`, `OPENSKY_RETRY_BACKOFF_MS`, `OPENSKY_RETRY_MAX_BACKOFF_MS` | `3`, `1000`, `15000` | per-request retries |
| `OPENSKY_FAILURE_BACKOFF_SECONDS`, `OPENSKY_FAILURE_MAX_BACKOFF_SECONDS` | `60`, `1800` | backoff after a failed cycle |
| `WEATHER_API_KEY` | empty | OpenWeatherMap (secret) |
| `AEROSYNC_WEATHER_REFRESH_MINUTES`, `AEROSYNC_WEATHER_MAX_AGE_MINUTES` | `30`, `120` | observation reuse and staleness |
| `AEROSYNC_WEATHER_FORECAST_ENABLED` | `true` | fetch forecasts (display only) |
| `AEROSYNC_MONITORING_ENABLED`, `AEROSYNC_MONITORING_INTERVAL_MS` | `true`, `120000` | disruption monitor |
| `AEROSYNC_MONITORING_LOOKAHEAD_MINUTES` | `180` | flights watched by the first three rules |
| `AEROSYNC_MONITORING_WEATHER_MIN_SCORE`, `AEROSYNC_MONITORING_DELAY_RISK_THRESHOLD`, `AEROSYNC_MONITORING_CREW_DUTY_ALERT_HOURS` | `2`, `0.80`, `11.0` | rule thresholds |
| `ML_SERVICE_TIMEOUT_SECONDS` | `10` | delay-model call timeout; the fallback prediction is used after it |
| `AEROSYNC_LOG_LEVEL` | `INFO` | log level of `com.aerosync` |
| `JPA_SHOW_SQL` | `false` | SQL logging (development only) |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | unset | `ecs` for JSON log lines |

## 9. Observability

* One key=value log line per ingestion run, weather refresh failure and
  monitor cycle (`event=...`); no request bodies, tokens or keys. JSON output
  with `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`.
* `ingestion_runs` keeps 14 days of run history; `/api/system/status` and
  the dashboard (status bar, Settings → Data sources) show it.
* `/api/health` (liveness) and `/api/health/ready` (database) are public and
  carry no details, for load balancers or a container health check (the
  backend's health check in `docker-compose.yml` is still disabled, as before
  Stage 4; the ML and PostgreSQL containers have theirs).
* Graceful shutdown: `server.shutdown=graceful`, the scheduler waits up to
  20 s for a running job.

## 10. Limitations

* **No timetable, delay or status from OpenSky.** Delays of live flights
  cannot be computed from OpenSky alone. In `LIVE` mode there are therefore
  no future flights and the delay-based rules have nothing to watch;
  `HYBRID` is the useful demonstration mode.
* **Flight legs are at least a day old** (OpenSky's nightly batch), and only
  legs between network airports with an airline callsign are imported.
* **Network-flight rules need recorded delays or aircraft status.** Nothing
  in Stage 4 sets them for synthetic flights automatically, so these rules
  fire only when a delay or an unavailable aircraft is recorded.
* **Two flight models stay separate.** The resolution engine's `flights` and
  the operational network's `flight_instances` are not linked; network
  disruptions are advisory.
* **Anonymous OpenSky access is very limited**: about one state poll every
  8 minutes for the default box and 12 flight queries a day, so most past
  days stay incomplete without an account.
* **Feed state is per process.** Backoff and rate-limit waits reset on
  restart (the daily flight budget does not: it is read from
  `ingestion_runs`). Running several backend instances would make each poll
  OpenSky; a shared lock would be needed first.
* **Crew and gate pools have no source column**; they are synthetic in every
  mode.
* **The ML model knows nine airports.** Flights from the six other network
  airports are encoded as the default airport by the ML service (unchanged
  since Stage 1, see `aerosync-ml/tests`).
* **Forecasts are display-only**; the delay model uses observations only.
* The Streamlit dashboard (`aerosync-dashboard`) was not changed and does not
  show the Stage 4 information.
