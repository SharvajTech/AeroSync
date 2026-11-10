# AeroSync web dashboard

React + TypeScript dashboard for the Stage 1 operational network APIs: a hybrid
airport + flight graph, airport and flight details, searchable airport and
flight lists and an airport operations board.

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

No credentials are needed or stored: the graph and airport endpoints are
public `GET` endpoints of the backend.

## Scripts

| Command | What it does |
|---------|--------------|
| `npm run dev` | Vite dev server with the `/api` proxy |
| `npm run build` | Type-check (`tsc --noEmit`) and production build into `dist/` |
| `npm run preview` | Serve `dist/` on port 3000 with the same proxy |
| `npm test` | Vitest unit and component tests |
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
| `#/operations?airport=BLR` | Departures/arrivals board and gates of one airport |
| `#/settings` | Read-only connection, data window and data origin information |

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

## Code layout

```
src/
  api/          types mirroring the backend DTOs, fetch client (errors, timeouts), request cache, endpoints
  graph/        layout.ts and model.ts (pure: response -> drawable graph), style.ts, NetworkGraph.tsx (Cytoscape)
  components/   layout (sidebar, top bar), common (cards, states, legend), details panels, graph panel
  pages/        one component per route
  hooks/        resource loading, hash routing, graph scope loading, backend status
  state/        operating date context, selection and scope types
  utils/        formatting and filters
  test/         fixtures used only by tests
```

Each network response is fetched once per date and cached for 60 s;
concurrent requests for the same URL share one request and failed requests
are never cached. The dashboard contains no mock data: everything shown comes
from the backend, and the "Synthetic data" badge reflects the backend's
`synthetic_data` flag.
