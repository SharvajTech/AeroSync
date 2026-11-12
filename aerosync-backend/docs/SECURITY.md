# AeroSync authentication and airport-scoped authorization (Stage 3)

AeroSync users sign in with a username and password and then see and change
only the data of the airports assigned to them. The backend enforces this; the
web dashboard and the Streamlit dashboard only reflect it.

## Model

| Table (Flyway V3) | Purpose |
|-------------------|---------|
| `app_users` | One row per person: `username` (lower-case, unique), `display_name`, `password_hash` (BCrypt, 60 characters), `role`, `enabled`, timestamps |
| `user_airports` | Airports a user may operate (`user_id` → `app_users`, `airport_id` → `airports`) |

No existing table is changed. A user can be assigned to one or several airports.

| Role | Can |
|------|-----|
| `OPERATIONS_CONTROLLER` | View the assigned airports' operations and act on them: report disruptions, propose/approve/reject/resolve resolutions, change flight status, run batch jobs for those airports |
| `OPERATIONS_VIEWER` | View only; every action endpoint answers 403 |

## Authentication flow

1. `POST /api/auth/login` with `{"username": "...", "password": "..."}` (public).
   - The password is checked against the BCrypt hash (work factor 12 by
     default, `AEROSYNC_BCRYPT_STRENGTH`). Unknown user, wrong password and
     disabled account all get the same 401 `invalid_credentials`, and take
     about the same time.
   - After 5 failures within 15 minutes for one username and client
     address, attempts get 429 with `Retry-After`
     (`AEROSYNC_LOGIN_MAX_FAILURES`, `AEROSYNC_LOGIN_LOCKOUT_MINUTES`).
   - A user without airports gets 403.
2. The response contains an HS256 JWT (`token`, `token_type`, `expires_at`)
   and the user profile with the authorized airports.
3. Every other request sends `Authorization: Bearer <token>`. `JwtFilter`
   checks the signature, issuer and expiry, then **loads the user from the
   database**:
   - a disabled account (or a removed airport) takes effect on the next
     request, not when the token expires;
   - the airport scope always comes from the database, never from the request
     or from the token's claims.
4. Failures are answered with 401 and a `code` the clients use to show the
   right message: `authentication_required`, `token_invalid`,
   `token_expired`, `account_inactive`.

Public endpoints: `POST /api/auth/login`, `GET /api/health`, and CORS
preflight (`OPTIONS`). Everything else, including unknown paths such as `/`,
answers 401 without a valid token. This is why an anonymous request to
`http://localhost:8082/` returns 401 rather than 404.

Token claims: `sub` (user id), `username`, `role`, `airports`, `iss`
(`aerosync-backend`), `iat`, `exp`, `jti`. Default lifetime 8 hours
(`JWT_EXPIRATION`, milliseconds).

**Signing secret.** Set `JWT_SECRET` (at least 32 characters, e.g.
`openssl rand -base64 48`) in `.env`. There is no built-in default:
- without a secret, the backend signs with a random key per start and logs a
  warning (sessions end on restart);
- the example values that were once published in this repository are treated
  as unset;
- a secret shorter than 32 bytes stops the application at start-up.

**Logout** is client-side: the dashboards delete the token, and the backend
keeps no session. A token remains valid until it expires unless the account
is disabled (see Limitations).

## Authorization rules

`AirportScope` (built from the signed-in user) applies the same rules in
every service:

| Data | Visible when | Changeable when |
|------|--------------|-----------------|
| Flight (operational network or legacy `flights`) | its origin **or** destination is one of the user's airports | its **origin** is one of the user's airports (the departure station owns gate and crew) and the role is `OPERATIONS_CONTROLLER` |
| Airport graph, operations board, gates, weather | the airport is one of the user's airports | — |
| Disruption | its flight is visible | its flight is changeable |
| Dependency (graph edge) | **both** flights are visible | — |

Consequences:
- **Network graph.** `GET /api/graph/network` only contains the user's
  airports, their flights and the other endpoint airport of each flight.
  The optional `airports=BOM,DEL` parameter can only *narrow* this: any
  code outside the user's airports is 403, and a malformed one is 400.
- **Traversals.** Flight dependencies and affected-flights traversals only
  follow dependencies whose flights are both visible. Flights of other
  airports never appear, not even as counts or as parents in a path.
- **Ids.** Airports, flights and disruptions of other airports answer 403,
  even when the id or IATA code is edited by hand. Unknown ids answer 404.
- **Resolutions.** Propose, resolve, approve and reject check the role and
  the disrupted flight's departure airport first.
  - The cascade step only disrupts and re-resolves connecting flights that
    depart from the user's airports; the response says when connections at
    another airport were left alone.
- **Gates.** A flight only gets, frees or excludes gates of its departure
  airport or of the shared pool (pre-Stage-1 gates without an airport). This
  holds for algorithmic proposals, hand-edited approval bodies and rejection
  texts.
- **Crew.** Crew records have no airport, so they remain a network-wide pool
  (see Limitations).
- **Batch jobs.**
  - `predict-all` scores only flights departing from the user's airports.
  - `simulate` picks only a flight departing from the user's airports.
  - `generate-schedule` replaces every legacy flight, so it needs access to
    every departure airport that has flights.

Errors use the existing format: `{"error": "...", "status": 403, "code": "forbidden"}`.

## Creating users

**Development.** Set these in `.env`:

```
AEROSYNC_DEMO_USERS_ENABLED=true
AEROSYNC_DEMO_PASSWORD=<at least 8 characters>
```

