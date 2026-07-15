import pandas as pd
import numpy as np
import os

# ── Reproducibility ──────────────────────────────────────────────────────────
np.random.seed(42)
NUM_RECORDS = 10000

# ── Indian airport routes (realistic) ────────────────────────────────────────
ROUTES = [
    ("BOM", "DEL", 1148), ("DEL", "BOM", 1148),
    ("BOM", "BLR", 845),  ("BLR", "BOM", 845),
    ("DEL", "BLR", 1740), ("BLR", "DEL", 1740),
    ("BOM", "HYD", 620),  ("HYD", "BOM", 620),
    ("DEL", "CCU", 1305), ("CCU", "DEL", 1305),
    ("BOM", "MAA", 1015), ("MAA", "BOM", 1015),
    ("DEL", "AMD", 890),  ("AMD", "DEL", 890),
    ("BLR", "HYD", 500),  ("HYD", "BLR", 500),
    ("DEL", "GOI", 1900), ("GOI", "DEL", 1900),
    ("BOM", "PNQ", 120),  ("PNQ", "BOM", 120),
]

# ── Historical delay rates per route (based on real patterns) ─────────────
ROUTE_DELAY_RATES = {
    ("BOM", "DEL"): 0.38, ("DEL", "BOM"): 0.35,
    ("BOM", "BLR"): 0.28, ("BLR", "BOM"): 0.30,
    ("DEL", "BLR"): 0.42, ("BLR", "DEL"): 0.40,
    ("BOM", "HYD"): 0.25, ("HYD", "BOM"): 0.27,
    ("DEL", "CCU"): 0.45, ("CCU", "DEL"): 0.43,
    ("BOM", "MAA"): 0.32, ("MAA", "BOM"): 0.30,
    ("DEL", "AMD"): 0.20, ("AMD", "DEL"): 0.22,
    ("BLR", "HYD"): 0.18, ("HYD", "BLR"): 0.19,
    ("DEL", "GOI"): 0.35, ("GOI", "DEL"): 0.33,
    ("BOM", "PNQ"): 0.15, ("PNQ", "BOM"): 0.14,
}

AIRCRAFT_TYPES = ["A320", "B737", "A321", "ATR72", "B777"]

# ── Helper: pick a random route ───────────────────────────────────────────────
def random_route():
    idx = np.random.randint(0, len(ROUTES))
    origin, destination, distance = ROUTES[idx]
    delay_rate = ROUTE_DELAY_RATES[(origin, destination)]
    return origin, destination, distance, delay_rate

# ── Core delay probability formula ───────────────────────────────────────────
# This mirrors real-world delay drivers — do not change the weights
# without retraining the model
def compute_delay_probability(row):
    prob = 0.0

    # Weather is the strongest driver
    prob += row["weather_score"] * 0.20

    # Peak hours increase delay probability
    if row["departure_hour"] in [7, 8, 9, 17, 18, 19, 20]:
        prob += 0.15

    # Weekend flights are slightly more delayed
    if row["day_of_week"] in [5, 6]:
        prob += 0.05

    # Monsoon months (June–September)
    if row["month"] in [6, 7, 8, 9]:
        prob += 0.10

    # Crew duty hours — above 10 hours is risky
    if row["crew_duty_hours"] > 10:
        prob += 0.20
    elif row["crew_duty_hours"] > 8:
        prob += 0.10

    # Historical route delay rate is a strong predictor
    prob += row["historical_delay_rate"] * 0.40

    # Older aircraft fail more
    if row["aircraft_age"] > 15:
        prob += 0.10
    elif row["aircraft_age"] > 10:
        prob += 0.05

    # Tight gate turnaround increases delay
    if row["gate_turnaround_mins"] < 30:
        prob += 0.15
    elif row["gate_turnaround_mins"] < 45:
        prob += 0.07

    # Short routes have less buffer time
    if row["route_distance_km"] < 300:
        prob += 0.05

    return min(prob, 1.0)


