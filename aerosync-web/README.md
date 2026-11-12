# AeroSync web dashboard

React + TypeScript dashboard for the Stage 1 operational network APIs: a hybrid
airport + flight graph, airport and flight details, searchable airport and
flight lists, an airport operations board and (Stage 3) sign-in with
airport-scoped access, disruptions and resolution approval.

The Streamlit app in `aerosync-dashboard/` is unchanged; this is a separate
front end on port 3000 (the port the backend's CORS configuration already allows).

## Run locally

Requires Node.js 20.19+ and the backend on `http://localhost:8080`.

```bash
cd aerosync-web
npm install
npm run dev            # http://localhost:3000
```

The dev server proxies `/api` to `BACKEND_URL` (default `http://localhost:8080`):

```bash
BACKEND_URL=http://localhost:8081 npm run dev
```

| Variable | Used by | Purpose |
|----------|---------|---------|
| `BACKEND_URL` | `vite.config.ts` (dev server and `vite preview`) | Where `/api` is proxied. |
| `VITE_API_BASE_URL` | browser bundle, build time | Optional. Call the backend directly (e.g. `http://localhost:8080`) instead of through the proxy. Leave empty for same-origin `/api`. |
| `BACKEND_UPSTREAM` | Docker image (nginx) | Where nginx proxies `/api` (default `http://backend:8080`). |

## Sign-in and airport access

The dashboard has no data of its own: every request goes to the backend with
the user's token, and the backend decides what the user may see (see
[`aerosync-backend/docs/SECURITY.md`](../aerosync-backend/docs/SECURITY.md)).
For local accounts, enable the backend's demo users (root README, "Sign in").

- **Login page.**
  - Username and password are checked in the form, then by
    `POST /api/auth/login`.
  - Wrong credentials, a locked account (429) or an unreachable backend each
    get their own message. Passwords are never stored.
- **Session.**
  - The token, its expiry and the user profile are kept in `sessionStorage`
    (`aerosync.session`): a reload keeps you signed in, closing the tab signs
    you out, and other tabs need their own sign-in.
  - On start the session is checked with `GET /api/auth/me`.
  - The session ends at the token's expiry, on **Sign out** (profile menu) or
    on any 401. The login page then says why (expired, signed out, account
    disabled).
- **Protected routes.** No page, not even its layout, renders without a
  session.
- **Airport selector** (top bar).
  - It only lists the airports assigned to the user and is shown as a plain
    label when there is only one. The choice is remembered per user.
  - Switching airports clears the previous airport's data and graph state.
    Network, flights, disruptions and operations are loaded for the selected
    airport only.
- **403.** A deep link or a hand-edited `?airport=` / flight id for another
  airport shows "Not authorized"; the other endpoint airport of a flight only
  shows its name, not its operations.
- **Disruptions panel** (`#/operations`): active disruptions of the airport.
  Operations controllers can propose, approve (applies gate and crew changes)
  or reject a resolution and report a disruption; viewers see a read-only
  notice. Flights departing elsewhere are marked as handled by their departure
  airport.

The Docker image's nginx sends a Content-Security-Policy that only allows the
app's own scripts and same-origin connections (`connect-src 'self'`). If you
build with `VITE_API_BASE_URL` pointing at another origin, add that origin to
`connect-src` in `nginx/default.conf.template`.

## Scripts

| Command | What it does |
|---------|--------------|
| `npm run dev` | Vite dev server with the `/api` proxy |
| `npm run build` | Type-check (`tsc --noEmit`) and production build into `dist/` |
| `npm run preview` | Serve `dist/` on port 3000 with the same proxy |
| `npm test` | Vitest unit and component tests (no backend needed; API calls are stubbed) |
| `npm run typecheck` | TypeScript only |

## Docker

`docker compose up web` builds the app and serves it with nginx on
`http://localhost:3000`; nginx proxies `/api` to the `backend` service.
After the first `npm install`, commit `package-lock.json` so the image build
uses `npm ci`.

## Pages

| Route | Content |
|-------|---------|
| `#/dashboard` | Metric cards (airports, flights, route edges, dependency edges for the date) and the network graph with the details panel |
| `#/network` | Full-size graph. Deep links: `?airport=BLR`, `?flight=<flight_instance_id>&depth=2`, `?flight=<id>&view=affected&horizon=24`, `?select=BOM` |
| `#/airports` | Searchable airport list with daily movements and dependency counts |
| `#/flights` | Flight instances of the date; filters for airport, status, delay status and airline |
| `#/operations?airport=BLR` | Departures/arrivals board, gates and disruptions of one of your airports |
| `#/settings` | Signed-in account, connection, data window and data origin information |

