import numpy as np
import pandas as pd
import joblib
import os
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
from typing import Optional, List
import networkx as nx
# Add this block right after all the import statements, before app = FastAPI(...)
from dotenv import load_dotenv
load_dotenv()  # loads WEATHER_API_KEY from .env file

app = FastAPI(title="AeroSync 2.0 ML Service", version="2.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:8080", "http://localhost:3000"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# ── Load models ───────────────────────────────────────────────────────────────
MODEL_DIR = "models"

# Try loading ensemble first, fall back to old model
try:
    ensemble_model = joblib.load(f"{MODEL_DIR}/ensemble.pkl")
    scaler         = joblib.load(f"{MODEL_DIR}/scaler.pkl")
    feature_cols   = joblib.load(f"{MODEL_DIR}/feature_columns.pkl")
    MODEL_NAME     = "stacking_ensemble"
    print("✅ Ensemble model loaded")

    try:
        importances = joblib.load(
            f"{MODEL_DIR}/feature_importances.pkl")
    except Exception:
        importances = {}
        print("⚠️ Feature importances not found")

except Exception:
    # Fallback to old single model
    print("⚠️ Ensemble not found — trying legacy model")
    try:
        ensemble_model = joblib.load(
            f"{MODEL_DIR}/delay_model.pkl")
        scaler = None
        MODEL_NAME = "xgboost_legacy"
        importances = {}

        # Legacy feature columns
        feature_cols = [
            "departure_hour", "day_of_week", "month",
            "route_distance_km", "weather_score",
            "crew_duty_hours", "historical_delay_rate",
            "aircraft_age", "gate_turnaround_mins",
            "origin_encoded", "destination_encoded",
            "aircraft_encoded", "is_peak_hour", "is_monsoon",
            "crew_risk", "tight_turnaround", "risk_score"
        ]
        print(f"✅ Legacy model loaded — {len(feature_cols)} features")
    except Exception as e:
        print(f"❌ Model loading failed: {e}")
        raise RuntimeError(f"No model found: {e}")

# ── SHAP explainability (optional — /predict works with or without it) ───────
# Uses a SEPARATE artifact: the fitted XGBoost base-learner extracted from
# inside the stacking ensemble during training (see
# train_model.py::export_shap_artifacts). This does not touch or replace
# `ensemble_model` — the calibrated stacking ensemble is still the only
# model used to compute delay_probability. SHAP is used only to *explain*
# that prediction, via the strongest individual tree-based component.
#
# Why this is a reasonable proxy rather than exactly what the ensemble did:
# the deployed predictor is a 5-fold-calibrated StackingClassifier (4 base
# learners + a Logistic Regression meta-learner) — there's no single tree
# structure to run exact SHAP on, and a model-agnostic explainer over the
# whole pipeline would be slow and only approximate. TreeExplainer on the
# XGBoost base-learner is exact for that component and fast enough for a
# live request; see README "Model explainability (SHAP)" for the full
# rationale and its limits.
SHAP_AVAILABLE = False
try:
    import shap
    xgb_shap_model = joblib.load(f"{MODEL_DIR}/xgb_base_estimator.pkl")
    shap_feature_cols = joblib.load(f"{MODEL_DIR}/shap_feature_columns.pkl")
    if shap_feature_cols != feature_cols:
        print("⚠️ SHAP feature columns don't match the deployed model's "
              "feature columns — disabling SHAP to avoid misaligned "
              "explanations. Retrain with train_model.py to refresh both.")
    else:
        shap_explainer = shap.TreeExplainer(xgb_shap_model)
        SHAP_AVAILABLE = True
        print("✅ SHAP explainer loaded (XGBoost base-learner)")
except Exception as e:
    print(f"⚠️ SHAP explainability not available: {e}")

# ── RAG retrieval (optional — /rag/retrieve works with or without it) ────────
# Bridges to aerosync-rag/, a SIBLING directory of aerosync-ml/. Loads a
# pre-built FAISS index (built ahead of time via
# aerosync-rag/retrieval/build_index.py) — this service only ever LOADS an
# index, it never builds one at request time. Falls back gracefully — same
# pattern as WEATHER_AVAILABLE/SHAP_AVAILABLE above — so a missing index or
# missing aerosync-rag/ never breaks /predict or anything else in this file.
RAG_AVAILABLE = False
try:
    import sys as _sys
    _rag_retrieval_dir = os.path.join(os.path.dirname(__file__), "..", "aerosync-rag", "retrieval")
    _rag_ingestion_dir = os.path.join(os.path.dirname(__file__), "..", "aerosync-rag", "ingestion")
    _rag_index_dir = os.path.join(os.path.dirname(__file__), "..", "aerosync-rag", "index")
    _sys.path.insert(0, _rag_retrieval_dir)
    _sys.path.insert(0, _rag_ingestion_dir)

    from faiss_retriever import FaissRetriever  # noqa: E402

    if os.path.exists(os.path.join(_rag_index_dir, "flight_policies.faiss")):
        rag_retriever = FaissRetriever.load(_rag_index_dir)
        RAG_BACKEND_NAME = rag_retriever.embedder.name
        RAG_AVAILABLE = True
        print(f"✅ RAG retriever loaded (backend: {RAG_BACKEND_NAME})")
    else:
        print("⚠️ No RAG index found at aerosync-rag/index/ — run "
              "aerosync-rag/retrieval/build_index.py first. /rag/retrieve "
              "will report unavailable until then.")
except Exception as e:
    print(f"⚠️ RAG retrieval not available: {e}")

# ── Weather service ───────────────────────────────────────────────────────────
WEATHER_API_KEY = os.environ.get("WEATHER_API_KEY", "")
try:
    from services.weather_service import (
        get_weather_score, get_all_airports_weather)
    WEATHER_AVAILABLE = True
    print("✅ Weather service loaded")
except ImportError:
    WEATHER_AVAILABLE = False
    print("⚠️ Weather service not found")

# ── Encoding maps ─────────────────────────────────────────────────────────────
AIRPORT_MAP = {
    "BOM": 0, "DEL": 1, "BLR": 2, "HYD": 3,
    "CCU": 4, "MAA": 5, "AMD": 6, "GOI": 7, "PNQ": 8
}
AIRCRAFT_MAP = {
    "A320": 0, "B737": 1, "A321": 2, "ATR72": 3, "B777": 4
}


# ── Request/Response schemas ──────────────────────────────────────────────────
class FlightPredictionRequest(BaseModel):
    flight_id:             str
    departure_hour:        int   = Field(..., ge=0, le=23)
    day_of_week:           int   = Field(..., ge=0, le=6)
    month:                 int   = Field(..., ge=1, le=12)
    origin_airport:        str
    destination_airport:   str
    route_distance_km:     float = Field(..., gt=0)
    weather_score:         int   = Field(..., ge=0, le=3)
    crew_duty_hours:       float = Field(..., ge=0, le=14)
    historical_delay_rate: float = Field(..., ge=0, le=1)
    aircraft_age:          int   = Field(..., ge=1, le=30)
    gate_turnaround_mins:  int   = Field(..., ge=10, le=120)
    aircraft_type:         str

    model_config = {"json_schema_extra": {"example": {
        "flight_id": "AI-101", "departure_hour": 7,
        "day_of_week": 4, "month": 7,
        "origin_airport": "BOM", "destination_airport": "DEL",
        "route_distance_km": 1148, "weather_score": 2,
        "crew_duty_hours": 11.5, "historical_delay_rate": 0.41,
        "aircraft_age": 8, "gate_turnaround_mins": 25,
        "aircraft_type": "A320"
    }}}


class GraphFlightNode(BaseModel):
    flight_id:         str
    origin:            str
    destination:       str
    departure_hour:    int
    aircraft_type:     str
    delay_probability: Optional[float] = 0.5
    risk_level:        Optional[str]   = "MEDIUM"
    crew_duty_hours:   Optional[float] = 6.0
    weather_score:     Optional[int]   = 0
    status:            Optional[str]   = "SCHEDULED"


class CascadeRequest(BaseModel):
    disrupted_flight_id: str
    delay_minutes:       int
    flights:             List[GraphFlightNode]


class RagRetrieveRequest(BaseModel):
    query: str
    top_k: Optional[int] = 3


# ── Feature engineering ───────────────────────────────────────────────────────
def build_features(req: FlightPredictionRequest) -> dict:
    origin_enc = AIRPORT_MAP.get(req.origin_airport.upper(), 0)
    dest_enc   = AIRPORT_MAP.get(req.destination_airport.upper(), 1)
    ac_enc     = AIRCRAFT_MAP.get(req.aircraft_type.upper(), 0)

    is_peak_hour    = 1 if req.departure_hour in [7,8,9,17,18,19,20] else 0
    is_monsoon      = 1 if req.month in [6,7,8,9] else 0
    is_fog_season   = 1 if req.month in [11,12,1,2] else 0
    is_peak_day     = 1 if req.day_of_week in [4,6] else 0
    crew_risk       = 1 if req.crew_duty_hours > 10 else 0
    tight_turnaround = 1 if req.gate_turnaround_mins < 30 else 0
    aging_aircraft  = 1 if req.aircraft_age > 18 else 0
    weather_monsoon = req.weather_score * is_monsoon
    risk_score = (
        req.weather_score          * 0.25 +
        req.historical_delay_rate  * 0.35 +
        crew_risk                  * 0.15 +
        tight_turnaround           * 0.10 +
        is_fog_season              * 0.08 +
        is_peak_day                * 0.07
    )

    return {
        "departure_hour":        req.departure_hour,
        "day_of_week":           req.day_of_week,
        "month":                 req.month,
        "route_distance_km":     req.route_distance_km,
        "weather_score":         req.weather_score,
        "crew_duty_hours":       req.crew_duty_hours,
        "historical_delay_rate": req.historical_delay_rate,
        "aircraft_age":          req.aircraft_age,
        "gate_turnaround_mins":  req.gate_turnaround_mins,
        "origin_encoded":        origin_enc,
        "destination_encoded":   dest_enc,
        "aircraft_encoded":      ac_enc,
        "is_peak_hour":          is_peak_hour,
        "is_monsoon":            is_monsoon,
        "is_fog_season":         is_fog_season,
        "is_peak_day":           is_peak_day,
        "crew_risk":             crew_risk,
        "tight_turnaround":      tight_turnaround,
        "aging_aircraft":        aging_aircraft,
        "weather_monsoon":       weather_monsoon,
        "risk_score":            risk_score,
    }


def classify_risk(prob: float) -> str:
    if prob >= 0.70: return "HIGH"
    if prob >= 0.45: return "MEDIUM"
    return "LOW"


def get_top_factors(fv, feature_names, imps) -> list:
    factors = []
    for i, fname in enumerate(feature_names):
        importance = imps.get(fname, 0)
        value      = fv[i] if i < len(fv) else 0
        if importance > 0.01 and value > 0:
            factors.append({
                "feature":    fname,
                "value":      round(float(value), 3),
                "importance": round(float(importance), 4)
            })
    return sorted(factors,
                  key=lambda x: x['importance'],
                  reverse=True)[:5]


def get_shap_explanation(fv_scaled_row, fv_unscaled, feature_names) -> list:
    """Local, per-prediction SHAP explanation for a single flight.

    Global feature importance (top_delay_factors / models/info) answers
    "what does this model generally care about, across every flight?" — it
    is a single fixed ranking that never changes per request. It cannot
    tell you WHY *this specific* flight was scored the way it was: two
    flights can hit the same top_delay_factors while one is actually driven
    mostly by weather and the other mostly by crew duty hours, and a static
    importance ranking can't distinguish them.

    SHAP fixes that by attributing THIS prediction's score to THIS flight's
    exact feature values: for the fitted XGBoost base-learner, it computes
    exactly how many log-odds each feature value added or subtracted for
    this one input, relative to the model's average output. That's the
    "local explanation" requested — the direction and magnitude are read
    directly off shap.TreeExplainer's output, never invented or estimated.
    """
    if not SHAP_AVAILABLE:
        return []
    try:
        shap_values = shap_explainer.shap_values(fv_scaled_row)
        row = np.asarray(shap_values)[0]  # (n_features,) for this one row

        entries = []
        for i, fname in enumerate(feature_names):
            contribution = float(row[i])
            entries.append({
                "feature":     fname,
                "value":       round(float(fv_unscaled[i]), 3),
                "impact":      "increases_risk" if contribution > 0 else "decreases_risk",
                "contribution": round(contribution, 4),
            })
        return sorted(entries, key=lambda e: abs(e["contribution"]),
                      reverse=True)[:5]
    except Exception as e:
        print(f"⚠️ SHAP explanation failed for this request: {e}")
        return []


# ── Endpoints ─────────────────────────────────────────────────────────────────

@app.get("/")
def root():
    return {
        "service":      "AeroSync 2.0 ML",
        "status":       "running",
        "model":        MODEL_NAME,
        "features":     len(feature_cols),
        "weather_live": WEATHER_AVAILABLE,
        "shap_explainability": SHAP_AVAILABLE
    }


@app.get("/health")
def health():
    return {
        "status":        "healthy",
        "models_loaded": True,
        "model_type":    MODEL_NAME,
        "weather_live":  WEATHER_AVAILABLE,
        "shap_explainability": SHAP_AVAILABLE
    }


@app.get("/models/info")
def models_info():
    top_features = sorted(
        importances.items(),
        key=lambda x: x[1], reverse=True
    )[:10] if importances else []

    return {
        "model_type":    MODEL_NAME,
        "feature_count": len(feature_cols),
        "top_features":  [{"feature": f, "importance": round(v, 4)}
                          for f, v in top_features],
        "shap_explainability": SHAP_AVAILABLE
    }


@app.post("/predict")
def predict(req: FlightPredictionRequest):
    try:
        # If weather service available and key set,
        # override weather_score with live data
        live_weather_score = req.weather_score
        if WEATHER_AVAILABLE and WEATHER_API_KEY:
            try:
                w = get_weather_score(
                    req.origin_airport, WEATHER_API_KEY)
                if w.get("is_live"):
                    live_weather_score = w["weather_score"]
            except Exception:
                pass

        # Build modified request with live weather
        import copy
        req_with_live = copy.copy(req)
        req_with_live.weather_score = live_weather_score

        features = build_features(req_with_live)
        fv = np.array(
            [features.get(col, 0) for col in feature_cols]
        ).reshape(1, -1)

        # Scale if scaler available (ensemble needs scaling)
        if scaler is not None:
            fv_input = scaler.transform(fv)
        else:
            fv_input = fv

        delay_prob = float(
            ensemble_model.predict_proba(fv_input)[0][1])

        top_factors = get_top_factors(
            fv[0].tolist(), feature_cols, importances)

        # Local, per-flight SHAP explanation — see get_shap_explanation()
        # docstring for how this differs from top_delay_factors above.
        # "value" is the original (unscaled) feature value for readability;
        # "contribution" is the SHAP attribution computed on the
        # standardized input the XGBoost base-learner actually sees.
        explanation = (
            get_shap_explanation(fv_input, fv[0], feature_cols)
            if SHAP_AVAILABLE else []
        )

        return {
            "flight_id":           req.flight_id,
            "delay_probability":   round(delay_prob, 4),
            "risk_level":          classify_risk(delay_prob),
            "predicted_delayed":   bool(delay_prob >= 0.50),
            "confidence":          round(abs(delay_prob - 0.5) * 2, 4),
            "top_delay_factors":   top_factors,
            "explanation":         explanation,
            "model_used":          MODEL_NAME,
            "features_used":       len(feature_cols),
            "live_weather_score":  live_weather_score,
            "weather_was_live":    live_weather_score != req.weather_score
        }
    except Exception as e:
        raise HTTPException(500, f"Prediction failed: {e}")


@app.post("/predict/batch")
def predict_batch(flights: List[FlightPredictionRequest]):
    results = []
    for flight in flights:
        try:
            result = predict(flight)
            results.append(result)
        except Exception as e:
            results.append({
                "flight_id":        flight.flight_id,
                "error":            str(e),
                "delay_probability": 0.5,
                "risk_level":        "MEDIUM",
                "explanation":       [],
                "model_used":        "fallback"
            })
    return {"total": len(flights), "results": results}


@app.get("/weather/{airport_code}")
def get_weather(airport_code: str):
    """Get live weather score for an airport."""
    if not WEATHER_AVAILABLE:
        raise HTTPException(503, "Weather service not configured")
    if not WEATHER_API_KEY:
        raise HTTPException(503, "WEATHER_API_KEY not set")

    result = get_weather_score(
        airport_code.upper(), WEATHER_API_KEY)
    return result


@app.get("/weather/all/airports")
def get_all_weather():
    """Get weather for all 4 main Indian airports."""
    if not WEATHER_AVAILABLE or not WEATHER_API_KEY:
        return {a: {"weather_score": 1, "is_live": False}
                for a in ["BOM", "DEL", "BLR", "HYD"]}

    return get_all_airports_weather(WEATHER_API_KEY)


@app.post("/graph/cascade")
def simulate_cascade(req: CascadeRequest):
    G = nx.DiGraph()

    for f in req.flights:
        G.add_node(f.flight_id,
                   origin=f.origin,
                   destination=f.destination,
                   departure_hour=f.departure_hour,
                   aircraft_type=f.aircraft_type,
                   delay_probability=f.delay_probability or 0.5,
                   risk_level=f.risk_level or "MEDIUM",
                   crew_duty_hours=f.crew_duty_hours or 6.0,
                   weather_score=f.weather_score or 0,
                   status=f.status or "SCHEDULED")

    # Build aircraft rotation edges
    sorted_flights = sorted(req.flights, key=lambda f: f.departure_hour)
    for flight in sorted_flights:
        for next_f in sorted_flights:
            if (next_f.origin == flight.destination
                    and next_f.departure_hour > flight.departure_hour
                    and next_f.departure_hour <= flight.departure_hour + 3
                    and next_f.flight_id != flight.flight_id):
                turnaround = next_f.departure_hour - flight.departure_hour
                weight     = max(0.3, 1.0 - turnaround / 3.0)
                if not G.has_edge(flight.flight_id, next_f.flight_id):
                    G.add_edge(flight.flight_id, next_f.flight_id,
                               dependency="AIRCRAFT_ROTATION",
                               weight=weight)

    # BFS cascade propagation
    if req.disrupted_flight_id not in G:
        raise HTTPException(404,
            f"{req.disrupted_flight_id} not in graph")

    cascade = {
        req.disrupted_flight_id: {
            "delay_mins":  req.delay_minutes,
            "cause":       "PRIMARY_DISRUPTION",
            "from_flight": None
        }
    }
    queue   = [(req.disrupted_flight_id, req.delay_minutes)]
    visited = {req.disrupted_flight_id}

    while queue:
        current, current_delay = queue.pop(0)
        for successor in G.successors(current):
            if successor in visited:
                continue
            edge_data   = G.edges[current, successor]
            weight      = edge_data.get("weight", 0.5)
            dep_type    = edge_data.get("dependency", "UNKNOWN")
            prop_delay  = int(current_delay * weight)
            if prop_delay > 15:
                visited.add(successor)
                cascade[successor] = {
                    "delay_mins":  prop_delay,
                    "cause":       dep_type,
                    "from_flight": current,
                    "weight":      weight
                }
                queue.append((successor, prop_delay))

    total_delay = sum(v["delay_mins"] for v in cascade.values())

    # Find critical path
    critical_path = [req.disrupted_flight_id]
    affected = [k for k in cascade if k != req.disrupted_flight_id]
    max_delay = 0
    for target in affected:
        try:
            paths = list(nx.all_simple_paths(
                G, req.disrupted_flight_id, target, cutoff=5))
            for path in paths:
                path_d = sum(cascade.get(n, {}).get("delay_mins", 0)
                             for n in path)
                if path_d > max_delay:
                    max_delay      = path_d
                    critical_path  = path
        except Exception:
            pass

    return {
        "origin_flight":           req.disrupted_flight_id,
        "initial_delay_mins":      req.delay_minutes,
        "flights_affected":        len(cascade),
        "cascade_details":         cascade,
        "total_delay_mins":        total_delay,
        "estimated_cost_usd":      total_delay * 150,
        "estimated_pax_affected":  len(cascade) * 120,
        "critical_path":           critical_path,
        "cascade_severity": (
            "CRITICAL" if len(cascade) >= 5 else
            "HIGH"     if len(cascade) >= 3 else
            "MEDIUM"   if len(cascade) >= 2 else
            "LOW"
        )
    }


@app.post("/rag/retrieve")
def rag_retrieve(req: RagRetrieveRequest):
    """Retrieval only — no LLM call here. The Spring Boot backend's
    RagService calls this to fetch policy/SOP evidence before asking
    Gemini to explain a resolution (see aerosync-backend AgentService).
    Never raises: on any failure this returns available=false with an
    empty chunk list rather than an HTTP error, so callers can degrade
    gracefully (see requirement: 'if LLM/RAG fails, still return the
    deterministic resolution')."""
    if not RAG_AVAILABLE:
        return {"available": False, "backend": None, "chunks": [], "count": 0}
    try:
        top_k = req.top_k or 3
        results = rag_retriever.retrieve(req.query, top_k=top_k)
        chunks = [
            {
                "doc_name": r.doc_name,
                "doc_title": r.doc_title,
                "section": r.section,
                "chunk_id": r.chunk_id,
                "text": r.text,
                "score": round(float(r.score), 4),
            }
            for r in results
        ]
        return {"available": True, "backend": RAG_BACKEND_NAME,
                "chunks": chunks, "count": len(chunks)}
    except Exception as e:
        return {"available": False, "backend": RAG_BACKEND_NAME,
                "chunks": [], "count": 0, "error": str(e)}


if __name__ == "__main__":
    import uvicorn
    port = int(os.environ.get("PORT", 8000))
    uvicorn.run("main:app", host="0.0.0.0", port=port, reload=True)