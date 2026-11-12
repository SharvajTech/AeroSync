"""
Stage 4 tests of the delay-prediction service with the committed models:
health, the backend contract, missing and invalid input, live-weather
handling without calling OpenWeatherMap, and a regression check of the
model output.

Run from the repository root or aerosync-ml/:
    python -m pytest aerosync-ml/tests
Needs the packages of requirements.txt and requirements-dev.txt.
"""
import re

import pytest

from conftest import REPO_DIR

ML_SERVICE_JAVA = REPO_DIR / "aerosync-backend/src/main/java/com/aerosync/service/MLService.java"


# ── health ───────────────────────────────────────────────────────────────────

def test_health_reports_the_loaded_model(client, ml_main):
    response = client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "healthy"
    assert body["models_loaded"] is True
    assert body["model_type"] == ml_main.MODEL_NAME == "stacking_ensemble"


def test_model_info_lists_the_feature_count(client, ml_main):
    body = client.get("/models/info").json()
    assert body["feature_count"] == len(ml_main.feature_cols) == 20


# ── prediction ───────────────────────────────────────────────────────────────

def test_a_valid_flight_gets_a_consistent_prediction(client, flight):
    response = client.post("/predict", json=flight)
    assert response.status_code == 200
    body = response.json()
    p = body["delay_probability"]
    assert 0.0 <= p <= 1.0
    assert body["flight_id"] == "AI-101"
    assert body["predicted_delayed"] == (p >= 0.5)
    assert body["risk_level"] == ("HIGH" if p >= 0.70 else "MEDIUM" if p >= 0.45 else "LOW")
    assert body["confidence"] == pytest.approx(abs(p - 0.5) * 2, abs=1e-4)
    assert body["model_used"] == "stacking_ensemble"
    assert body["features_used"] == 20


def test_the_same_input_always_gives_the_same_answer(client, flight):
    first = client.post("/predict", json=flight).json()
    second = client.post("/predict", json=flight).json()
    assert first == second


def test_every_model_feature_is_computed_and_none_is_zero_filled(ml_main, flight):
    """/predict fills unknown columns with 0; make sure no model column depends on that."""
    request = ml_main.FlightPredictionRequest(**flight)
    features = ml_main.build_features(request)
    missing = [column for column in ml_main.feature_cols if column not in features]
    assert missing == []


def test_batch_prediction_answers_every_flight(client, flight):
    second = {**flight, "flight_id": "6E-2001", "weather_score": 0}
    body = client.post("/predict/batch", json=[flight, second]).json()
    assert body["total"] == 2
    assert [r["flight_id"] for r in body["results"]] == ["AI-101", "6E-2001"]


# ── missing and invalid input ────────────────────────────────────────────────

@pytest.mark.parametrize("field", [
    "flight_id", "departure_hour", "origin_airport", "route_distance_km",
    "weather_score", "crew_duty_hours", "aircraft_type",
])
def test_a_missing_feature_is_rejected_not_guessed(client, flight, field):
    del flight[field]
    response = client.post("/predict", json=flight)
    assert response.status_code == 422
    assert any(error["loc"][-1] == field and error["type"] == "missing"
               for error in response.json()["detail"])


@pytest.mark.parametrize("field, value", [
    ("departure_hour", 24),
    ("departure_hour", -1),
    ("day_of_week", 7),
    ("month", 13),
    ("route_distance_km", 0),
    ("weather_score", 4),
    ("crew_duty_hours", 15),
    ("historical_delay_rate", 1.5),
    ("aircraft_age", 0),
    ("gate_turnaround_mins", 5),
    ("departure_hour", "seven"),
])
def test_an_out_of_range_or_wrong_type_value_is_rejected(client, flight, field, value):
    flight[field] = value
    response = client.post("/predict", json=flight)
    assert response.status_code == 422
    assert any(error["loc"][-1] == field for error in response.json()["detail"])


def test_an_empty_body_is_rejected(client):
    assert client.post("/predict", json={}).status_code == 422


def test_one_invalid_flight_rejects_the_whole_batch(client, flight):
    bad = {**flight, "weather_score": 9}
    assert client.post("/predict/batch", json=[flight, bad]).status_code == 422


def test_an_airport_outside_the_encoder_is_scored_as_the_default_airport(client, flight):
    """Documents a known limitation: the model only knows the original nine
    airports (see test_gnn_compatibility.py). An unknown origin is encoded as
    0 (BOM) instead of being rejected, so the prediction equals BOM's."""
    known = client.post("/predict", json=flight).json()["delay_probability"]
    unknown = client.post("/predict", json={**flight, "origin_airport": "LKO"}).json()["delay_probability"]
    assert unknown == known


# ── weather ──────────────────────────────────────────────────────────────────

