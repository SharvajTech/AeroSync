# AeroSync ML service

FastAPI service with the delay-prediction model (stacking ensemble, 20
features, `models/`), SHAP explanations, cascade simulation and optional RAG
retrieval. The Spring backend calls `POST /predict`; the service is reachable
only inside the Docker network (`ml-service:8000`) under Docker Compose.

## Run

```bash
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --port 8000          # http://localhost:8000/docs
```

Optional: `WEATHER_API_KEY` (OpenWeatherMap). When it is set, `/predict`
replaces the request's weather score with the current **observation** at the
origin when one is available; a forecast is never used, and when the weather
source fails the request's value is kept (`weather_was_live: false`).

## Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/health` | status and loaded model |
| `GET` | `/models/info` | model type, feature count, top features |
| `POST` | `/predict` | one flight; all 13 request fields are required and range-checked (422 otherwise) |
| `POST` | `/predict/batch` | several flights (one invalid flight rejects the batch with 422) |
| `GET` | `/weather/{airport}`, `/weather/all/airports` | weather score |
| `POST` | `/graph/cascade` | cascade propagation simulation |
| `POST` | `/rag/retrieve` | policy retrieval (needs `faiss-cpu` and a built index; otherwise `available: false`) |

## Test

```bash
pip install -r requirements-dev.txt
python -m pytest tests -q
```

* `tests/test_prediction_service.py`: health, the request/response contract
  with the backend's `MLService`, missing and invalid input (422), live-weather
  handling with a mocked weather source, determinism and a regression check of
  the committed model's output.
* `tests/test_opensky_fetcher.py`: the standalone fetcher with a mocked
  `requests` (credentials only from the environment, token reuse, stop on
  HTTP 429, no secret in the output).
* `tests/test_gnn_compatibility.py`: the GNN encoders and artefacts are
  unchanged by the operational network (Stage 1).

No test calls an external API.

## OpenSky fetcher (`data/opensky_fetcher.py`)

A standalone script that collected OpenSky data for model experiments; the
backend does its own ingestion (see `aerosync-backend/docs/LIVE_DATA.md`).
It reads `OPENSKY_CLIENT_ID` and `OPENSKY_CLIENT_SECRET` from the environment
(OAuth2 client credentials; OpenSky no longer accepts username/password) and
runs anonymously without them. Earlier versions of this file contained
account credentials; they were removed in Stage 4 and that password must be
considered compromised and changed.

## Limitations

* The airport encoder knows the original nine airports (`AIRPORT_MAP`). An
  unknown airport is encoded as the default (BOM) instead of being rejected;
  a test documents this. Changing it requires retraining.
* `requirements.txt` is not pinned. The committed pickles load with current
  scikit-learn/XGBoost releases (XGBoost warns that the model was serialized
  by an older version); pin versions when the model is retrained.
* `/health` reports `weather_live: true` when the weather module is present,
  even without an API key.
