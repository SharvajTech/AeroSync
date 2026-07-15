import requests
import os
from datetime import datetime

# Indian airport ICAO → city name for OpenWeatherMap
AIRPORT_CITIES = {
    "BOM": "Mumbai,IN",
    "DEL": "Delhi,IN",
    "BLR": "Bangalore,IN",
    "HYD": "Hyderabad,IN",
    "CCU": "Kolkata,IN",
    "MAA": "Chennai,IN",
    "AMD": "Ahmedabad,IN",
    "GOI": "Goa,IN",
    "PNQ": "Pune,IN",
}

# Cache weather to avoid hitting API on every prediction
# Refresh every 30 minutes
_weather_cache = {}
_cache_timestamp = {}
CACHE_TTL_SECONDS = 1800  # 30 minutes


def get_weather_score(airport_code: str,
                      api_key: str) -> dict:
    """
    Fetch real weather for airport and convert to 0-3 score.
    Uses OpenWeatherMap free tier.
    Returns cached result if fresh enough.
    """
    now = datetime.now().timestamp()
    cached_time = _cache_timestamp.get(airport_code, 0)

    # Return cached if still fresh
    if (now - cached_time) < CACHE_TTL_SECONDS \
            and airport_code in _weather_cache:
        return _weather_cache[airport_code]

    city = AIRPORT_CITIES.get(airport_code.upper())
    if not city:
        return _fallback_weather(airport_code)

    try:
        url = "https://api.openweathermap.org/data/2.5/weather"
        params = {
            "q":     city,
            "appid": api_key,
            "units": "metric"
        }
        response = requests.get(url, params=params, timeout=10)

        if response.status_code != 200:
            print(f"Weather API error for {airport_code}: "
                  f"{response.status_code}")
            return _fallback_weather(airport_code)

        data   = response.json()
        result = _parse_weather(data, airport_code)

        # Cache the result
        _weather_cache[airport_code]    = result
        _cache_timestamp[airport_code]  = now

        return result

    except Exception as e:
        print(f"Weather fetch failed for {airport_code}: {e}")
        return _fallback_weather(airport_code)


def _parse_weather(data: dict, airport_code: str) -> dict:
    """
    Convert OpenWeatherMap response to AeroSync weather score.
    Score 0 = clear, 1 = light, 2 = moderate, 3 = severe
    """
    weather_id   = data.get("weather", [{}])[0].get("id", 800)
    wind_speed   = data.get("wind", {}).get("speed", 0)     # m/s
    visibility   = data.get("visibility", 10000)              # metres
    rain_1h      = data.get("rain",  {}).get("1h", 0)        # mm
    snow_1h      = data.get("snow",  {}).get("1h", 0)        # mm
    humidity     = data.get("main",  {}).get("humidity", 50)
    description  = data.get("weather", [{}])[0].get(
        "description", "clear")

    score = 0

    # Thunderstorm (200-232) — always severe
    if 200 <= weather_id <= 232:
        score = 3

    # Heavy rain (502-504, 522) — severe
    elif weather_id in [502, 503, 504, 522]:
        score = 3

    # Moderate rain (501) or heavy drizzle — moderate
    elif weather_id in [501, 311, 312, 313, 314]:
        score = 2

    # Light rain (500) or drizzle (300-321) — light
    elif 300 <= weather_id <= 321 or weather_id == 500:
        score = 1

    # Snow (600-622)
    elif 600 <= weather_id <= 622:
        score = 2 if snow_1h > 1 else 1

    # Fog/mist (741, 701) — critical for airports
    elif weather_id in [741, 701, 711, 721]:
        score = 2

    # High wind regardless of precipitation
    if wind_speed > 15:   # > 54 km/h
        score = max(score, 2)
    elif wind_speed > 10:  # > 36 km/h
        score = max(score, 1)

    # Low visibility
    if visibility < 1000:
        score = max(score, 3)
    elif visibility < 3000:
        score = max(score, 2)
    elif visibility < 5000:
        score = max(score, 1)

    return {
        "airport_code":   airport_code,
        "weather_score":  min(score, 3),
        "description":    description,
        "wind_speed_ms":  round(wind_speed, 1),
        "visibility_m":   visibility,
        "rain_1h_mm":     rain_1h,
        "humidity_pct":   humidity,
        "raw_weather_id": weather_id,
        "is_live":        True
    }


def _fallback_weather(airport_code: str) -> dict:
    """Return safe default when API unavailable."""
    return {
        "airport_code":  airport_code,
        "weather_score": 1,
        "description":   "data unavailable",
        "is_live":       False
    }


def get_all_airports_weather(api_key: str) -> dict:
    """Fetch weather for all 4 main airports at once."""
    results = {}
    for airport in ["BOM", "DEL", "BLR", "HYD"]:
        results[airport] = get_weather_score(airport, api_key)
    return results


if __name__ == "__main__":
    # Test — replace with your key
    key = os.environ.get("WEATHER_API_KEY", "your_key_here")
    weather = get_all_airports_weather(key)
    for airport, data in weather.items():
        print(f"{airport}: score={data['weather_score']} "
              f"({data['description']})")