# ── Generate dataset ──────────────────────────────────────────────────────────
def generate_data():
    records = []

    for _ in range(NUM_RECORDS):
        origin, destination, distance, delay_rate = random_route()

        row = {
            # Time features
            "departure_hour":       np.random.randint(0, 24),
            "day_of_week":          np.random.randint(0, 7),
            "month":                np.random.randint(1, 13),

            # Route features
            "origin_airport":       origin,
            "destination_airport":  destination,
            "route_distance_km":    distance + np.random.randint(-50, 50),

            # Operational features
            "weather_score":        np.random.choice([0, 1, 2, 3],
                                        p=[0.55, 0.25, 0.15, 0.05]),
            "crew_duty_hours":      round(np.random.uniform(1, 14), 1),
            "historical_delay_rate": delay_rate + np.random.uniform(-0.05, 0.05),
            "aircraft_age":         np.random.randint(1, 22),
            "gate_turnaround_mins": np.random.randint(20, 90),
            "aircraft_type":        np.random.choice(AIRCRAFT_TYPES),
        }

        # Compute probability then assign binary label with noise
        delay_prob = compute_delay_probability(row)
        # Add small noise so the dataset is not perfectly clean
        # This makes the ML model more realistic
        noise = np.random.uniform(-0.05, 0.05)
        row["delay_label"] = int((delay_prob + noise) >= 0.50)
        row["delay_probability"] = round(delay_prob, 4)

        records.append(row)

    df = pd.DataFrame(records)
    return df


# ── Encode categorical columns ─────────────────────────────────────────────
def encode_categoricals(df):
    airport_map = {
        "BOM": 0, "DEL": 1, "BLR": 2, "HYD": 3,
        "CCU": 4, "MAA": 5, "AMD": 6, "GOI": 7, "PNQ": 8
    }
    aircraft_map = {
        "A320": 0, "B737": 1, "A321": 2, "ATR72": 3, "B777": 4
    }

    df["origin_encoded"]      = df["origin_airport"].map(airport_map)
    df["destination_encoded"] = df["destination_airport"].map(airport_map)
    df["aircraft_encoded"]    = df["aircraft_type"].map(aircraft_map)

    return df


# ── Save to CSV ───────────────────────────────────────────────────────────────
def save_data(df):
    os.makedirs("data", exist_ok=True)
    df.to_csv("data/flight_data.csv", index=False)
    print(f"Dataset saved → data/flight_data.csv")
    print(f"Total records  : {len(df)}")
    print(f"Delayed flights: {df['delay_label'].sum()} "
          f"({df['delay_label'].mean()*100:.1f}%)")
    print(f"On-time flights: {(df['delay_label']==0).sum()} "
          f"({(df['delay_label']==0).mean()*100:.1f}%)")
    print(f"\nFeature columns:\n{list(df.columns)}")
    print(f"\nSample record:\n{df.iloc[0].to_dict()}")

def engineer_features(df):
    """Add derived features — must match train_model.py FEATURE_COLUMNS."""
    df["is_peak_hour"]    = df["departure_hour"].apply(
        lambda h: 1 if h in [7, 8, 9, 17, 18, 19, 20] else 0)
    df["is_monsoon"]      = df["month"].apply(
        lambda m: 1 if m in [6, 7, 8, 9] else 0)
    df["is_fog_season"]   = df["month"].apply(
        lambda m: 1 if m in [11, 12, 1, 2] else 0)
    df["is_peak_day"]     = df["day_of_week"].apply(
        lambda d: 1 if d in [4, 6] else 0)
    df["crew_risk"]       = df["crew_duty_hours"].apply(
        lambda h: 1 if h > 10 else 0)
    df["tight_turnaround"] = df["gate_turnaround_mins"].apply(
        lambda m: 1 if m < 30 else 0)
    df["aging_aircraft"]  = df["aircraft_age"].apply(
        lambda a: 1 if a > 18 else 0)
    df["weather_monsoon"] = df["weather_score"] * df["is_monsoon"]
    df["risk_score"]      = (
        df["weather_score"]          * 0.25 +
        df["historical_delay_rate"]  * 0.35 +
        df["crew_risk"]              * 0.15 +
        df["tight_turnaround"]       * 0.10 +
        df["is_fog_season"]          * 0.08 +
        df["is_peak_day"]            * 0.07
    )
    return df


