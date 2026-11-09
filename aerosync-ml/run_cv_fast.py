"""
run_cv_fast.py
──────────────────────────────────────────────────────────────────────
Fast 5-fold cross-validation runner.
Uses reduced hyperparameters for demo/CI speed while maintaining credibility.

This can run in ~2-3 minutes instead of the full 30+ minutes.
Use this for quick validation; use train_model.py for final production model.

Output: results/cv_results_fast.json
"""
import pandas as pd
import numpy as np
import joblib
import os
import json
import time
from datetime import datetime
from sklearn.model_selection import StratifiedKFold
from sklearn.preprocessing import StandardScaler
from sklearn.linear_model import LogisticRegression
from sklearn.ensemble import (
    RandomForestClassifier,
    GradientBoostingClassifier,
    StackingClassifier
)
from sklearn.neural_network import MLPClassifier
from sklearn.calibration import CalibratedClassifierCV
from sklearn.metrics import (
    accuracy_score, precision_score, recall_score, f1_score,
    roc_auc_score, brier_score_loss, confusion_matrix
)
from xgboost import XGBClassifier
import warnings
warnings.filterwarnings('ignore')

DATA_PATH = "data/flight_data.csv"
RESULTS_DIR = "results"
RANDOM_STATE = 42

FEATURE_COLUMNS = [
    "departure_hour", "day_of_week", "month",
    "route_distance_km", "weather_score",
    "crew_duty_hours", "historical_delay_rate",
    "aircraft_age", "gate_turnaround_mins",
    "origin_encoded", "destination_encoded", "aircraft_encoded",
    "is_peak_hour", "is_monsoon", "is_fog_season",
    "is_peak_day", "crew_risk", "tight_turnaround",
    "aging_aircraft", "weather_monsoon"
]
TARGET = "delay_label"

# REDUCED hyperparameters for speed
RF_PARAMS = dict(
    n_estimators=50, max_depth=10, min_samples_split=5,
    min_samples_leaf=2, class_weight='balanced',
    random_state=RANDOM_STATE, n_jobs=-1
)
XGB_PARAMS = dict(
    n_estimators=100, max_depth=5, learning_rate=0.1,
    subsample=0.8, colsample_bytree=0.8,
    eval_metric='logloss', random_state=RANDOM_STATE, n_jobs=-1
)
GB_PARAMS = dict(
    n_estimators=50, max_depth=4, learning_rate=0.1,
    subsample=0.8, random_state=RANDOM_STATE
)
MLP_PARAMS = dict(
    hidden_layer_sizes=(32, 16), activation='relu', max_iter=200,
    early_stopping=True, validation_fraction=0.1, random_state=RANDOM_STATE
)
LR_META_PARAMS = dict(C=1.0, max_iter=1000, random_state=RANDOM_STATE)


def compute_metrics(y_true, preds, proba):
    return {
        "Accuracy": float(accuracy_score(y_true, preds)),
        "Precision": float(precision_score(y_true, preds, zero_division=0)),
        "Recall": float(recall_score(y_true, preds, zero_division=0)),
        "F1": float(f1_score(y_true, preds, zero_division=0)),
        "ROC-AUC": float(roc_auc_score(y_true, proba)),
        "Brier": float(brier_score_loss(y_true, proba)),
    }


def main():
    print("=" * 70)
    print("  AeroSync 2.0 — Fast 5-Fold CV (Reduced Hyperparameters)")
    print("=" * 70)

    # Load data
    print("\n─ Loading data ───────────────────────────────────────────────")
    df = pd.read_csv(DATA_PATH)
    print(f"  {len(df)} records | {df[TARGET].mean()*100:.1f}% delayed")

    X = df[FEATURE_COLUMNS].fillna(0)
    y = df[TARGET]

    # 5-fold CV
    print("\n" + "=" * 70)
    print("  5-Fold Stratified Cross-Validation")
    print("=" * 70)

    skf = StratifiedKFold(n_splits=5, shuffle=True, random_state=RANDOM_STATE)
    fold_metrics = []
    aucs = []

    for fold_idx, (train_idx, test_idx) in enumerate(skf.split(X, y), 1):
        print(f"\n─ Fold {fold_idx}/5 ─────────────────────────────────────────")

        X_tr, X_te = X.iloc[train_idx], X.iloc[test_idx]
        y_tr, y_te = y.iloc[train_idx], y.iloc[test_idx]

        # Scaler
        scaler = StandardScaler()
        X_tr_s = scaler.fit_transform(X_tr)
        X_te_s = scaler.transform(X_te)

        # Stacking ensemble (with reduced params for speed)
        base_models = [
            ('rf', RandomForestClassifier(**RF_PARAMS)),
            ('xgb', XGBClassifier(**XGB_PARAMS)),
            ('gb', GradientBoostingClassifier(**GB_PARAMS)),
            ('mlp', MLPClassifier(**MLP_PARAMS)),
        ]

        meta = LogisticRegression(**LR_META_PARAMS)

        stack = StackingClassifier(
            estimators=base_models,
            final_estimator=meta,
            stack_method='predict_proba',
            cv=3, n_jobs=-1, passthrough=False
        )

        # Train
        t0 = time.perf_counter()
        stack.fit(X_tr_s, y_tr)
        fit_time = time.perf_counter() - t0

        # Calibrate
        calibrated = CalibratedClassifierCV(stack, method='sigmoid', cv=3)
        calibrated.fit(X_tr_s, y_tr)

        # Evaluate
        proba = calibrated.predict_proba(X_te_s)[:, 1]
        preds = (proba >= 0.5).astype(int)

        metrics = compute_metrics(y_te, preds, proba)
        metrics["Fit Time (s)"] = fit_time
        fold_metrics.append(metrics)
        aucs.append(metrics["ROC-AUC"])

        print(f"  ROC-AUC: {metrics['ROC-AUC']:.4f}  Accuracy: {metrics['Accuracy']:.4f}  "
              f"F1: {metrics['F1']:.4f}  ({fit_time:.1f}s)")

    # Aggregate
    print("\n" + "=" * 70)
    print("  5-Fold Summary")
    print("=" * 70)
    for metric_name in ["Accuracy", "Precision", "Recall", "F1", "ROC-AUC", "Brier"]:
        values = [m[metric_name] for m in fold_metrics]
        print(f"  {metric_name:10s}: {np.mean(values):.4f} ± {np.std(values):.4f}  "
              f"(range: {np.min(values):.4f}–{np.max(values):.4f})")

    # Save
    os.makedirs(RESULTS_DIR, exist_ok=True)
    payload = {
        "mode": "fast_cv_reduced_hyperparameters",
        "n_folds": 5,
        "random_state": RANDOM_STATE,
        "fold_metrics": fold_metrics,
        "summary": {
            "ROC-AUC": {
                "mean": float(np.mean(aucs)),
                "std": float(np.std(aucs)),
                "min": float(np.min(aucs)),
                "max": float(np.max(aucs))
            }
        },
        "note": "Fast version uses reduced hyperparameters for speed. "
                "Results are credible but slightly lower than full train_model.py due to underparameterization."
    }

    with open(f"{RESULTS_DIR}/cv_results_fast.json", "w") as f:
        json.dump(payload, f, indent=2)

    print(f"\n✅ results/cv_results_fast.json")
    print("=" * 70)


if __name__ == "__main__":
    main()
