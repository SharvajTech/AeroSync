import requests
import pandas as pd
import time
import os
from datetime import datetime, timedelta

INDIAN_AIRPORTS = {
    "BOM": "VABB",
    "DEL": "VIDP",
    "BLR": "VOBL",
    "HYD": "VOHS",   # Rajiv Gandhi International (VOHY is the old Begumpet airport)
}

# OpenSky accepts only OAuth2 client credentials (basic auth with a username
# and password is no longer accepted). Create an API client on your OpenSky
# account page and pass its id and secret through the environment, never in
# this file:
#   OPENSKY_CLIENT_ID=...  OPENSKY_CLIENT_SECRET=...  python data/opensky_fetcher.py
# Without them only anonymous live states are fetched (400 credits a day).
OPENSKY_CLIENT_ID = os.environ.get("OPENSKY_CLIENT_ID", "")
OPENSKY_CLIENT_SECRET = os.environ.get("OPENSKY_CLIENT_SECRET", "")
OPENSKY_TOKEN_URL = os.environ.get(
    "OPENSKY_TOKEN_URL",
    "https://auth.opensky-network.org/auth/realms/opensky-network/protocol/openid-connect/token")

_token = {"value": None, "expires_at": 0.0}


def _auth_headers() -> dict:
    """Bearer header for OpenSky, or {} for anonymous access.

    Tokens last about 30 minutes; one is reused until shortly before it
    expires. The secret and the token are never printed."""
    if not (OPENSKY_CLIENT_ID and OPENSKY_CLIENT_SECRET):
        return {}
    if _token["value"] and time.time() < _token["expires_at"] - 60:
        return {"Authorization": f"Bearer {_token['value']}"}
    response = requests.post(
        OPENSKY_TOKEN_URL,
        data={"grant_type": "client_credentials",
              "client_id": OPENSKY_CLIENT_ID,
              "client_secret": OPENSKY_CLIENT_SECRET},
        timeout=15)
    if response.status_code != 200:
        raise RuntimeError(f"OpenSky token request failed: HTTP {response.status_code}")
    body = response.json()
    _token["value"] = body["access_token"]
    _token["expires_at"] = time.time() + float(body.get("expires_in", 1800))
    return {"Authorization": f"Bearer {_token['value']}"}


def _rate_limited(response, icao_code: str) -> bool:
    """True on HTTP 429. OpenSky says how long to wait; this script stops
    instead of retrying so it never spends more credits than allowed."""
    if response.status_code != 429:
        return False
    wait = response.headers.get("X-Rate-Limit-Retry-After-Seconds", "unknown")
    print(f"  {icao_code}: OpenSky credit limit reached (retry after {wait} s) — stopping")
    return True


class RateLimited(Exception):
    pass


def fetch_live_states(icao_code: str) -> list:
    """
    Fetch LIVE flight states over Indian airports.
    Works anonymously; with client credentials the authenticated quota applies.
    Bounding boxes for Indian airports.
    """
    AIRPORT_BBOX = {
        "VABB": (18.9, 72.8, 19.2, 73.1),   # Mumbai
        "VIDP": (28.5, 77.0, 28.7, 77.2),   # Delhi
        "VOBL": (12.9, 77.6, 13.2, 77.8),   # Bangalore
        "VOHS": (17.1, 78.3, 17.4, 78.6),   # Hyderabad (Rajiv Gandhi Intl)
    }

    bbox = AIRPORT_BBOX.get(icao_code)
    if not bbox:
        return []

    url = "https://opensky-network.org/api/states/all"
    params = {
        "lamin": bbox[0],
        "lomin": bbox[1],
        "lamax": bbox[2],
        "lomax": bbox[3],
    }

    try:
        response = requests.get(
            url, params=params, headers=_auth_headers(), timeout=15)

        if response.status_code == 200:
            data = response.json()
            states = data.get("states", []) or []
            print(f"  {icao_code}: {len(states)} live aircraft")
            return states
        if _rate_limited(response, icao_code):
            raise RateLimited()
        print(f"  {icao_code}: HTTP {response.status_code}")
        return []
    except RateLimited:
        raise
    except Exception as e:
        print(f"  {icao_code}: {type(e).__name__}")
        return []