def merge_with_real_data(synthetic_df):
    """Merge with real OpenSky data if available."""
    real_path = "data/real_flights.csv"
    import os
    if not os.path.exists(real_path):
        print("No real data found — using synthetic only")
        return synthetic_df

    real_df = pd.read_csv(real_path)
    if real_df.empty:
        return synthetic_df

    print(f"Merging {len(synthetic_df)} synthetic "
          f"+ {len(real_df)} real records")

    import numpy as np
    real_df['weather_score'] = np.random.choice(
        [0, 1, 2, 3], size=len(real_df), p=[0.55, 0.25, 0.15, 0.05])
    real_df['crew_duty_hours'] = np.random.uniform(
        1, 14, size=len(real_df)).round(1)
    real_df['aircraft_age'] = np.random.randint(1, 22, size=len(real_df))
    real_df['gate_turnaround_mins'] = np.random.randint(
        20, 90, size=len(real_df))
    real_df['route_distance_km'] = np.random.randint(
        400, 1800, size=len(real_df)).astype(float)
    real_df['historical_delay_rate'] = np.random.uniform(
        0.15, 0.48, size=len(real_df)).round(3)
    real_df['aircraft_type'] = np.random.choice(
        ["A320","B737","A321","ATR72","B777"], size=len(real_df))

    # Map origin/destination
    real_df['origin_airport'] = real_df.get(
        'origin', pd.Series(['BOM'] * len(real_df)))
    real_df['destination_airport'] = real_df.get(
        'destination', pd.Series(['DEL'] * len(real_df)))

    # Keep only compatible columns
    keep_cols = [
        'departure_hour', 'day_of_week', 'month',
        'origin_airport', 'destination_airport',
        'route_distance_km', 'weather_score', 'crew_duty_hours',
        'historical_delay_rate', 'aircraft_age',
        'gate_turnaround_mins', 'aircraft_type'
    ]
    available = [c for c in keep_cols if c in real_df.columns]
    real_df = real_df[available].dropna()

    combined = pd.concat(
        [synthetic_df, real_df], ignore_index=True)
    print(f"Combined: {len(combined)} records")
    return combined

# ── Entry point ───────────────────────────────────────────────────────────────
if __name__ == "__main__":
    import os
    os.makedirs("data", exist_ok=True)

    print("Step 1: Generating synthetic data...")
    df = generate_data()

    print("Step 2: Merging with real data if available...")
    df = merge_with_real_data(df)

    print("Step 3: Encoding categoricals...")
    df = encode_categoricals(df)

    print("Step 4: Engineering features...")
    df = engineer_features(df)

    print("Step 5: Computing labels...")
    # Labels already computed in generate_data() for synthetic
    # For real data rows add labels
    if 'delay_label' not in df.columns:
        df['delay_label'] = 0

    df.to_csv("data/flight_data.csv", index=False)

    # Verify all required columns
    required = [
        'is_peak_hour', 'is_monsoon', 'is_fog_season',
        'is_peak_day', 'crew_risk', 'tight_turnaround',
        'aging_aircraft', 'weather_monsoon', 'risk_score'
    ]
    missing = [c for c in required if c not in df.columns]
    if missing:
        print(f"❌ Still missing: {missing}")
    else:
        print(f"✅ All features present ({len(df.columns)} columns)")
        print(f"   Records: {len(df)}")
        print(f"   Delayed: {df['delay_label'].mean()*100:.1f}%")
    print("\nRun train_model.py next.")