def test_without_an_api_key_the_request_weather_score_is_used(client, flight):
    body = client.post("/predict", json=flight).json()
    assert body["live_weather_score"] == flight["weather_score"]
    assert body["weather_was_live"] is False


def test_a_live_observation_replaces_the_weather_score(client, ml_main, flight, monkeypatch):
    monkeypatch.setattr(ml_main, "WEATHER_API_KEY", "test-key")
    monkeypatch.setattr(ml_main, "get_weather_score",
                        lambda airport, key: {"weather_score": 3, "is_live": True})
    body = client.post("/predict", json={**flight, "weather_score": 0}).json()
    assert body["live_weather_score"] == 3
    assert body["weather_was_live"] is True


def test_an_unavailable_weather_source_keeps_the_request_value(client, ml_main, flight, monkeypatch):
    monkeypatch.setattr(ml_main, "WEATHER_API_KEY", "test-key")

    def unavailable(airport, key):
        raise TimeoutError("weather source did not answer")

    monkeypatch.setattr(ml_main, "get_weather_score", unavailable)
    response = client.post("/predict", json=flight)
    assert response.status_code == 200
    assert response.json()["live_weather_score"] == flight["weather_score"]
    assert response.json()["weather_was_live"] is False


def test_a_fallback_reading_is_not_treated_as_live(client, ml_main, flight, monkeypatch):
    monkeypatch.setattr(ml_main, "WEATHER_API_KEY", "test-key")
    monkeypatch.setattr(ml_main, "get_weather_score",
                        lambda airport, key: {"weather_score": 1, "is_live": False})
    body = client.post("/predict", json={**flight, "weather_score": 2}).json()
    assert body["live_weather_score"] == 2
    assert body["weather_was_live"] is False


def test_all_airport_weather_without_a_key_is_marked_not_live(client):
    body = client.get("/weather/all/airports").json()
    assert body and all(reading["is_live"] is False for reading in body.values())


# ── contract with the Spring backend ─────────────────────────────────────────

def test_the_backend_sends_exactly_the_fields_the_service_expects(ml_main):
    source = ML_SERVICE_JAVA.read_text(encoding="utf-8")
    sent = set(re.findall(r'body\.put\("([a-z_]+)"', source))
    expected = set(ml_main.FlightPredictionRequest.model_fields)
    assert sent == expected


def test_the_backend_reads_fields_the_service_returns(client, flight):
    source = ML_SERVICE_JAVA.read_text(encoding="utf-8")
    read = set(re.findall(r'node\.path\("([a-z_]+)"\)', source))
    returned = set(client.post("/predict", json=flight).json())
    assert read, "MLService no longer parses the response field by field; update this test"
    assert read <= returned, f"read by the backend but not returned: {read - returned}"


# ── regression ───────────────────────────────────────────────────────────────

# Recorded with the committed models/ (stacking ensemble, 20 features).
# A change beyond the tolerance means the model or its feature engineering
# changed: retrain deliberately and update the values, do not loosen them.
REGRESSION_CASES = [
    ({}, 0.9899, "HIGH"),
    ({"flight_id": "6E-2001", "departure_hour": 13, "day_of_week": 1, "month": 3, "weather_score": 0,
      "crew_duty_hours": 4.0, "historical_delay_rate": 0.08, "gate_turnaround_mins": 60}, 0.0125, "LOW"),
    ({"flight_id": "SG-8152", "departure_hour": 6, "month": 12, "weather_score": 3, "origin_airport": "DEL",
      "destination_airport": "BLR", "route_distance_km": 1740, "aircraft_type": "B737", "aircraft_age": 22},
     0.9898, "HIGH"),
]


@pytest.mark.parametrize("changes, probability, risk", REGRESSION_CASES)
def test_model_output_matches_the_recorded_values(client, flight, changes, probability, risk):
    body = client.post("/predict", json={**flight, **changes}).json()
    assert body["delay_probability"] == pytest.approx(probability, abs=0.005)
    assert body["risk_level"] == risk


def test_risk_classification_thresholds(ml_main):
    assert ml_main.classify_risk(0.70) == "HIGH"
    assert ml_main.classify_risk(0.6999) == "MEDIUM"
    assert ml_main.classify_risk(0.45) == "MEDIUM"
    assert ml_main.classify_risk(0.4499) == "LOW"


def test_cascade_for_an_unknown_flight_is_a_404(client):
    body = {"disrupted_flight_id": "XX-1", "delay_minutes": 60,
            "flights": [{"flight_id": "AI-1", "origin": "BOM", "destination": "DEL",
                         "departure_hour": 7, "aircraft_type": "A320"}]}
    assert client.post("/graph/cascade", json=body).status_code == 404