The operating date (top bar) applies to every page. It starts at the backend's
`service_date`; the top bar shows the backend's `data_window`, and a date
without flights shows an empty state with a link to the first date that has data.

## Graph

- **Airport** nodes (blue) at schematic geographic positions; **flight** nodes
  (green, amber when delayed, red when cancelled or diverted) on the line
  between their origin and destination.
- **Route** edges: `DEPARTS_FROM` airport → flight and `ARRIVES_AT`
  flight → airport (solid blue arrows).
- **Dependency** edges between flights, dashed in orange tones with a
  different dash per type: `AIRCRAFT_ROTATION`, `PASSENGER_CONNECTION`,
  `CREW_ROTATION`, `OPERATIONAL_CONSTRAINT`. Each type can be toggled.
- Selecting a node opens its details and highlights its direct neighbours.
- Scopes come from the backend views: national network, airport
  (`/api/graph/airports/{iata}`), flight dependencies
  (`/api/graph/flights/{id}?depth=1..3`) and affected flights
  (`/api/graph/flights/{id}/affected`, 6/12/24 h horizon). Upstream flights
  are drawn grey, downstream flights orange.
- The national view shows one two-hour departure window at a time (several
  hundred flights a day would be unreadable at once); "All day (routes)"
  shows one line per city pair instead.

## Live data and monitoring (Stage 4)

Everything below comes from the backend; the dashboard never simulates a
live value. See `aerosync-backend/docs/LIVE_DATA.md` for the sources.

- **Status bar** (under the top bar, every page, from `GET /api/system/status`
  every 30 s): data mode (Synthetic / Hybrid / Live data), each external feed
  (live aircraft, flight records, weather) with its state and last successful
  ingestion, the disruption monitor, database or prediction service outages,
  and when the status was refreshed. When a refresh fails, the last values
  stay and "Not refreshed since …" is shown.
- **Airport live panel** (Dashboard and Operations, for the selected airport
  only): the latest weather **observation** with its time and source, the
  forecast in a separate block labelled as a prediction, and aircraft within
  60 km with phase, altitude, speed, distance and age (60 s refresh). A
  missing source is shown as unavailable or off, never filled in. ATC
  callsigns are shown without an invented flight number.
- **Graph**: refreshed in the background every 2 min (the last good data
  stays on screen if a refresh fails). Flights from an external source are
  drawn as dark-outlined diamonds, flights with an open disruption get a red
  ring; the footer shows the number of active disruptions, the data sources
  and the time of the last refresh. Flight details show the source, callsign,
  transponder and import time of imported flights, and the open disruption.
- **Operations → Disruptions**: severity, confidence, delay, who or what
  detected it (monitor rule, controller, simulation) and when, a "New" badge
  for the last 15 minutes, auto-refresh every 60 s. Advisory disruptions of
  network flights have no actions. Only an operations controller of the
  departure airport sees propose/approve/reject; the backend checks again.
- **Flights**: source badge per flight; imported flights show "n/a" for the
  delay because the source has no timetable.
- **Settings → Data sources**: mode, every feed with interval, last run counts
  and skip reasons, the monitor, database and prediction service.

Polling pauses while the browser tab is hidden. Live endpoints are never
cached and always ask for one airport of the user; switching airports drops
the previous airport's data.

## Code layout

```
src/
  api/          types mirroring the backend DTOs, fetch client (bearer token, errors, timeouts), request cache, endpoints
  auth/         session storage, AuthStore (login, restore, logout, expiry), credential validation, AuthProvider
  graph/        layout.ts and model.ts (pure: response -> drawable graph), style.ts, NetworkGraph.tsx (Cytoscape)
  components/   layout (sidebar, top bar), common (cards, states, legend), details panels, graph panel
  pages/        one component per route
  hooks/        resource loading, polling (Poller), hash routing, graph scope loading, backend status
  state/        signed-in airport selection, operating date context (with background refresh), system status, selection and scope types
  components/status, components/live   Stage 4 status bar and airport live panel
  utils/        formatting, filters, live-data labels (liveData.ts)
  test/         fixtures used only by tests
```

Each network response is fetched once per airport and date and cached for 60 s
(the cache is emptied on sign-in and sign-out);
concurrent requests for the same URL share one request and failed requests
are never cached. The dashboard contains no mock data: everything shown comes
from the backend, and the "Synthetic data" badge reflects the backend's
`synthetic_data` flag.
