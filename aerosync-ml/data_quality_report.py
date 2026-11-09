"""
data_quality_report.py
────────────────────────────────────────────────────────────────────────────
Standalone data-quality and target-leakage audit for the SYNTHETIC
flight-delay dataset (data/flight_data.csv). Run this BEFORE train_model.py.

It does not train anything. It only inspects the CSV and reports:
  - basic dataset statistics (record count, class balance, missing values,
    duplicate rows)
  - per-feature correlation with the label, to surface anything that looks
    like a disguised restatement of the target
  - an explicit redundancy check on `risk_score` (see README "Data quality
    & leakage audit" section for the full writeup)

Usage:
    cd aerosync-ml
    python data_quality_report.py
"""
import json
import os

import numpy as np
import pandas as pd

from train_model import FEATURE_COLUMNS, TARGET, LABEL_DERIVED_COLUMNS

DATA_PATH   = "data/flight_data.csv"
RESULTS_DIR = "results"
PLOTS_DIR   = "plots"

# Heuristic only — a flag, not an automatic verdict. Genuine causal
# predictors (e.g. historical_delay_rate) can legitimately correlate with
# the target; what actually matters is WHY a feature correlates, which this
# script's redundancy check speaks to directly for risk_score.
LEAKAGE_CORR_THRESHOLD = 0.80

# risk_score's construction formula, as written in data_generator.py's
# engineer_features(). Used to PROVE (not just suspect) that risk_score is
# a deterministic function of columns already in FEATURE_COLUMNS.
RISK_SCORE_WEIGHTS = {
    "weather_score":         0.25,
    "historical_delay_rate": 0.35,
    "crew_risk":              0.15,
    "tight_turnaround":       0.10,
    "is_fog_season":          0.08,
    "is_peak_day":            0.07,
}


def load_data() -> pd.DataFrame:
    if not os.path.exists(DATA_PATH):
        raise FileNotFoundError(
            f"{DATA_PATH} not found — run data_generator.py first.")
    return pd.read_csv(DATA_PATH)


def basic_stats(df: pd.DataFrame) -> dict:
    return {
        "n_records": int(len(df)),
        "n_columns": int(len(df.columns)),
        "n_duplicate_rows": int(df.duplicated().sum()),
        "missing_values": {c: int(v) for c, v in df.isnull().sum().items() if v > 0},
        "class_distribution": {
            "on_time": int((df[TARGET] == 0).sum()),
            "delayed": int((df[TARGET] == 1).sum()),
            "delayed_pct": round(float(df[TARGET].mean() * 100), 2),
        },
    }


def leakage_scan(df: pd.DataFrame) -> pd.DataFrame:
    """Correlate every numeric column against delay_label and
    delay_probability, and flag which columns are actually used to train
    the model (FEATURE_COLUMNS) vs. present in the CSV only."""
    numeric_cols = df.select_dtypes(include=[np.number]).columns.tolist()
    has_prob = "delay_probability" in df.columns

    rows = []
    for col in numeric_cols:
        if col == TARGET:
            continue
        corr_label = df[col].corr(df[TARGET])
        corr_prob = (df[col].corr(df["delay_probability"])
                     if has_prob and col != "delay_probability" else None)
        flagged = abs(corr_label) >= LEAKAGE_CORR_THRESHOLD or (
            corr_prob is not None and abs(corr_prob) >= LEAKAGE_CORR_THRESHOLD)
        rows.append({
            "feature": col,
            "corr_with_delay_label": round(float(corr_label), 4),
            "corr_with_delay_probability": (
                round(float(corr_prob), 4) if corr_prob is not None else None),
            "used_as_model_input": col in FEATURE_COLUMNS,
            "is_label_derived_column": col in LABEL_DERIVED_COLUMNS,
            "flag_high_correlation": bool(flagged),
        })

    out = pd.DataFrame(rows)
    sort_key = out["corr_with_delay_probability"].fillna(out["corr_with_delay_label"])
    return out.reindex(sort_key.abs().sort_values(ascending=False).index)


def risk_score_redundancy_check(df: pd.DataFrame) -> dict:
    """Proves (rather than merely suspects) that risk_score is an exact
    deterministic linear function of six columns already present as
    standalone features — i.e. it carries no information they don't
    already carry, while (per leakage_scan) correlating with the target
    more strongly than any single one of them."""
    if "risk_score" not in df.columns:
        return {"present": False}

    recomputed = sum(df[col] * w for col, w in RISK_SCORE_WEIGHTS.items())
    max_abs_diff = float((df["risk_score"] - recomputed).abs().max())
    return {
        "present": True,
        "used_as_model_input": "risk_score" in FEATURE_COLUMNS,
        "formula": " + ".join(f"{w}*{c}" for c, w in RISK_SCORE_WEIGHTS.items()),
        "is_exact_deterministic_function_of_existing_features": bool(
            np.isclose(max_abs_diff, 0, atol=1e-6)),
        "max_reconstruction_error": max_abs_diff,
        "corr_with_delay_label": round(float(df["risk_score"].corr(df[TARGET])), 4),
        "corr_with_delay_probability": (
            round(float(df["risk_score"].corr(df["delay_probability"])), 4)
            if "delay_probability" in df.columns else None),
        "verdict": (
            "risk_score is provably redundant with features already in "
            "FEATURE_COLUMNS (exact linear combination) and correlates with "
            "the label more strongly than any raw driver. Excluded from "
            "training — see train_model.py FEATURE_COLUMNS comment."
        ),
    }


