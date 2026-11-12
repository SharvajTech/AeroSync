# AeroSync backend

Spring Boot service (Java 17, PostgreSQL, Flyway) behind the dashboard: flights
and disruptions, the resolution engine (proposal → approval → execution), the
operational network graph, sign-in with airport-scoped access, and, from
Stage 4, live data ingestion and disruption monitoring.

| Document | Content |
|----------|---------|
| [`docs/OPERATIONAL_NETWORK.md`](docs/OPERATIONAL_NETWORK.md) | Stage 1: graph model, schema, graph API, synthetic network |
| [`docs/SECURITY.md`](docs/SECURITY.md) | Stage 3: JWT sign-in, roles, airport scope, users |
| [`docs/LIVE_DATA.md`](docs/LIVE_DATA.md) | Stage 4: data modes, OpenSky ingestion, weather, disruption monitoring, status API, configuration, limitations |

## Run

```bash
# PostgreSQL must be running; credentials come from the environment
export DB_HOST=localhost DB_PORT=5432 DB_NAME=aerosync DB_USER=postgres DB_PASSWORD=...
export JWT_SECRET=$(openssl rand -base64 48)
./mvnw spring-boot:run            # http://localhost:8080
```

With Docker Compose from the repository root the backend is on host port
8082 (`docker compose up -d --build`, settings in `.env`; see `.env.example`).
Without further settings it runs in `SYNTHETIC` mode and needs no external
API. For live data set `AEROSYNC_DATA_MODE=HYBRID` (or `LIVE`) and, ideally,
`OPENSKY_CLIENT_ID` / `OPENSKY_CLIENT_SECRET` and `WEATHER_API_KEY`
(details in [`docs/LIVE_DATA.md`](docs/LIVE_DATA.md)).

## Test

```bash
./mvnw -B verify
```

* Unit tests run without a database or network. External APIs are replaced
  by an in-process HTTP stub (`ingestion/StubHttpServer`) or mocks; no test
  calls OpenSky, OpenWeatherMap or Gemini.
* Spring integration tests (`network/*IntegrationTest`) use H2 with the
  `test` profile (`src/test/resources/application-test.properties`).
* `migration/FlywayMigrationPostgresTest` applies all Flyway migrations to a
  real PostgreSQL and checks the Stage 4 constraints. It is skipped unless
  `AEROSYNC_TEST_POSTGRES_URL` is set (optional `AEROSYNC_TEST_POSTGRES_USER`,
  `AEROSYNC_TEST_POSTGRES_PASSWORD`); CI sets it against a throwaway
  container.

| Package | What the tests cover |
|---------|----------------------|
| `ingestion` | OpenSky client (OAuth2 token, timeouts, retries with backoff, 401 renewal, 429 handling, no secret in errors), response parsing, normalization and reject reasons, the pipeline end to end (idempotency, no duplicates, provenance, partial failures, rate limits, credit budgets) |
| `weather` | observation vs forecast, caching, staleness, unavailable source |
| `monitoring` | every detection rule, severity and confidence, deduplication, cooldown, auto-close, "never acts" |
| `datasource` | what each data mode generates, fetches and serves |
| `security`, `service`, `network` | airport scope, JWT, resolution scope, graph and live endpoints per airport, viewer read-only (Stages 3 and 4) |
| `graph` | graph builder, dependency rules and traversal (Stage 1) |
