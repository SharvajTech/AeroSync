"""
gnn/graph_dataset.py
────────────────────────────────────────────────────────────────────────────
Builds a reproducible synthetic FLIGHT NETWORK graph, explicitly modeling
aircraft rotations, passenger/crew connections, and shared-route coupling
as graph edges — structure the tabular model (train_model.py) never sees,
since it treats every flight as an independent row.

NODES = individual flights, one synthetic day of operations across the same
        Indian-route network used by data_generator.py.

EDGES (three kinds, see build_graph() docstring for the exact rules):
  - rotation:   this flight -> the next leg flown by the SAME aircraft
  - connection: a different aircraft's flight departing the same airport
                shortly after this one arrives (passenger/crew handoff)
  - same_route: another flight on the same origin->destination pair within
                a few hours (shared congestion / OD coupling)

TARGET (cascade_risk_label) = did this flight end up delayed AND was that
tied to an upstream disruption (its rotation predecessor or a connecting
flight was ALSO delayed)? This is deliberately narrower than the tabular
model's delay_label — see the module docstring in train_gnn.py and the
README for the full distinction between "is this flight delayed" (tabular)
and "is this flight's delay linked to something upstream" (this target).

Reproducible: RANDOM_STATE=42 — the same constant used throughout
aerosync-ml — drives every random draw here via a dedicated np.random.Generator,
so re-running this script produces the same graph every time.
"""
import os
import sys
import json

import numpy as np
import pandas as pd
import joblib

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from data_generator import ROUTES, ROUTE_DELAY_RATES, AIRCRAFT_TYPES  # noqa: E402
from train_model import FEATURE_COLUMNS as TABULAR_FEATURE_COLUMNS      # noqa: E402

RANDOM_STATE = 42

# ── Graph scale (kept small on purpose — "small, understandable GNN") ───────
N_AIRCRAFT          = 550     # synthetic aircraft flying today
ROTATION_LEN_RANGE   = (2, 5)  # legs per aircraft today
CONNECTION_WINDOW_MIN = 90     # max gap for a plausible passenger/crew connection
MAX_CONNECTIONS_PER_FLIGHT = 2
OD_WINDOW_MIN        = 180     # +/- window for "same route, nearby departure"
MAX_OD_EDGES_PER_FLIGHT = 2
CRUISE_KMH            = 750    # rough average incl. taxi/climb/descent overhead
GROUND_OVERHEAD_MIN    = 25    # taxi + takeoff/landing buffer added to flight time

# Node feature order — must match model input order everywhere.
NODE_FEATURE_NAMES = [
    "delay_probability",       # this flight's own intrinsic risk (from the
                                # trained tabular ensemble, models/ensemble.pkl)
    "weather_score",
    "crew_duty_hours",
    "gate_turnaround_mins",
    "aircraft_age",
    "departure_hour",
    "historical_delay_rate",
    "incoming_delay_signal",   # mean INTRINSIC delay_probability of this
                                # flight's causal predecessors (rotation +
                                # connection) — "known in advance" upstream
                                # risk, not their realized outcome (that
                                # would leak the label — see module docstring)
    "num_connections",         # node degree across all edge types
]

AIRPORTS = sorted({o for o, d, _ in ROUTES} | {d for o, d, _ in ROUTES})


def _outbound_routes():
    by_origin = {}
    for o, d, dist in ROUTES:
        by_origin.setdefault(o, []).append((d, dist))
    return by_origin


OUTBOUND = _outbound_routes()


def _load_tabular_model():
    """Reuse the actual deployed tabular ensemble (Part 1-3) to score each
    synthetic flight's intrinsic delay risk — ties this graph dataset to
    the real trained model instead of inventing a second ad-hoc formula."""
    model_dir = os.path.join(os.path.dirname(__file__), "..", "models")
    ensemble = joblib.load(os.path.join(model_dir, "ensemble.pkl"))
    scaler   = joblib.load(os.path.join(model_dir, "scaler.pkl"))
    cols     = joblib.load(os.path.join(model_dir, "feature_columns.pkl"))
    assert cols == TABULAR_FEATURE_COLUMNS, (
        "models/feature_columns.pkl doesn't match train_model.FEATURE_COLUMNS "
        "— retrain the tabular model (train_model.py) before building the graph.")
    return ensemble, scaler, cols


AIRPORT_ENCODE = {"BOM": 0, "DEL": 1, "BLR": 2, "HYD": 3,
                   "CCU": 4, "MAA": 5, "AMD": 6, "GOI": 7, "PNQ": 8}
AIRCRAFT_ENCODE = {"A320": 0, "B737": 1, "A321": 2, "ATR72": 3, "B777": 4}