On start-up `DemoUserSeeder` then creates the accounts listed in
`AEROSYNC_DEMO_USERS`. All of them get that password, stored as a BCrypt hash.
- Default accounts: `bom.ops` (BOM), `del.ops` (DEL),
  `network.ops` (BOM, DEL, BLR, HYD), all `OPERATIONS_CONTROLLER`, and
  `bom.viewer` (BOM, `OPERATIONS_VIEWER`).
- Existing accounts are never changed.
- Without a password nothing is created.

**Production.** Keep the seeder disabled and insert users with a BCrypt hash,
for example:

```bash
HASH=$(htpasswd -bnBC 12 "" 'the-password' | tr -d ':\n')
psql -c "INSERT INTO app_users (user_id, username, display_name, password_hash, role)
         VALUES (gen_random_uuid(), 'jane.doe', 'Jane Doe', '$HASH', 'OPERATIONS_CONTROLLER')"
psql -c "INSERT INTO user_airports (user_id, airport_id)
         SELECT u.user_id, a.airport_id FROM app_users u, airports a
         WHERE u.username = 'jane.doe' AND a.iata_code IN ('BOM')"
```

Disable an account with `UPDATE app_users SET enabled = false WHERE username = '...'`.
The change applies to the user's next request.

## Clients

- **Web dashboard (`aerosync-web`).**
  - Login page, then protected routes.
  - The token is kept in `sessionStorage`: it survives a reload of the tab,
    is not shared with other tabs, and is gone when the tab closes. nginx
    sends a strict Content-Security-Policy so that only the app's own scripts
    run.
  - Every request carries the token. A 401 signs the user out with an
    explanation; a 403 is shown as "Not authorized".
  - The airport selector only offers the user's airports, and switching
    airports reloads all data and graph state.
- **Streamlit dashboard.** A sidebar sign-in form; the token is kept in the
  Streamlit session only.

## Tests

| Test | Covers |
|------|--------|
| `security/AirportScopeTest` | visibility and operate rules, narrowing can never widen, input echo |
| `security/JwtUtilTest` | issue/verify, expiry, tampered payload, foreign key, unsigned, wrong issuer, missing subject/expiry, weak and missing secrets |
| `security/JwtFilterGraphAccessTest` | public paths, missing/invalid/expired tokens, disabled users, principal propagation |
| `security/LoginAttemptLimiterTest`, `security/LogRedactionTest` | lockout window; credentials removed from log lines |
| `service/ResolutionGateScopeTest`, `service/CascadeServiceScopeTest` | gates and cascades never touch another airport |
| `service/DemoUserSeederTest` | demo account definitions |
| `network/AirportScopedAccessIntegrationTest` | HTTP end to end with BOM, DEL, BOM+DEL and read-only users: login, invalid credentials, lockout, missing/invalid/expired tokens, revocation, scoped graph and traversal, `airports` manipulation, cross-airport flights, disruptions and resolution actions (403 and database unchanged), multi-airport cascades |

## Stage 4 additions

- `GET /api/airports/{iata}/live-aircraft` and `GET /api/airports/{iata}/weather`
  check the airport against the token's scope on the backend (400 invalid
  code, 404 unknown airport, 403 outside the scope); the frontend's airport
  choice is never trusted.
- `GET /api/system/status` needs a token and holds no operational data of any
  airport. `GET /api/health/ready` is public and returns only `UP` or `DOWN`.
- Disruptions found by the monitor follow the same rules as reported ones:
  visible when their flight departs from or arrives at one of the user's
  airports, actionable only for an `OPERATIONS_CONTROLLER` of the departure
  airport. The monitor itself never proposes, approves or executes.
  Advisory (network-flight) disruptions answer 409 `not_available` to
  propose/approve, after the 403 checks.
- New secrets (`OPENSKY_CLIENT_ID`, `OPENSKY_CLIENT_SECRET`,
  `WEATHER_API_KEY`) come only from the environment, have no default and
  are never logged; OpenSky error messages pass through `LogRedaction`.
- CI (`.github/workflows/ci.yml`) needs no secret: external APIs are mocked.
  Should a later job need one, store it as a GitHub Actions secret.
- `aerosync-ml/data/opensky_fetcher.py` contained an OpenSky username and
  password in earlier commits. They were removed; the password must be
  treated as exposed (it remains in the Git history) and changed on the
  OpenSky account.

| Stage 4 test | Covers |
|--------------|--------|
| `network/LiveDataAndMonitoringIntegrationTest` | status needs a token, readiness reveals nothing, live aircraft and weather of another airport (403), detected disruptions through propose/approve with viewer and other-airport controller refused, advisory disruptions scoped and not actionable |
| `network/LiveIngestionIntegrationTest` | imported aircraft and legs are served only to their airports |

## Limitations

- Tokens cannot be revoked one by one: logout deletes the token in the
  browser only. Disabling the account revokes all of a user's tokens at once.
  The 8-hour default lifetime limits the exposure.
- The login attempt limit is in memory and per backend instance. Behind the
  nginx proxy all clients share one address, so it effectively applies per
  username; this also lets someone lock an account for 15 minutes on purpose.
- Crew have no airport attribute, so crew proposals and swaps use the
  network-wide crew pool. Associating crew with a base airport needs a data
  decision (which airport each existing crew member belongs to).
- The gate airport check maps a legacy flight's origin code to the
  deterministic reference airport id. A gate of an airport created outside
  the reference list is therefore never offered to legacy flights (fail-closed).
- There is no user-management API or UI; accounts are provisioned as shown
  above.
