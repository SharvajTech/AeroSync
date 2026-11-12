"""Shared setup for the ML service tests.

main.py loads its models from the relative path "models" when it is
imported, so the tests import it from aerosync-ml/. WEATHER_API_KEY is
blanked before the import so no test calls OpenWeatherMap (python-dotenv
does not override a variable that is already set, even to an empty value).
"""
import os
import sys
import warnings
from pathlib import Path

import pytest

ML_DIR = Path(__file__).resolve().parents[1]
REPO_DIR = ML_DIR.parent


@pytest.fixture(scope="session")
def ml_main():
    os.environ["WEATHER_API_KEY"] = ""
    if str(ML_DIR) not in sys.path:
        sys.path.insert(0, str(ML_DIR))
    previous = os.getcwd()
    os.chdir(ML_DIR)
    try:
        with warnings.catch_warnings():
            # Pickles from older scikit-learn/XGBoost releases warn on load; the model still loads.
            warnings.simplefilter("ignore")
            import main  # noqa: PLC0415
    finally:
        os.chdir(previous)
    return main


@pytest.fixture(scope="session")
def client(ml_main):
    from fastapi.testclient import TestClient
    return TestClient(ml_main.app)


@pytest.fixture
def flight():
    """A valid /predict body (the example from the request schema)."""
    return {
        "flight_id": "AI-101", "departure_hour": 7, "day_of_week": 4, "month": 7,
        "origin_airport": "BOM", "destination_airport": "DEL", "route_distance_km": 1148,
        "weather_score": 2, "crew_duty_hours": 11.5, "historical_delay_rate": 0.41,
        "aircraft_age": 8, "gate_turnaround_mins": 25, "aircraft_type": "A320",
    }