def _tabular_feature_row(leg):
    dep_hour = leg["departure_hour"]
    month = leg["month"]
    dow = leg["day_of_week"]
    crew_h = leg["crew_duty_hours"]
    turn = leg["gate_turnaround_mins"]
    age = leg["aircraft_age"]
    weather = leg["weather_score"]
    is_monsoon = 1 if month in [6, 7, 8, 9] else 0
    row = {
        "departure_hour": dep_hour, "day_of_week": dow, "month": month,
        "route_distance_km": leg["route_distance_km"], "weather_score": weather,
        "crew_duty_hours": crew_h, "historical_delay_rate": leg["historical_delay_rate"],
        "aircraft_age": age, "gate_turnaround_mins": turn,
        "origin_encoded": AIRPORT_ENCODE[leg["origin"]],
        "destination_encoded": AIRPORT_ENCODE[leg["destination"]],
        "aircraft_encoded": AIRCRAFT_ENCODE[leg["aircraft_type"]],
        "is_peak_hour": 1 if dep_hour in [7, 8, 9, 17, 18, 19, 20] else 0,
        "is_monsoon": is_monsoon,
        "is_fog_season": 1 if month in [11, 12, 1, 2] else 0,
        "is_peak_day": 1 if dow in [4, 6] else 0,
        "crew_risk": 1 if crew_h > 10 else 0,
        "tight_turnaround": 1 if turn < 30 else 0,
        "aging_aircraft": 1 if age > 18 else 0,
        "weather_monsoon": weather * is_monsoon,
    }
    return [row[c] for c in TABULAR_FEATURE_COLUMNS]