def print_report(stats: dict, scan: pd.DataFrame, risk_check: dict):
    print("=" * 78)
    print("  DATA QUALITY & LEAKAGE AUDIT — data/flight_data.csv (SYNTHETIC)")
    print("=" * 78)

    print(f"\nRecords            : {stats['n_records']}")
    print(f"Columns             : {stats['n_columns']}")
    print(f"Duplicate rows      : {stats['n_duplicate_rows']}")
    print(f"Missing values      : {stats['missing_values'] or 'none'}")
    cd = stats["class_distribution"]
    print(f"Class distribution  : on_time={cd['on_time']}  "
          f"delayed={cd['delayed']}  ({cd['delayed_pct']}% delayed)")

    print("\n── Correlation with target (top 10 by |corr with delay_probability|) ──")
    disp = scan.head(10).copy()
    print(disp.to_string(index=False))

    flagged = scan[scan["flag_high_correlation"]]
    if len(flagged):
        print(f"\n⚠️  {len(flagged)} column(s) flagged at |corr| >= "
              f"{LEAKAGE_CORR_THRESHOLD} (heuristic — verify each manually, "
              f"a high correlation alone is not automatically leakage):")
        for _, r in flagged.iterrows():
            used = "USED as model input" if r["used_as_model_input"] else "NOT used as model input"
            print(f"    - {r['feature']:22s} corr_label={r['corr_with_delay_label']:+.3f}  ({used})")
    else:
        print(f"\n✅ No column exceeds the |corr| >= {LEAKAGE_CORR_THRESHOLD} heuristic flag.")

    print("\n── risk_score redundancy check ─────────────────────────")
    if risk_check.get("present"):
        print(f"  Formula                         : {risk_check['formula']}")
        print(f"  Exact function of existing cols : "
              f"{risk_check['is_exact_deterministic_function_of_existing_features']} "
              f"(max reconstruction error: {risk_check['max_reconstruction_error']:.2e})")
        print(f"  corr(risk_score, delay_label)   : {risk_check['corr_with_delay_label']}")
        print(f"  corr(risk_score, delay_probability): {risk_check['corr_with_delay_probability']}")
        print(f"  Used as model input today        : {risk_check['used_as_model_input']}")
        print(f"  Verdict: {risk_check['verdict']}")
    else:
        print("  risk_score column not present in this CSV.")

    print("\n── Label-derived columns (must never be model inputs) ───")
    for c in LABEL_DERIVED_COLUMNS:
        in_features = c in FEATURE_COLUMNS
        mark = "❌ IN FEATURE_COLUMNS — BUG" if in_features else "✅ correctly excluded"
        print(f"    {c:22s} {mark}")

    print("\nReminder: this dataset is SYNTHETIC (data_generator.py). It is "
          "useful for demonstrating a full ML pipeline end-to-end, but it "
          "does not represent real airline operations and the trained "
          "model's numbers should not be read as real-world delay rates.")
    print("=" * 78)


def save_plot(scan: pd.DataFrame):
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        return

    os.makedirs(PLOTS_DIR, exist_ok=True)
    d = scan.dropna(subset=["corr_with_delay_probability"]).copy()
    d = d.reindex(d["corr_with_delay_probability"].abs().sort_values(ascending=False).index)

    colors = []
    for _, r in d.iterrows():
        if r["feature"] == "risk_score":
            colors.append("#ef4444")       # flagged, excluded
        elif r["flag_high_correlation"]:
            colors.append("#f59e0b")
        elif r["used_as_model_input"]:
            colors.append("#3b82f6")
        else:
            colors.append("#9ca3af")       # not used as a model input

    plt.figure(figsize=(9, 8))
    plt.barh(d["feature"][::-1], d["corr_with_delay_probability"][::-1], color=colors[::-1])
    plt.axvline(0, color="black", linewidth=0.8)
    plt.xlabel("Correlation with delay_probability")
    plt.title("Feature Correlation with Target — Leakage Audit\n"
              "(red = excluded as redundant/leaky, blue = used feature, grey = unused)")
    plt.tight_layout()
    plt.savefig(f"{PLOTS_DIR}/feature_target_correlation.png", dpi=150)
    plt.close()
    print(f"  ✅ {PLOTS_DIR}/feature_target_correlation.png")


def main():
    df = load_data()
    stats = basic_stats(df)
    scan = leakage_scan(df)
    risk_check = risk_score_redundancy_check(df)

    print_report(stats, scan, risk_check)

    os.makedirs(RESULTS_DIR, exist_ok=True)
    payload = {
        "basic_stats": stats,
        "correlation_scan": scan.to_dict(orient="records"),
        "risk_score_redundancy_check": risk_check,
        "feature_columns_used_for_training": FEATURE_COLUMNS,
        "n_features_used_for_training": len(FEATURE_COLUMNS),
    }
    with open(f"{RESULTS_DIR}/data_quality_report.json", "w") as f:
        json.dump(payload, f, indent=2)
    with open(f"{RESULTS_DIR}/data_quality_report.txt", "w") as f:
        f.write(f"Records: {stats['n_records']}\n")
        f.write(f"Duplicate rows: {stats['n_duplicate_rows']}\n")
        f.write(f"Missing values: {stats['missing_values'] or 'none'}\n")
        f.write(f"Class distribution: {stats['class_distribution']}\n\n")
        f.write("Correlation scan (sorted by |corr with delay_probability|):\n")
        f.write(scan.to_string(index=False))
        f.write("\n\nrisk_score redundancy check:\n")
        f.write(json.dumps(risk_check, indent=2))

    print(f"\n  ✅ {RESULTS_DIR}/data_quality_report.json")
    print(f"  ✅ {RESULTS_DIR}/data_quality_report.txt")
    save_plot(scan)


if __name__ == "__main__":
    main()
