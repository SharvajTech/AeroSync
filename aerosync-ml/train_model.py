import pandas as pd
import numpy as np
import joblib
import os
import matplotlib
matplotlib.use('Agg')  # non-interactive backend
import matplotlib.pyplot as plt
import seaborn as sns
import warnings
warnings.filterwarnings('ignore')

from sklearn.model_selection    import train_test_split, cross_val_score
from sklearn.preprocessing      import StandardScaler
from sklearn.linear_model       import LogisticRegression
from sklearn.ensemble           import (
    RandomForestClassifier,
    GradientBoostingClassifier,
    StackingClassifier
)
from sklearn.neural_network     import MLPClassifier
from sklearn.calibration        import CalibratedClassifierCV
from sklearn.metrics            import (
    accuracy_score, classification_report,
    roc_auc_score, roc_curve, confusion_matrix,
    brier_score_loss
)
from xgboost import XGBClassifier

# ── Config ────────────────────────────────────────────────────────────────────
DATA_PATH    = "data/flight_data.csv"
MODEL_DIR    = "models"
RANDOM_STATE = 42

FEATURE_COLUMNS = [
    "departure_hour", "day_of_week", "month",
    "route_distance_km", "weather_score",
    "crew_duty_hours", "historical_delay_rate",
    "aircraft_age", "gate_turnaround_mins",
    "origin_encoded", "destination_encoded", "aircraft_encoded",
    "is_peak_hour", "is_monsoon", "is_fog_season",
    "is_peak_day", "crew_risk", "tight_turnaround",
    "aging_aircraft", "weather_monsoon", "risk_score"
]
TARGET = "delay_label"


# ── Load and prepare data ─────────────────────────────────────────────────────
def load_and_prepare():
    print(f"Loading data from {DATA_PATH}...")
    df = pd.read_csv(DATA_PATH)
    print(f"Loaded {len(df)} records")
    print(f"Delayed: {df[TARGET].mean()*100:.1f}%")

    # Verify all features exist
    missing = [c for c in FEATURE_COLUMNS if c not in df.columns]
    if missing:
        print(f"\n❌ Missing features: {missing}")
        print(f"   Run data_generator.py first to regenerate the CSV")
        raise ValueError(f"CSV missing: {missing}")

    X = df[FEATURE_COLUMNS].fillna(0)
    y = df[TARGET]

    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.2,
        random_state=RANDOM_STATE, stratify=y)

    scaler = StandardScaler()
    X_train_s = scaler.fit_transform(X_train)
    X_test_s  = scaler.transform(X_test)

    print(f"Train: {len(X_train)} | Test: {len(X_test)}")
    return (X_train, X_test, y_train, y_test,
            X_train_s, X_test_s, scaler, X, y)


# ── Build stacking ensemble ───────────────────────────────────────────────────
def build_stacking_ensemble():
    """
    4 diverse base models → Logistic Regression meta-learner.
    Each base model specialises in different feature patterns:
    - RandomForest: feature interactions, categorical patterns
    - XGBoost:      best overall on tabular data
    - GradientBoosting: temporal/seasonal patterns
    - MLP:          non-linear combinations
    """
    base_models = [
        ('random_forest', RandomForestClassifier(
            n_estimators=200,
            max_depth=12,
            min_samples_split=5,
            min_samples_leaf=2,
            class_weight='balanced',
            random_state=RANDOM_STATE,
            n_jobs=-1
        )),
        ('xgboost', XGBClassifier(
            n_estimators=300,
            max_depth=6,
            learning_rate=0.05,
            subsample=0.8,
            colsample_bytree=0.8,
            eval_metric='logloss',
            random_state=RANDOM_STATE,
            n_jobs=-1
        )),
        ('gradient_boosting', GradientBoostingClassifier(
            n_estimators=150,
            max_depth=5,
            learning_rate=0.05,
            subsample=0.8,
            random_state=RANDOM_STATE
        )),
        ('neural_net', MLPClassifier(
            hidden_layer_sizes=(64, 32),
            activation='relu',
            max_iter=300,
            early_stopping=True,
            validation_fraction=0.1,
            random_state=RANDOM_STATE
        ))
    ]

    meta_learner = LogisticRegression(
        C=1.0, max_iter=1000, random_state=RANDOM_STATE)

    stack = StackingClassifier(
        estimators=base_models,
        final_estimator=meta_learner,
        stack_method='predict_proba',
        cv=3,          # 3-fold for speed
        n_jobs=-1,
        passthrough=False
    )
    return stack


# ── Train and evaluate ────────────────────────────────────────────────────────
def train_and_evaluate(X_train, X_test, y_train, y_test,
                        X_train_s, X_test_s):

    print("\n── Building stacking ensemble ───────────────────────")
    stack = build_stacking_ensemble()

    print("── Training base models (this takes 3-5 minutes)... ─")
    stack.fit(X_train_s, y_train)
    print("── Training complete ─────────────────────────────────")

    print("── Calibrating probabilities ────────────────────────")

    calibrated = CalibratedClassifierCV(stack, method='sigmoid', cv=5)
    calibrated.fit(X_train_s, y_train)

    proba = calibrated.predict_proba(X_test_s)[:, 1]
    preds = (proba >= 0.5).astype(int)

    acc   = accuracy_score(y_test, preds)
    auc   = roc_auc_score(y_test, proba)
    brier = brier_score_loss(y_test, proba)

    print(f"\n── Results ──────────────────────────────────────────")
    print(f"  Accuracy    : {acc*100:.2f}%")
    print(f"  ROC-AUC     : {auc:.4f}")
    print(f"  Brier Score : {brier:.4f}  (lower = better)")
    print(classification_report(
        y_test, preds,
        target_names=["On Time", "Delayed"]))

    return calibrated, stack, proba, preds, auc