def _build_legs(rng):
    """Structural pass: build every aircraft's rotation for one synthetic
    day. Returns a list of leg dicts (no delay outcomes yet)."""
    legs = []
    flight_counter = 0

    for aircraft_id in range(N_AIRCRAFT):
        aircraft_type = rng.choice(AIRCRAFT_TYPES)
        aircraft_age = int(rng.integers(1, 22))
        month = int(rng.integers(1, 13))
        base_dow = int(rng.integers(0, 7))
        rotation_len = int(rng.integers(ROTATION_LEN_RANGE[0], ROTATION_LEN_RANGE[1] + 1))

        origin = rng.choice(AIRPORTS)
        clock = int(rng.integers(4 * 60, 22 * 60))  # first departure, minutes from midnight
        crew_duty_hours = float(rng.uniform(0.3, 1.5))  # just starting duty

        for pos in range(rotation_len):
            choices = OUTBOUND.get(origin, [])
            if not choices:
                break
            dest, base_dist = choices[rng.integers(0, len(choices))]
            distance = base_dist + int(rng.integers(-50, 50))
            delay_rate = ROUTE_DELAY_RATES[(origin, dest)] + rng.uniform(-0.05, 0.05)

            weather = int(rng.choice([0, 1, 2, 3], p=[0.55, 0.25, 0.15, 0.05]))
            if pos == 0:
                turnaround = int(rng.integers(30, 90))  # first-of-day prep
            else:
                turnaround = int(rng.integers(18, 90))

            day_of_week = (base_dow + clock // 1440) % 7
            departure_hour = (clock // 60) % 24

            leg = {
                "flight_id": f"F{flight_counter:05d}",
                "aircraft_id": aircraft_id,
                "rotation_pos": pos,
                "origin": origin, "destination": dest,
                "route_distance_km": distance,
                "weather_score": weather,
                "crew_duty_hours": round(crew_duty_hours, 2),
                "historical_delay_rate": round(float(delay_rate), 4),
                "aircraft_age": aircraft_age,
                "aircraft_type": aircraft_type,
                "gate_turnaround_mins": turnaround,
                "departure_hour": departure_hour,
                "day_of_week": day_of_week,
                "month": month,
                "departure_clock": clock,
            }
            legs.append(leg)
            flight_counter += 1

            flight_minutes = distance / CRUISE_KMH * 60 + GROUND_OVERHEAD_MIN
            arrival_clock = clock + flight_minutes
            leg["arrival_clock"] = arrival_clock

            # next leg's setup
            origin = dest
            clock = int(arrival_clock + turnaround)
            crew_duty_hours += flight_minutes / 60 + turnaround / 60
            # legal duty day limit — start a fresh rotation-day if exceeded
            if crew_duty_hours > 13:
                break

    return legs


def _build_edges(legs):
    """Adds rotation, connection, and same-route edges to the leg list.
    Returns (edge_list, edge_types) where edge_list holds *directed causal*
    pairs (predecessor -> successor) for rotation/connection, and
    undirected pairs for same_route. edge_types is a parallel list of
    'rotation' | 'connection' | 'same_route'.
    """
    by_flight = {leg["flight_id"]: leg for leg in legs}
    causal_predecessors = {leg["flight_id"]: [] for leg in legs}  # rotation + connection
    all_neighbors = {leg["flight_id"]: set() for leg in legs}
    edges = []          # (src, dst, type)

    # 1. rotation edges — consecutive legs of the same aircraft
    by_aircraft = {}
    for leg in legs:
        by_aircraft.setdefault(leg["aircraft_id"], []).append(leg)
    for aircraft_id, aircraft_legs in by_aircraft.items():
        aircraft_legs.sort(key=lambda l: l["rotation_pos"])
        for a, b in zip(aircraft_legs, aircraft_legs[1:]):
            edges.append((a["flight_id"], b["flight_id"], "rotation"))
            causal_predecessors[b["flight_id"]].append(a["flight_id"])
            all_neighbors[a["flight_id"]].add(b["flight_id"])
            all_neighbors[b["flight_id"]].add(a["flight_id"])

    # 2. connection edges — different aircraft, A arrives shortly before B departs
    by_airport_arrivals = {}
    by_airport_departures = {}
    for leg in legs:
        by_airport_arrivals.setdefault(leg["destination"], []).append(leg)
        by_airport_departures.setdefault(leg["origin"], []).append(leg)

    for airport, departures in by_airport_departures.items():
        arrivals = by_airport_arrivals.get(airport, [])
        for b in departures:
            candidates = []
            for a in arrivals:
                if a["aircraft_id"] == b["aircraft_id"]:
                    continue  # that's the rotation edge, not a connection
                gap = b["departure_clock"] - a["arrival_clock"]
                if 0 <= gap <= CONNECTION_WINDOW_MIN:
                    candidates.append((gap, a))
            candidates.sort(key=lambda t: t[0])
            for gap, a in candidates[:MAX_CONNECTIONS_PER_FLIGHT]:
                edges.append((a["flight_id"], b["flight_id"], "connection"))
                causal_predecessors[b["flight_id"]].append(a["flight_id"])
                all_neighbors[a["flight_id"]].add(b["flight_id"])
                all_neighbors[b["flight_id"]].add(a["flight_id"])

    # 3. same-route edges — undirected, contextual only (NOT causal)
    by_route = {}
    for leg in legs:
        by_route.setdefault((leg["origin"], leg["destination"]), []).append(leg)
    seen_pairs = set()
    for route, route_legs in by_route.items():
        route_legs.sort(key=lambda l: l["departure_clock"])
        for i, leg in enumerate(route_legs):
            candidates = []
            for other in route_legs:
                if other["flight_id"] == leg["flight_id"]:
                    continue
                if other["aircraft_id"] == leg["aircraft_id"]:
                    continue
                gap = abs(other["departure_clock"] - leg["departure_clock"])
                if gap <= OD_WINDOW_MIN:
                    candidates.append((gap, other))
            candidates.sort(key=lambda t: t[0])
            for gap, other in candidates[:MAX_OD_EDGES_PER_FLIGHT]:
                pair = tuple(sorted([leg["flight_id"], other["flight_id"]]))
                if pair in seen_pairs:
                    continue
                seen_pairs.add(pair)
                edges.append((leg["flight_id"], other["flight_id"], "same_route"))
                all_neighbors[leg["flight_id"]].add(other["flight_id"])
                all_neighbors[other["flight_id"]].add(leg["flight_id"])

    return edges, causal_predecessors, all_neighbors


def build_graph_dataset(seed=RANDOM_STATE, verbose=True):
    rng = np.random.default_rng(seed)
    legs = _build_legs(rng)
    by_flight = {leg["flight_id"]: leg for leg in legs}

    ensemble, scaler, cols = _load_tabular_model()
    X = np.array([_tabular_feature_row(leg) for leg in legs])
    X_scaled = scaler.transform(X)
    intrinsic_prob = ensemble.predict_proba(X_scaled)[:, 1]
    for leg, p in zip(legs, intrinsic_prob):
        leg["intrinsic_delay_probability"] = float(p)

    edges, causal_predecessors, all_neighbors = _build_edges(legs)

    # ── Cascade label generation — strict chronological (causal) order ──
    UPSTREAM_BOOST_TIGHT  = 0.35
    UPSTREAM_BOOST_NORMAL = 0.15
    ordered = sorted(legs, key=lambda l: l["departure_clock"])
    for leg in ordered:
        preds = causal_predecessors[leg["flight_id"]]
        has_upstream_disruption = any(
            by_flight[p]["realized_delayed"] for p in preds
            if "realized_delayed" in by_flight[p]
        ) if preds else False

        tight = leg["gate_turnaround_mins"] < 30
        boost = 0.0
        if has_upstream_disruption:
            boost = UPSTREAM_BOOST_TIGHT if tight else UPSTREAM_BOOST_NORMAL

        final_prob = min(1.0, 0.72 * leg["intrinsic_delay_probability"] + boost)
        noise = rng.uniform(-0.05, 0.05)
        realized = int((final_prob + noise) >= 0.5)

        leg["has_upstream_disruption"] = int(has_upstream_disruption)
        leg["realized_delayed"] = realized
        leg["cascade_risk_label"] = int(realized == 1 and has_upstream_disruption)

        preds_intrinsic = [by_flight[p]["intrinsic_delay_probability"] for p in preds]
        leg["incoming_delay_signal"] = float(np.mean(preds_intrinsic)) if preds_intrinsic else 0.0

    for leg in legs:
        leg["num_connections"] = len(all_neighbors[leg["flight_id"]])

    # ── Assemble arrays ──────────────────────────────────────────────────
    flight_ids = [leg["flight_id"] for leg in legs]
    id_to_idx = {fid: i for i, fid in enumerate(flight_ids)}
    N = len(legs)

    node_features = np.zeros((N, len(NODE_FEATURE_NAMES)), dtype=np.float32)
    labels = np.zeros(N, dtype=np.int64)
    for i, leg in enumerate(legs):
        node_features[i] = [
            leg["intrinsic_delay_probability"],
            leg["weather_score"],
            leg["crew_duty_hours"],
            leg["gate_turnaround_mins"],
            leg["aircraft_age"],
            leg["departure_hour"],
            leg["historical_delay_rate"],
            leg["incoming_delay_signal"],
            leg["num_connections"],
        ]
        labels[i] = leg["cascade_risk_label"]

    src, dst, etypes = [], [], []
    type_code = {"rotation": 0, "connection": 1, "same_route": 2}
    for a, b, t in edges:
        # bidirectional for GNN message passing — see module docstring
        src += [id_to_idx[a], id_to_idx[b]]
        dst += [id_to_idx[b], id_to_idx[a]]
        etypes += [type_code[t], type_code[t]]
    edge_index = np.array([src, dst], dtype=np.int64)
    edge_type = np.array(etypes, dtype=np.int64)

    meta_df = pd.DataFrame(legs)

    if verbose:
        n_rotation = sum(1 for _, _, t in edges if t == "rotation")
        n_conn     = sum(1 for _, _, t in edges if t == "connection")
        n_route    = sum(1 for _, _, t in edges if t == "same_route")
        print(f"Nodes (flights)      : {N}")
        print(f"Edges (directed pairs, pre-bidirectional): "
              f"{len(edges)}  (rotation={n_rotation}, connection={n_conn}, "
              f"same_route={n_route})")
        print(f"Edge index shape (bidirectional): {edge_index.shape}")
        print(f"cascade_risk_label positive rate: {labels.mean()*100:.1f}% "
              f"({labels.sum()} / {N})")
        print(f"realized_delayed positive rate  : "
              f"{meta_df['realized_delayed'].mean()*100:.1f}%")
        print(f"Mean node degree                : {2*len(edges)/N:.2f}")

    return {
        "node_features": node_features,
        "labels": labels,
        "edge_index": edge_index,
        "edge_type": edge_type,
        "flight_ids": flight_ids,
        "feature_names": NODE_FEATURE_NAMES,
        "meta": meta_df,
    }


def save_dataset(dataset, out_dir=None):
    out_dir = out_dir or os.path.join(os.path.dirname(__file__), "data")
    os.makedirs(out_dir, exist_ok=True)
    np.savez(
        os.path.join(out_dir, "flight_graph.npz"),
        node_features=dataset["node_features"],
        labels=dataset["labels"],
        edge_index=dataset["edge_index"],
        edge_type=dataset["edge_type"],
    )
    dataset["meta"].to_csv(os.path.join(out_dir, "flight_graph_meta.csv"), index=False)
    with open(os.path.join(out_dir, "feature_names.json"), "w") as f:
        json.dump(dataset["feature_names"], f, indent=2)
    print(f"  ✅ {out_dir}/flight_graph.npz")
    print(f"  ✅ {out_dir}/flight_graph_meta.csv")
    print(f"  ✅ {out_dir}/feature_names.json")


def load_dataset(data_dir=None):
    data_dir = data_dir or os.path.join(os.path.dirname(__file__), "data")
    npz = np.load(os.path.join(data_dir, "flight_graph.npz"))
    with open(os.path.join(data_dir, "feature_names.json")) as f:
        feature_names = json.load(f)
    return {
        "node_features": npz["node_features"],
        "labels": npz["labels"],
        "edge_index": npz["edge_index"],
        "edge_type": npz["edge_type"],
        "feature_names": feature_names,
    }


if __name__ == "__main__":
    print("Building synthetic flight-network graph (seed="
          f"{RANDOM_STATE})...")
    ds = build_graph_dataset()
    save_dataset(ds)