def build_features_from_states(
        states: list, airport_iata: str) -> pd.DataFrame:
    """
    Convert live state vectors to ML-ready features.
    OpenSky state vector fields:
    [icao24, callsign, origin_country, time_position,
     last_contact, longitude, latitude, baro_altitude,
     on_ground, velocity, true_track, vertical_rate,
     sensors, geo_altitude, squawk, spi, position_source]
    """
    if not states:
        return pd.DataFrame()

    records = []
    now = datetime.now()

    for state in states:
        try:
            if not isinstance(state, list) or len(state) < 9:
                continue

            callsign  = (state[1] or "").strip()
            on_ground = state[8]
            velocity  = state[9] or 0
            altitude  = state[7] or 0

            # Only include airborne aircraft
            if on_ground or altitude < 100:
                continue

            if not callsign:
                continue

            records.append({
                "flight_id":      callsign,
                "origin":         airport_iata,
                "destination":    "UNK",
                "departure_hour": now.hour,
                "day_of_week":    now.weekday(),
                "month":          now.month,
                "velocity_ms":    round(float(velocity), 1),
                "altitude_m":     round(float(altitude), 0),
                "data_source":    "opensky_live",
                # Live states carry no schedule: the time fields are the
                # time of the fetch, not the departure time.
                "time_basis":     "fetch_time",
            })
        except Exception:
            continue

    return pd.DataFrame(records)


def fetch_historical_with_auth(
        icao_code: str, days_back: int = 2) -> list:
    """
    Departures of the past days (OpenSky's nightly batch, so up to
    yesterday). Needs client credentials. The documented maximum interval
    for flight queries is two days, and each query costs up to 30 credits.
    """
    if not (OPENSKY_CLIENT_ID and OPENSKY_CLIENT_SECRET):
        print(f"  {icao_code}: skipping historical "
              f"(no OPENSKY_CLIENT_ID/OPENSKY_CLIENT_SECRET)")
        return []

    days_back = max(1, min(days_back, 2))
    end_time   = int(datetime.now().timestamp())
    begin_time = int(
        (datetime.now() - timedelta(days=days_back)).timestamp())

    url = "https://opensky-network.org/api/flights/departure"
    params = {
        "airport": icao_code,
        "begin":   begin_time,
        "end":     end_time
    }

    try:
        response = requests.get(
            url, params=params, headers=_auth_headers(), timeout=30)

        if response.status_code == 200:
            data = response.json() or []
            print(f"  {icao_code}: {len(data)} historical flights")
            return data
        if _rate_limited(response, icao_code):
            raise RateLimited()
        print(f"  {icao_code}: HTTP {response.status_code}")
        return []
    except RateLimited:
        raise
    except Exception as e:
        print(f"  {icao_code}: {type(e).__name__}")
        return []


def fetch_all_indian_airports() -> pd.DataFrame:
    """
    Try historical first (needs auth), fall back to live states.
    """
    print("Fetching from OpenSky Network...")
    frames = []

    for iata, icao in INDIAN_AIRPORTS.items():
        print(f"  Trying {iata} ({icao})...")

        # Try historical (needs credentials)
        try:
            historical = fetch_historical_with_auth(icao, days_back=2)
        except RateLimited:
            break

        if historical:
            # Process historical data
            icao_to_iata = {v: k for k, v in INDIAN_AIRPORTS.items()}
            records = []
            for f in historical:
                try:
                    first_seen = f.get("firstSeen", 0)
                    if not first_seen:
                        continue
                    dt   = datetime.fromtimestamp(first_seen)
                    dest = icao_to_iata.get(
                        f.get("estArrivalAirport", ""), "UNK")
                    records.append({
                        "flight_id":      (f.get("callsign") or "").strip(),
                        "origin":         iata,
                        "destination":    dest,
                        "departure_hour": dt.hour,
                        "day_of_week":    dt.weekday(),
                        "month":          dt.month,
                        "data_source":    "opensky_historical",
                    })
                except Exception:
                    continue
            if records:
                frames.append(pd.DataFrame(records))
        else:
            # Fall back to live states
            try:
                states = fetch_live_states(icao)
            except RateLimited:
                break
            df = build_features_from_states(states, iata)
            if not df.empty:
                frames.append(df)

        time.sleep(5)  # respect rate limit

    if not frames:
        print("No OpenSky data fetched — will use synthetic only")
        print("Tip: create an OpenSky API client and set "
              "OPENSKY_CLIENT_ID / OPENSKY_CLIENT_SECRET for historical data")
        return pd.DataFrame()

    combined = pd.concat(frames, ignore_index=True)
    print(f"Total OpenSky records: {len(combined)}")
    return combined


if __name__ == "__main__":
    os.makedirs("data", exist_ok=True)
    df = fetch_all_indian_airports()

    if not df.empty:
        df.to_csv("data/real_flights.csv", index=False)
        print(f"✅ Saved {len(df)} records → data/real_flights.csv")
        print(df[["origin", "departure_hour",
                  "data_source"]].head(10))
    else:
        print("⚠️  No real data — synthetic data will be used")
        print("    Set OPENSKY_CLIENT_ID / OPENSKY_CLIENT_SECRET for full access")