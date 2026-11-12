# AeroSync

[![CI](https://github.com/SharvajTech/AeroSync/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/SharvajTech/AeroSync/actions/workflows/ci.yml)

**Intelligent flight delay prediction and autonomous disruption resolution for airport operations.**

AeroSync is a full-stack aviation operations platform that combines machine learning, rule-based constraint solving, and generative AI to predict flight delays, simulate cascade effects across a flight network, and resolve operational disruptions in seconds — with optional human-in-the-loop approval.

Built as a portfolio project demonstrating end-to-end system design across backend engineering, data science, and applied AI.

---

## Table of Contents

- [Overview](#overview)
- [Problem Statement](#problem-statement)
- [Key Features](#key-features)
- [System Architecture](#system-architecture)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Machine Learning Pipeline](#machine-learning-pipeline)
- [Disruption Resolution Engine](#disruption-resolution-engine)
- [API Reference](#api-reference)
- [Getting Started](#getting-started)
- [Configuration](#configuration)
- [Live Data and Monitoring](#live-data-and-monitoring)
- [Continuous Integration](#continuous-integration)
- [Skills Demonstrated](#skills-demonstrated)
- [Roadmap](#roadmap)
- [License](#license)

---

## Overview

When a single flight is delayed, downstream effects ripple through gates, crew rotations, aircraft positioning, and passenger connections. Airlines often coordinate these manually — a slow process that scales poorly during peak disruption windows.

AeroSync addresses this with three integrated layers:

1. **ML prediction service** — A calibrated stacking ensemble scores delay risk per flight using operational, seasonal, and live weather features.
2. **Spring Boot operations API** — Persists flights, gates, crew, and disruptions in PostgreSQL; orchestrates predictions, scheduling, and resolution workflows.
3. **Hybrid resolution agent** — Deterministic algorithms enforce DGCA-style operational rules and make reassignment decisions; Google Gemini generates concise human-readable explanations; cascade logic propagates impact to connected flights.

---

## Problem Statement

A delay on one flight can trigger a chain of failures:

- Crew may approach or exceed **12-hour duty limits** (DGCA regulations)
- **Gates** become blocked for the next rotation
- **Passengers** miss minimum connection buffers
- **Aircraft** may be unavailable for the next leg

Manual coordination between ops teams typically takes **20–40 minutes** and still yields suboptimal outcomes. AeroSync demonstrates how software can **detect risk early**, **model cascade impact**, and **propose or apply resolutions** with full audit trails stored in the database.

---

## Key Features

| Area | Capability |
|------|------------|
| **Delay prediction** | Stacking ensemble (Random Forest, XGBoost, Gradient Boosting, MLP) with probability calibration and explainable top delay factors |
| **Live weather** | OpenWeatherMap integration for Indian hub airports (BOM, DEL, BLR, HYD) |
| **Cascade simulation** | NetworkX directed graph for aircraft-rotation propagation and critical-path analysis |
| **Rule engine** | DGCA duty-hour checks, weather/aircraft compatibility, gate turnaround constraints |
| **Hybrid AI resolution** | Algorithm decides; Gemini explains; failures degrade gracefully to rule-based output |
| **Human-in-the-loop** | Propose → approve/reject workflow with feedback-driven re-reasoning |
| **Operations data** | JPA entities for flights, gates, crew, disruptions; JSONB resolution storage |
| **Schedule generation** | Daily flight schedule seeded with live weather scores on startup |
| **Auth** | Username/password login (BCrypt), JWT bearer tokens, airport-scoped access: users only see and change the airports assigned to them |
| **Live data (Stage 4)** | OpenSky Network aircraft states and past flights, airport weather observations, data modes SYNTHETIC / HYBRID / LIVE with the source on every record |
| **Disruption monitoring** | Background detection of weather, crew, delay-risk, turnaround, connection and aircraft problems with severity and confidence; never acts without approval |
| **CI** | GitHub Actions: backend, frontend, ML tests and Docker image builds on every pull request |

---

## System Architecture

```mermaid
flowchart TB
    subgraph Client["Client / Frontend (planned)"]
        UI[Dashboard on :3000]
    end

    subgraph Backend["aerosync-backend — Spring Boot :8080"]
        API[REST Controllers]
        FS[FlightService]
        DS[DisruptionService]
        AS[AgentService]
        RAS[ResolutionAlgorithmService]
        CS[CascadeService]
        DB[(PostgreSQL)]
    end

    subgraph ML["aerosync-ml — FastAPI :8000"]
        PRED["/predict"]
        CASCADE["/graph/cascade"]
        WX[Weather Service]
        MODEL[Stacking Ensemble]
    end

    subgraph External["External APIs"]
        OWM[OpenWeatherMap]
        GEM[Google Gemini]
    end

    UI --> API
    API --> FS & DS & AS
    FS --> PRED
    AS --> RAS & CS & GEM
    CS --> RAS
    API --> DB
    PRED --> MODEL
    PRED --> WX
    WX --> OWM
    AS --> GEM
```

**Resolution flow (hybrid mode)**

1. Trigger or simulate a disruption on a flight.
2. `ResolutionAlgorithmService` runs the rule engine and resolves gate, crew, and passenger constraints.
3. `AgentService` requests a short ops brief from **Gemini** (explanation only — not decision-making).
4. `CascadeService` finds connected flights and resolves propagated delays.
5. Full resolution JSON is persisted on the `Disruption` entity.

---

## Tech Stack

| Layer | Technologies |
|-------|----------------|
| **Backend** | Java 17, Spring Boot 4.x, Spring Data JPA, Spring WebFlux, Lombok |
| **Web dashboard** | React 19, TypeScript, Vite, Cytoscape.js (network graph) |
| **Database** | PostgreSQL (JSONB for resolution payloads) |
| **ML service** | Python 3.11+, FastAPI, Uvicorn, scikit-learn, XGBoost, NetworkX, pandas, joblib |
| **AI / LLM** | Google Gemini 2.5 Flash (explanation generation) |
| **Auth** | JWT (jjwt) |
| **Weather** | OpenWeatherMap API |
| **Build** | Maven (backend), pip/venv (ML) |
| **Visualization** | matplotlib, seaborn (training evaluation plots) |

---

## Project Structure

```
AeroSync/
├── aerosync-backend/                 # Spring Boot REST API
│   └── src/main/java/com/aerosync/
│       ├── controller/               # Flights, disruptions, agents, auth
│       ├── service/                  # ML client, weather, resolution, cascade, seeding
│       ├── model/                    # Flight, Crew, Gate, Disruption (JPA)
│       ├── repository/               # Spring Data repositories
│       ├── dto/                      # Prediction request/response DTOs
│       ├── security/                 # JWT filter and utilities
│       └── config/                   # WebClient beans, app configuration
│
├── aerosync-web/                     # React operations dashboard (network graph, airports, flights)
│
├── aerosync-ml/                      # FastAPI ML & graph service
│   ├── main.py                       # Prediction, batch, weather, cascade endpoints
│   ├── train_model.py                # Stacking ensemble training pipeline
│   ├── data_generator.py             # Synthetic Indian-route training data
│   ├── services/weather_service.py   # Live weather scoring
│   ├── data/flight_data.csv          # ~10k labeled training records
│   ├── models/                       # Serialized ensemble, scaler, features
│   └── plots/                        # ROC, confusion matrix, feature importance
│
├── LICENSE
└── README.md
```

---

## Machine Learning Pipeline

### Dataset

- **~10,000 synthetic records** across realistic Indian domestic routes (BOM, DEL, BLR, HYD, CCU, MAA, AMD, GOI, PNQ)
- **21 engineered features**: departure time, seasonality (monsoon, fog), peak hours, crew risk, turnaround tightness, route distance, historical delay rate, encodings, and composite `risk_score`

### Model

- **Stacking ensemble**: Random Forest + XGBoost + Gradient Boosting + MLP → logistic regression meta-learner
- **Calibrated probabilities** via `CalibratedClassifierCV` (sigmoid)
- Artefacts: `ensemble.pkl`, `scaler.pkl`, `feature_columns.pkl`, `feature_importances.pkl`

### Train & evaluate

```bash
cd aerosync-ml
python -m venv venv && source venv/bin/activate
pip install pandas numpy scikit-learn xgboost joblib matplotlib seaborn fastapi uvicorn networkx python-dotenv requests

python data_generator.py    # regenerate flight_data.csv (optional)
python train_model.py       # trains ensemble and writes plots/ + models/
```

### Serve predictions

```bash
python main.py              # FastAPI on http://localhost:8000
```

Example response from `POST /predict` includes `delay_probability`, `risk_level` (LOW/MEDIUM/HIGH), `top_delay_factors`, and whether live weather was applied.

---

## Disruption Resolution Engine

### Disruption types

`WEATHER_DELAY` · `CREW_TIMEOUT` · `AIRCRAFT_FAULT` · `GATE_CONFLICT` · `CASCADING_DELAY`

### Constraint domains

| Domain | Checks |
|--------|--------|
| **Rules** | DGCA 12h max duty, duty warnings, ATR72 weather blocks, critical gate turnaround |
| **Gates** | Availability, terminal compatibility, aircraft type fit |
| **Crew** | Duty hours remaining, location, aircraft certification, minimum rest |
| **Passengers** | Connection buffer breach, alternate flight capacity, tier priority |
| **Cascade** | Connected departures from destination hub with propagated delay |

### Agent modes

| Endpoint | Behavior |
|----------|----------|
| `POST /api/agents/propose/{id}` | Computes resolution **without** writing to DB |
| `POST /api/agents/approve/{id}` | Applies approved proposal |
| `POST /api/agents/reject/{id}` | Re-reasons excluding rejected resources |
| `POST /api/agents/resolve/{id}` | Full auto-resolve + persist |

---

## API Reference

### Backend (`http://localhost:8080` locally, `http://localhost:8082` under Docker Compose)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/flights` | List all flights |
| `GET` | `/api/flights/risk/{level}` | Filter by LOW / MEDIUM / HIGH |
| `GET` | `/api/flights/disrupted` | Active disrupted flights |
| `POST` | `/api/flights` | Create flight (triggers ML prediction) |
| `POST` | `/api/flights/predict-all` | Batch ML scoring |
| `POST` | `/api/flights/generate-schedule` | Regenerate daily schedule |
| `GET` | `/api/flights/weather/{code}` | Airport weather score |
| `GET` | `/api/disruptions` | List disruptions |
| `GET` | `/api/disruptions/active` | Active disruptions |
| `POST` | `/api/disruptions/trigger` | Trigger disruption (query params) |
| `POST` | `/api/disruptions/simulate` | Random high-risk disruption |
| `POST` | `/api/agents/resolve/{id}` | Auto-resolve disruption |
| `POST` | `/api/agents/propose/{id}` | Propose resolution |
| `POST` | `/api/auth/login` | Sign in (public); returns a bearer token and the user's airports |
| `GET` | `/api/auth/me` | The signed-in user, role and authorized airports |
| `GET` | `/api/health` | Liveness (public) |
| `GET` | `/api/graph/network?date=&airports=` | Operational network graph of the user's airports: airports, flight instances, route edges, dependencies |
| `GET` | `/api/graph/airports/{iata}?date=` | One airport with its departing/arriving flights and their dependencies |
| `GET` | `/api/graph/flights/{flightId}?depth=` | A flight instance with upstream/downstream dependencies |
| `GET` | `/api/graph/flights/{flightId}/affected` | Flights reachable downstream through dependencies |
| `GET` | `/api/airports` / `/api/airports/{iata}/operations?date=` | Airports; departures, arrivals and gates of one airport |
| `GET` | `/api/airports/{iata}/live-aircraft` | Aircraft reported near one of your airports (OpenSky; Stage 4) |
| `GET` | `/api/airports/{iata}/weather` | Latest observation and, separately, the forecast of one of your airports (Stage 4) |
| `GET` | `/api/system/status` | Data mode, feed freshness, database/ML health, disruption monitor (Stage 4) |
| `GET` | `/api/health/ready` | Readiness: database reachable (public, `UP`/`DOWN` only) |

Every endpoint except login and health needs `Authorization: Bearer <token>`
and is limited to the signed-in user's airports (other airports: 403). See
[`aerosync-backend/docs/SECURITY.md`](aerosync-backend/docs/SECURITY.md). The
operational network graph (Stage 1) is documented in
[`aerosync-backend/docs/OPERATIONAL_NETWORK.md`](aerosync-backend/docs/OPERATIONAL_NETWORK.md),
live data and monitoring (Stage 4) in
[`aerosync-backend/docs/LIVE_DATA.md`](aerosync-backend/docs/LIVE_DATA.md).

**Trigger disruption example**

```bash
API=http://localhost:8082   # Docker Compose; http://localhost:8080 for ./mvnw spring-boot:run
TOKEN=$(curl -s -X POST $API/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"bom.ops","password":"<your demo password>"}' | jq -r .token)
curl -X POST -H "Authorization: Bearer $TOKEN" \
  "$API/api/disruptions/trigger?flightId=AI-101&type=WEATHER_DELAY&delayMinutes=120"
```

### ML service (`http://localhost:8000`)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/health` | Service health |
| `GET` | `/models/info` | Model metadata and top features |
| `POST` | `/predict` | Single-flight delay prediction |
| `POST` | `/predict/batch` | Batch predictions |
| `GET` | `/weather/{airport_code}` | Live weather score |
| `POST` | `/graph/cascade` | Cascade propagation simulation |

Interactive docs: `http://localhost:8000/docs`

---

## Getting Started

### Prerequisites

- **Java 17+** (Temurin recommended)
- **Maven 3.8+**
- **Python 3.11+**
- **PostgreSQL 14+**
- API keys: **Google Gemini**, **OpenWeatherMap** (optional but recommended for live features)

### 1. Database setup

```bash
createdb aerosync
# Update credentials in aerosync-backend/src/main/resources/application.properties
```

### 2. Start the ML service

```bash
cd aerosync-ml
python -m venv venv
source venv/bin/activate          # Windows: venv\Scripts\activate

pip install pandas numpy scikit-learn xgboost joblib matplotlib seaborn \
  fastapi uvicorn networkx python-dotenv requests pydantic

# Optional: create aerosync-ml/.env with:
# WEATHER_API_KEY=your_openweathermap_key

python main.py
```

### 3. Start the backend

```bash
cd aerosync-backend
./mvnw spring-boot:run
```

On first launch, `DataSeederService` populates gates, crew, and a daily flight schedule. Ensure `ml.service.url` points to the running ML service (`http://localhost:8000`).

### 4. Start the web dashboard

```bash
cd aerosync-web
npm install
npm run dev          # http://localhost:3000, proxies /api to http://localhost:8080
```

See [`aerosync-web/README.md`](aerosync-web/README.md) for configuration, tests and the Docker image.

### Or: everything with Docker Compose

```bash
cp .env.example .env      # then fill in at least DB_PASSWORD and JWT_SECRET
docker compose up --build
```

Web dashboard http://localhost:3000, backend http://localhost:8082. `.env` is
ignored by git; keep real secrets only there.

The stack starts in `SYNTHETIC` mode and needs no external API. To add live
data, set in `.env` and run `docker compose up -d backend` again:

```bash
AEROSYNC_DATA_MODE=HYBRID            # synthetic schedule + live data, each labelled
OPENSKY_CLIENT_ID=...                # OpenSky API client (optional; anonymous otherwise)
OPENSKY_CLIENT_SECRET=...
WEATHER_API_KEY=...                  # OpenWeatherMap (optional)
```

### 5. Sign in (demo accounts, development only)

Set these in `.env` for Docker Compose (see [`.env.example`](.env.example)), or
export them in the shell before `./mvnw spring-boot:run`, then restart the backend:

```bash
JWT_SECRET=...                       # output of: openssl rand -base64 48 (32+ characters)
AEROSYNC_DEMO_USERS_ENABLED=true
AEROSYNC_DEMO_PASSWORD=choose-a-local-password
```

This creates `bom.ops` (BOM), `del.ops` (DEL), `network.ops` (BOM, DEL, BLR, HYD)
and the read-only `bom.viewer` (BOM), all with that password. Open
http://localhost:3000 and sign in. Without these settings no account exists;
see [`aerosync-backend/docs/SECURITY.md`](aerosync-backend/docs/SECURITY.md) for
creating real users.

### 6. Quick smoke test

```bash
API=http://localhost:8082   # Docker Compose; http://localhost:8080 for ./mvnw spring-boot:run

# Backend health (public); ML health only when running locally (no host port under Docker)
curl $API/api/health
curl http://localhost:8000/health

# Everything else needs a token
TOKEN=$(curl -s -X POST $API/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"bom.ops","password":"choose-a-local-password"}' | jq -r .token)
curl -H "Authorization: Bearer $TOKEN" $API/api/flights               # BOM flights only
curl -H "Authorization: Bearer $TOKEN" $API/api/graph/airports/DEL    # 403: not your airport

# Simulate a disruption at BOM, then resolve it (use the returned disruption id)
curl -X POST -H "Authorization: Bearer $TOKEN" $API/api/disruptions/simulate
curl -X POST -H "Authorization: Bearer $TOKEN" $API/api/agents/resolve/1
```

---

## Configuration

Configure via `application.properties` (backend) and environment variables (ML). **Never commit real API keys or database passwords.**

| Variable / Property | Service | Purpose |
|-------------------|---------|---------|
| `spring.datasource.*` | Backend | PostgreSQL connection |
| `ml.service.url` | Backend | ML service base URL |
| `gemini.api.key` | Backend | Gemini explanation API |
| `weather.api.key` | Backend | OpenWeatherMap for schedule weather |
| `JWT_SECRET` (`jwt.secret`) | Backend | JWT signing secret, 32+ characters, from `.env` (no default) |
| `JWT_EXPIRATION` | Backend | Token lifetime in ms (default 8 h) |
| `AEROSYNC_DEMO_USERS_ENABLED`, `AEROSYNC_DEMO_PASSWORD`, `AEROSYNC_DEMO_USERS` | Backend | Development-only demo accounts |
| `WEATHER_API_KEY` | ML | Live weather in `/predict` |
| `AEROSYNC_DATA_MODE` | Backend | `SYNTHETIC` (default), `HYBRID` or `LIVE` |
| `OPENSKY_CLIENT_ID`, `OPENSKY_CLIENT_SECRET` | Backend | OpenSky API client (OAuth2); empty = anonymous |
| `OPENSKY_STATES_POLL_SECONDS` | Backend | Wanted live-aircraft poll interval (raised automatically to stay within OpenSky's credits) |
| `AEROSYNC_MONITORING_ENABLED` | Backend | Disruption monitor (default on; detects only) |
| `AEROSYNC_LOG_LEVEL` | Backend | Log level of `com.aerosync` (default `INFO`) |

All Stage 4 settings with defaults:
[`aerosync-backend/docs/LIVE_DATA.md`](aerosync-backend/docs/LIVE_DATA.md#8-configuration).

---

## Live Data and Monitoring

Stage 4 connects AeroSync to real aviation data without giving up the
synthetic demo:

- **Data modes.** `SYNTHETIC` (default, no external API), `HYBRID`
  (synthetic schedule plus imported data) and `LIVE` (imported data only).
  Every flight, aircraft state, weather report and disruption records its
  source; the dashboard shows the mode and the source of every imported
  flight, and never presents synthetic data as live.
- **OpenSky Network.** Aircraft states around the network's airports and
  completed flights of previous days, fetched in the background with
  OAuth2, timeouts, retries, backoff and a credit budget that respects
  OpenSky's daily limits. OpenSky has no timetables, delays, aircraft types
  or flight status; AeroSync does not invent them.
- **Weather.** Observations and forecasts per airport, stored separately;
  only observations feed predictions and detection.
- **Disruption monitoring.** Detected disruptions appear on the Operations
  page with severity, confidence, source and time; resolving them still
  needs a controller's proposal and approval.

Details, field lists and limitations:
[`aerosync-backend/docs/LIVE_DATA.md`](aerosync-backend/docs/LIVE_DATA.md).

## Continuous Integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on every pull
request to `main` and every push to `main` (and manually via *Run workflow*):

| Job | Steps |
|-----|-------|
| Backend | JDK 17, Maven cache, `./mvnw -B verify` (unit and Spring/H2 integration tests), Flyway migrations against a throwaway PostgreSQL 16 service |
| Frontend | Node 22, npm cache, `npm ci`, `npm run typecheck`, `npm test`, `npm run build` |
| ML | Python 3.11, pip cache, `pytest`, then starts the service with uvicorn and checks `/health` |
| Docker | `docker compose config`, builds the backend, web, ML and Streamlit images (layer cache in GitHub Actions) |

Setup: nothing is required. The workflow uses no secrets because every test
mocks the external APIs; it has read-only repository permissions and does
not deploy. If a future job needs a credential, add it under *Settings →
Secrets and variables → Actions* and reference it as `${{ secrets.NAME }}`;
never print it. To make the checks mandatory, enable branch protection for
`main` with the four CI jobs as required status checks.
---

## Skills Demonstrated

- **Full-stack integration** — REST microservices, async WebClient calls, cross-language ML inference
- **Machine learning engineering** — Feature engineering, ensemble methods, calibration, model versioning with joblib
- **Applied AI design** — Hybrid architecture separating deterministic decisions from LLM explanation (reliable + auditable)
- **Domain modeling** — Aviation constraints, Indian airport network, regulatory rule encoding
- **Data persistence** — JPA/Hibernate, PostgreSQL JSONB for structured resolution logs
- **API design** — RESTful resources, human-in-the-loop agent workflows, OpenAPI via FastAPI
- **DevOps awareness** — Environment-based secrets, health endpoints, reproducible training pipeline

---

## Roadmap

- [x] React operations dashboard (`aerosync-web`, port 3000)
- [ ] WebSocket live disruption feed
- [x] OpenSky Network integration for real flight telemetry (Stage 4, backend ingestion)
- [x] Disruption monitoring with severity and confidence (Stage 4)
- [ ] Unit and integration tests for rule engine and cascade logic (partly: resolution scope, monitoring rules)
- [x] Docker Compose for one-command local deployment
- [x] Sign-in and airport-scoped access (Stage 3)
- [x] CI pipeline (GitHub Actions) with model regression tests (Stage 4)

---

## License

This project is licensed under the [MIT License](LICENSE) — Copyright (c) 2026 SharvajTech.

---

<p align="center">
  <sub>Built for learning and demonstration purposes. Not affiliated with any airline or aviation authority.</sub>
</p>