# ── Extract feature importance from RF base ───────────────────────────────────
def extract_feature_importance(stack, feature_names):
    try:
        rf = stack.named_estimators_['random_forest']
        importance_dict = dict(zip(
            feature_names, rf.feature_importances_))
        return dict(sorted(
            importance_dict.items(),
            key=lambda x: x[1], reverse=True))
    except Exception as e:
        print(f"⚠️  Could not extract importances: {e}")
        return {}


# ── Save all artefacts ────────────────────────────────────────────────────────
def save_all(calibrated, scaler, importances):
    os.makedirs(MODEL_DIR, exist_ok=True)

    joblib.dump(calibrated,      f"{MODEL_DIR}/ensemble.pkl")
    joblib.dump(scaler,          f"{MODEL_DIR}/scaler.pkl")
    joblib.dump(FEATURE_COLUMNS, f"{MODEL_DIR}/feature_columns.pkl")
    joblib.dump(importances,     f"{MODEL_DIR}/feature_importances.pkl")

    print(f"\n── Saved ────────────────────────────────────────────")
    print(f"  ✅ models/ensemble.pkl")
    print(f"  ✅ models/scaler.pkl")
    print(f"  ✅ models/feature_columns.pkl")
    print(f"  ✅ models/feature_importances.pkl")


# ── Plots ─────────────────────────────────────────────────────────────────────
def plot_results(proba, preds, y_test, importances):
    os.makedirs("plots", exist_ok=True)

    # ROC curve
    fpr, tpr, _ = roc_curve(y_test, proba)
    auc_val = roc_auc_score(y_test, proba)
    plt.figure(figsize=(7, 5))
    plt.plot(fpr, tpr, color='steelblue', linewidth=2,
             label=f'Stacking Ensemble (AUC={auc_val:.3f})')
    plt.plot([0, 1], [0, 1], 'k--', linewidth=0.8)
    plt.xlabel('False Positive Rate')
    plt.ylabel('True Positive Rate')
    plt.title('ROC Curve — Stacking Ensemble')
    plt.legend(loc='lower right')
    plt.tight_layout()
    plt.savefig('plots/roc_ensemble.png', dpi=150)
    plt.close()
    print("  ✅ plots/roc_ensemble.png")

    # Feature importance
    if importances:
        top  = list(importances.items())[:15]
        names, values = zip(*top)
        plt.figure(figsize=(10, 6))
        colors = ['#ef4444' if v > 0.08 else
                  '#f59e0b' if v > 0.04 else
                  '#22c55e' for v in values[::-1]]
        plt.barh(list(names[::-1]), list(values[::-1]),
                 color=colors)
        plt.title('Feature Importance — Random Forest Base Model')
        plt.xlabel('Importance Score')
        plt.tight_layout()
        plt.savefig('plots/feature_importance.png', dpi=150)
        plt.close()
        print("  ✅ plots/feature_importance.png")

    # Confusion matrix
    cm = confusion_matrix(y_test, preds)
    plt.figure(figsize=(5, 4))
    sns.heatmap(cm, annot=True, fmt='d', cmap='Blues',
                xticklabels=['On Time', 'Delayed'],
                yticklabels=['On Time', 'Delayed'])
    plt.title('Confusion Matrix — Stacking Ensemble')
    plt.ylabel('Actual')
    plt.xlabel('Predicted')
    plt.tight_layout()
    plt.savefig('plots/confusion_matrix.png', dpi=150)
    plt.close()
    print("  ✅ plots/confusion_matrix.png")


# ── Cross-validation on RF base (faster than full stack) ─────────────────────
def quick_cv(X_full, y_full, scaler):
    print("\n── Quick Cross Validation (Random Forest base) ──────")
    rf_cv = RandomForestClassifier(
        n_estimators=100, class_weight='balanced',
        random_state=RANDOM_STATE, n_jobs=-1)
    X_scaled = scaler.transform(X_full)
    scores   = cross_val_score(
        rf_cv, X_scaled, y_full,
        cv=5, scoring='roc_auc', n_jobs=-1)
    print(f"  CV AUC : {scores.mean():.4f} ± {scores.std():.4f}")
    print(f"  Folds  : {[round(s,4) for s in scores]}")


# ── Main ──────────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    print("=" * 55)
    print("  AeroSync 2.0 — Stacking Ensemble Training")
    print("=" * 55)

    (X_train, X_test, y_train, y_test,
     X_train_s, X_test_s, scaler, X_full, y_full) = load_and_prepare()

    calibrated, stack, proba, preds, auc = train_and_evaluate(
        X_train, X_test, y_train, y_test,
        X_train_s, X_test_s)

    importances = extract_feature_importance(stack, FEATURE_COLUMNS)

    quick_cv(X_full, y_full, scaler)

    print("\n── Generating plots ─────────────────────────────────")
    plot_results(proba, preds, y_test, importances)

    save_all(calibrated, scaler, importances)

    print("\n" + "=" * 55)
    print(f"  Training complete")
    print(f"  Best AUC  : {auc:.4f}")
    print(f"  Model     : stacking_ensemble")
    print(f"  Run main.py next")
    print("=" * 55)