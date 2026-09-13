import requests
import pandas as pd
import time
import os
from datetime import datetime, timedelta

INDIAN_AIRPORTS = {
    "BOM": "VABB",
    "DEL": "VIDP",
    "BLR": "VOBL",
    "HYD": "VOHY",
}

# OpenSky credentials — register free at opensky-network.org
# Without credentials, only live states work (not historical)
OPENSKY_USERNAME = "sharvaj02"  # leave blank for anonymous
OPENSKY_PASSWORD = ""


def fetch_live_states(icao_code: str) -> list:
    """
    Fetch LIVE flight states over Indian airports.
    This endpoint works without authentication.
    Bounding boxes for Indian airports.
    """
    AIRPORT_BBOX = {
        "VABB": (18.9, 72.8, 19.2, 73.1),   # Mumbai
        "VIDP": (28.5, 77.0, 28.7, 77.2),   # Delhi
        "VOBL": (12.9, 77.6, 13.2, 77.8),   # Bangalore
        "VOHY": (17.2, 78.4, 17.5, 78.6),   # Hyderabad
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

    auth = None
    if OPENSKY_USERNAME and OPENSKY_PASSWORD:
        auth = (OPENSKY_USERNAME, OPENSKY_PASSWORD)

    try:
        response = requests.get(
            url, params=params, auth=auth, timeout=15)

        if response.status_code == 200:
            data = response.json()
            states = data.get("states", []) or []
            print(f"  {icao_code}: {len(states)} live aircraft")
            return states
        elif response.status_code == 429:
            print(f"  {icao_code}: rate limited — waiting 30s")
            time.sleep(30)
            return []
        else:
            print(f"  {icao_code}: HTTP {response.status_code}")
            return []
    except Exception as e:
        print(f"  {icao_code}: {e}")
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
            })
        except Exception:
            continue

    return pd.DataFrame(records)


def fetch_historical_with_auth(
        icao_code: str, days_back: int = 7) -> list:
    """
    Historical data — only works with registered account.
    Register free at: opensky-network.org/index.php?option=com_users&view=registration
    """
    if not OPENSKY_USERNAME:
        print(f"  {icao_code}: skipping historical "
              f"(no credentials)")
        return []

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
            url, params=params,
            auth=(OPENSKY_USERNAME, OPENSKY_PASSWORD),
            timeout=30)

        if response.status_code == 200:
            data = response.json() or []
            print(f"  {icao_code}: {len(data)} historical flights")
            return data
        else:
            print(f"  {icao_code}: HTTP {response.status_code}")
            return []
    except Exception as e:
        print(f"  {icao_code}: {e}")
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
        historical = fetch_historical_with_auth(icao, days_back=7)

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
            states = fetch_live_states(icao)
            df = build_features_from_states(states, iata)
            if not df.empty:
                frames.append(df)

        time.sleep(5)  # respect rate limit

    if not frames:
        print("No OpenSky data fetched — will use synthetic only")
        print("Tip: Register at opensky-network.org for "
              "historical data access")
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
        print("    Register at opensky-network.org for full access")
