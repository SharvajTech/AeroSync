import pandas as pd
import numpy as np
import joblib
import os
import json
import time
from datetime import datetime
import matplotlib
matplotlib.use('Agg')  # non-interactive backend
import matplotlib.pyplot as plt
import seaborn as sns
import warnings
warnings.filterwarnings('ignore')

from sklearn.model_selection    import train_test_split, cross_val_score, StratifiedKFold
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
    accuracy_score, precision_score, recall_score, f1_score,
    classification_report,
    roc_auc_score, roc_curve, confusion_matrix,
    brier_score_loss
)
from xgboost import XGBClassifier

# ── Config ────────────────────────────────────────────────────────────────────
DATA_PATH    = "data/flight_data.csv"
MODEL_DIR    = "models"
PLOTS_DIR    = "plots"
RESULTS_DIR  = "results"
RANDOM_STATE = 42

# NOTE on feature selection — leakage audit (see data_quality_report.py):
#
# "risk_score" was removed from the trained feature set. It is still computed
# and written to data/flight_data.csv by data_generator.py (kept for
# analytics/dashboard display), but it is NOT used as a model input here.
#
# Reason: risk_score is a hand-engineered weighted sum of six columns that
# are ALREADY individually present in this feature list (weather_score,
# historical_delay_rate, crew_risk, tight_turnaround, is_fog_season,
# is_peak_day), using weights chosen to approximate the same synthetic
# delay-probability formula that produced delay_label. A correlation audit
# on the shipped dataset showed risk_score correlates with the label more
# strongly than any single raw driver (corr with delay_label = 0.689, vs.
# weather_score's 0.632 — the strongest true individual driver), and it was
# proven to be an *exact* deterministic linear function of those six columns
# (max reconstruction error ~2e-16, i.e. floating-point noise). That means
# it adds zero new information to the model while acting as a disguised
# shortcut to the label-generating formula — the textbook shape of a
# leakage-prone engineered feature, even though no individual raw input was
# itself illegitimate. It has been excluded rather than deleted from the
# dataset, so the audit trail and the CSV schema stay intact.
#
# "delay_probability" (the continuous pre-noise value used to derive
# delay_label) is also present in the CSV for analysis purposes only and
# must never be added to FEATURE_COLUMNS.
FEATURE_COLUMNS = [
    "departure_hour", "day_of_week", "month",
    "route_distance_km", "weather_score",
    "crew_duty_hours", "historical_delay_rate",
    "aircraft_age", "gate_turnaround_mins",
    "origin_encoded", "destination_encoded", "aircraft_encoded",
    "is_peak_hour", "is_monsoon", "is_fog_season",
    "is_peak_day", "crew_risk", "tight_turnaround",
    "aging_aircraft", "weather_monsoon"
    # "risk_score" intentionally excluded — see note above.
]
TARGET = "delay_label"

# Columns that must NEVER be used as model inputs because they are derived
# from (or are) the label itself. Used as a runtime guard below.
LABEL_DERIVED_COLUMNS = ["delay_label", "delay_probability"]

# ── Shared hyperparameters ──────────────────────────────────────────────────
# Single source of truth: these are the exact configs used both (a) as base
# learners inside the stacking ensemble and (b) as the standalone baseline
# models in the comparison, so the comparison is a true apples-to-apples
# test of "does stacking help", not an artifact of different tuning.
RF_PARAMS = dict(
    n_estimators=200,
    max_depth=12,
    min_samples_split=5,
    min_samples_leaf=2,
    class_weight='balanced',
    random_state=RANDOM_STATE,
    n_jobs=-1
)
XGB_PARAMS = dict(
    n_estimators=300,
    max_depth=6,
    learning_rate=0.05,
    subsample=0.8,
    colsample_bytree=0.8,
    eval_metric='logloss',
    random_state=RANDOM_STATE,
    n_jobs=-1
)
GB_PARAMS = dict(
    n_estimators=150,
    max_depth=5,
    learning_rate=0.05,
    subsample=0.8,
    random_state=RANDOM_STATE
)
MLP_PARAMS = dict(
    hidden_layer_sizes=(64, 32),
    activation='relu',
    max_iter=300,
    early_stopping=True,
    validation_fraction=0.1,
    random_state=RANDOM_STATE
)
# Meta-learner inside the stack (unchanged from the original pipeline).
LR_META_PARAMS = dict(C=1.0, max_iter=1000, random_state=RANDOM_STATE)
# Standalone Logistic Regression baseline — same regularisation as the
# meta-learner so the comparison isn't skewed by different tuning.
LR_BASELINE_PARAMS = dict(C=1.0, max_iter=1000, random_state=RANDOM_STATE)


# ── Load and prepare data ─────────────────────────────────────────────────────
def load_and_prepare():
    print(f"Loading data from {DATA_PATH}...")
    df = pd.read_csv(DATA_PATH)
    print(f"Loaded {len(df)} records")
    print(f"Delayed: {df[TARGET].mean()*100:.1f}%")
    print("Dataset is SYNTHETIC — generated by data_generator.py, "
          "not real airline operations data.")

    # Verify all features exist
    missing = [c for c in FEATURE_COLUMNS if c not in df.columns]
    if missing:
        print(f"\n❌ Missing features: {missing}")
        print(f"   Run data_generator.py first to regenerate the CSV")
        raise ValueError(f"CSV missing: {missing}")

    # Guard rail: never let a label-derived column slip into training.
    leaked = [c for c in LABEL_DERIVED_COLUMNS if c in FEATURE_COLUMNS]
    if leaked:
        raise ValueError(
            f"FEATURE_COLUMNS contains label-derived column(s) {leaked} — "
            f"this would leak the target into the model. Aborting.")

    X = df[FEATURE_COLUMNS].fillna(0)
    y = df[TARGET]

    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.2,
        random_state=RANDOM_STATE, stratify=y)

    scaler = StandardScaler()
    X_train_s = scaler.fit_transform(X_train)
    X_test_s  = scaler.transform(X_test)

    print(f"Train: {len(X_train)} | Test: {len(X_test)} "
          f"(same split reused for every model below)")
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
        ('random_forest',     RandomForestClassifier(**RF_PARAMS)),
        ('xgboost',           XGBClassifier(**XGB_PARAMS)),
        ('gradient_boosting', GradientBoostingClassifier(**GB_PARAMS)),
        ('neural_net',        MLPClassifier(**MLP_PARAMS)),
    ]

    meta_learner = LogisticRegression(**LR_META_PARAMS)

    stack = StackingClassifier(
        estimators=base_models,
        final_estimator=meta_learner,
        stack_method='predict_proba',
        cv=3,          # 3-fold for speed
        n_jobs=-1,
        passthrough=False
    )
    return stack


# ── Train and evaluate (stacking ensemble — unchanged behaviour) ─────────────
def train_and_evaluate(X_train, X_test, y_train, y_test,
                        X_train_s, X_test_s):

    print("\n── Building stacking ensemble ───────────────────────")
    stack = build_stacking_ensemble()

    print("── Training base models (this takes a few minutes)... ─")
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


# ── Metric helpers (used for every model in the comparison) ──────────────────
def compute_full_metrics(y_true, preds, proba):
    return {
        "Accuracy":  accuracy_score(y_true, preds),
        "Precision": precision_score(y_true, preds, zero_division=0),
        "Recall":    recall_score(y_true, preds, zero_division=0),
        "F1":        f1_score(y_true, preds, zero_division=0),
        "ROC-AUC":   roc_auc_score(y_true, proba),
        "Brier":     brier_score_loss(y_true, proba),
    }


def time_inference(model, X, n_repeats=5):
    """Average wall-clock time of predict_proba over n_repeats runs.
    Returns (total_seconds_avg, per_sample_milliseconds)."""
    times = []
    for _ in range(n_repeats):
        t0 = time.perf_counter()
        model.predict_proba(X)
        times.append(time.perf_counter() - t0)
    avg_total = float(np.mean(times))
    per_sample_ms = (avg_total / len(X)) * 1000.0
    return avg_total, per_sample_ms


# ── Individual baseline models ────────────────────────────────────────────────
def build_baseline_models():
    """Standalone models trained directly on the features — using the SAME
    hyperparameters as each model's counterpart inside the stack, so any
    performance gap reflects stacking itself, not different tuning."""
    return {
        "Logistic Regression": LogisticRegression(**LR_BASELINE_PARAMS),
        "Random Forest":       RandomForestClassifier(**RF_PARAMS),
        "XGBoost":             XGBClassifier(**XGB_PARAMS),
        "Gradient Boosting":   GradientBoostingClassifier(**GB_PARAMS),
        "MLP":                 MLPClassifier(**MLP_PARAMS),
    }


def run_baseline_comparison(X_train_s, y_train, X_test_s, y_test):
    print("\n── Training individual baseline models for comparison ──")
    models = build_baseline_models()
    results, fitted, probas = {}, {}, {}

    for name, model in models.items():
        print(f"  Training {name}...")
        t0 = time.perf_counter()
        model.fit(X_train_s, y_train)
        fit_time = time.perf_counter() - t0

        proba = model.predict_proba(X_test_s)[:, 1]
        preds = (proba >= 0.5).astype(int)
        metrics = compute_full_metrics(y_test, preds, proba)
        _, per_sample_ms = time_inference(model, X_test_s)
        metrics["Fit Time (s)"] = fit_time
        metrics["Inference (ms/sample)"] = per_sample_ms

        results[name] = metrics
        fitted[name] = model
        probas[name] = proba
        print(f"    Accuracy={metrics['Accuracy']:.4f}  "
              f"ROC-AUC={metrics['ROC-AUC']:.4f}  "
              f"Fit={fit_time:.2f}s")

    return results, fitted, probas


def build_comparison_table(all_results: dict):
    df = pd.DataFrame(all_results).T
    col_order = ["Accuracy", "Precision", "Recall", "F1", "ROC-AUC", "Brier",
                 "Fit Time (s)", "Inference (ms/sample)"]
    df = df[col_order].astype(float)
    df = df.sort_values("ROC-AUC", ascending=False)

    higher_better = ["Accuracy", "Precision", "Recall", "F1", "ROC-AUC"]
    lower_better  = ["Brier", "Fit Time (s)", "Inference (ms/sample)"]
    best = {}
    for m in higher_better:
        best[m] = df[m].idxmax()
    for m in lower_better:
        best[m] = df[m].idxmin()
    return df, best


def print_comparison_report(df, best):
    print("\n" + "=" * 78)
    print("  MODEL COMPARISON — Individual Baselines vs. Stacking Ensemble")
    print("=" * 78)
    disp = df.copy()
    for c in ["Accuracy", "Precision", "Recall", "F1", "ROC-AUC", "Brier"]:
        disp[c] = disp[c].map(lambda v: f"{v:.4f}")
    disp["Fit Time (s)"] = disp["Fit Time (s)"].map(lambda v: f"{v:.2f}")
    disp["Inference (ms/sample)"] = disp["Inference (ms/sample)"].map(lambda v: f"{v:.4f}")
    print(disp.to_string())

    print("\n── Best model per metric ─────────────────────────────")
    for metric, name in best.items():
        note = ("(lower is better)"
                 if metric in ("Brier", "Fit Time (s)", "Inference (ms/sample)")
                 else "(higher is better)")
        print(f"  {metric:24s}: {name:28s} {note}")

    print("\n⚠️  No single model wins on every metric — this is expected, not a")
    print("    bug. Which model is 'best' depends on what the deployment cares")
    print("    about: Recall matters more when a missed delay is costly; Precision")
    print("    matters more when false alarms waste ops resources; Brier Score")
    print("    matters when the predicted PROBABILITY itself (not just the 0/1")
    print("    label) drives downstream decisions — as it does in AeroSync's")
    print("    risk scoring and cascade simulation.")
    print(f"\n  Deployed model: Stacking Ensemble (Calibrated) — see the table")
    print(f"  above for exactly how it compares to each baseline on every metric.")
    print("=" * 78)


def save_comparison_results(df, best, n_train, n_test):
    os.makedirs(RESULTS_DIR, exist_ok=True)
    df.round(6).to_csv(f"{RESULTS_DIR}/model_comparison.csv")

    payload = {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "dataset": {
            "path": DATA_PATH,
            "synthetic": True,
            "n_train": n_train,
            "n_test": n_test,
            "test_size": 0.2,
            "random_state": RANDOM_STATE,
            "stratified": True,
            "n_features": len(FEATURE_COLUMNS),
        },
        "metrics": df.round(6).to_dict(orient="index"),
        "best_per_metric": best,
        "note": ("No model is universally best across every metric. Model "
                 "selection should be driven by which metric matters most "
                 "for the deployment context, not by accuracy alone."),
    }
    with open(f"{RESULTS_DIR}/model_comparison.json", "w") as f:
        json.dump(payload, f, indent=2)

    print("\n── Saved comparison results ─────────────────────────")
    print(f"  ✅ {RESULTS_DIR}/model_comparison.csv")
    print(f"  ✅ {RESULTS_DIR}/model_comparison.json")


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


# ── SHAP artefact export (global explainability + local /predict support) ────
def export_shap_artifacts(stack, X_test_s, feature_names):
    """
    Extracts the fitted XGBoost base-learner out of the (already-fit) stack
    and saves it separately so main.py can build a SHAP TreeExplainer at
    startup without needing to reload the whole calibrated ensemble or any
    sklearn internals. Also produces a global SHAP summary plot here, using
    the same test set as the rest of the evaluation.

    Why XGBoost and not the full calibrated stacking ensemble: SHAP's exact,
    fast TreeExplainer only applies to tree-based models. The deployed
    predictor is a CalibratedClassifierCV wrapping a StackingClassifier
    (RF + XGBoost + GradientBoosting + MLP + a Logistic Regression
    meta-learner) — there is no single tree structure to explain, and
    running a model-agnostic explainer (e.g. KernelSHAP) on the whole
    pipeline would be slow and only approximate. XGBoost is used as the
    practical, faithful stand-in: it is the strongest individual tree-based
    base learner (see the comparison table), and TreeExplainer gives exact
    SHAP values for it in milliseconds — fast enough for a live /predict
    call. This is documented as an approximation of the ensemble's
    reasoning, not a literal decomposition of the meta-learner's math.
    """
    try:
        import shap
    except ImportError:
        print("⚠️  shap not installed — skipping SHAP export "
              "(pip install shap to enable)")
        return

    os.makedirs(MODEL_DIR, exist_ok=True)
    os.makedirs(PLOTS_DIR, exist_ok=True)
    os.makedirs(RESULTS_DIR, exist_ok=True)

    xgb_base = stack.named_estimators_['xgboost']
    joblib.dump(xgb_base, f"{MODEL_DIR}/xgb_base_estimator.pkl")
    joblib.dump(feature_names, f"{MODEL_DIR}/shap_feature_columns.pkl")
    print(f"  ✅ {MODEL_DIR}/xgb_base_estimator.pkl "
          f"(used by main.py for SHAP explanations)")

    explainer = shap.TreeExplainer(xgb_base)

    # Cap sample size for speed on larger test sets; still representative.
    sample_n = min(len(X_test_s), 1000)
    rng = np.random.RandomState(RANDOM_STATE)
    idx = rng.choice(len(X_test_s), size=sample_n, replace=False)
    X_sample = X_test_s[idx]

    shap_values = explainer.shap_values(X_sample)
    # Normalise to a single (n_samples, n_features) array for the positive
    # (delayed) class across shap versions/output shapes.
    if isinstance(shap_values, list):
        sv = shap_values[1] if len(shap_values) > 1 else shap_values[0]
    elif isinstance(shap_values, np.ndarray) and shap_values.ndim == 3:
        sv = shap_values[:, :, 1]
    else:
        sv = shap_values

    mean_abs_shap = np.abs(sv).mean(axis=0)
    global_importance = dict(sorted(
        zip(feature_names, mean_abs_shap.tolist()),
        key=lambda x: x[1], reverse=True))

    with open(f"{RESULTS_DIR}/shap_global_importance.json", "w") as f:
        json.dump({
            "note": ("Mean |SHAP value| per feature, computed with "
                     "shap.TreeExplainer on the fitted XGBoost base "
                     "learner over a sample of the held-out test set. "
                     "This explains the XGBoost component's behaviour, "
                     "used as a practical proxy for the deployed "
                     "stacking ensemble — see export_shap_artifacts() "
                     "docstring in train_model.py."),
            "sample_size": int(sample_n),
            "global_importance": global_importance,
        }, f, indent=2)
    print(f"  ✅ {RESULTS_DIR}/shap_global_importance.json")

    try:
        import matplotlib.pyplot as _plt
        shap.summary_plot(sv, X_sample, feature_names=feature_names,
                           show=False, plot_size=(9, 7))
        _plt.title("SHAP Summary — XGBoost Base Learner "
                    "(explains the deployed ensemble's strongest tree component)")
        _plt.tight_layout()
        _plt.savefig(f"{PLOTS_DIR}/shap_summary.png", dpi=150, bbox_inches='tight')
        _plt.close()
        print(f"  ✅ {PLOTS_DIR}/shap_summary.png")
    except Exception as e:
        print(f"⚠️  Could not render SHAP summary plot: {e}")


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
    os.makedirs(PLOTS_DIR, exist_ok=True)

    # ROC curve (stacking ensemble only — kept for backward compatibility)
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
    plt.savefig(f'{PLOTS_DIR}/roc_ensemble.png', dpi=150)
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
        plt.savefig(f'{PLOTS_DIR}/feature_importance.png', dpi=150)
        plt.close()
        print("  ✅ plots/feature_importance.png")

    # Confusion matrix — this IS the "final selected model" confusion matrix
    # requested by the comparison requirements (the deployed calibrated
    # stacking ensemble).
    cm = confusion_matrix(y_test, preds)
    plt.figure(figsize=(5, 4))
    sns.heatmap(cm, annot=True, fmt='d', cmap='Blues',
                xticklabels=['On Time', 'Delayed'],
                yticklabels=['On Time', 'Delayed'])
    plt.title('Confusion Matrix — Stacking Ensemble (Final Model)')
    plt.ylabel('Actual')
    plt.xlabel('Predicted')
    plt.tight_layout()
    plt.savefig(f'{PLOTS_DIR}/confusion_matrix.png', dpi=150)
    plt.close()
    print("  ✅ plots/confusion_matrix.png")


def plot_roc_comparison(all_probas: dict, y_test):
    os.makedirs(PLOTS_DIR, exist_ok=True)
    plt.figure(figsize=(8, 6))
    for name, proba in all_probas.items():
        fpr, tpr, _ = roc_curve(y_test, proba)
        auc_val = roc_auc_score(y_test, proba)
        lw = 3 if "Stacking" in name else 1.6
        plt.plot(fpr, tpr, linewidth=lw, label=f'{name} (AUC={auc_val:.3f})')
    plt.plot([0, 1], [0, 1], 'k--', linewidth=0.8)
    plt.xlabel('False Positive Rate')
    plt.ylabel('True Positive Rate')
    plt.title('ROC Curves — All Models (same train/test split)')
    plt.legend(loc='lower right', fontsize=9)
    plt.tight_layout()
    plt.savefig(f'{PLOTS_DIR}/roc_comparison.png', dpi=150)
    plt.close()
    print("  ✅ plots/roc_comparison.png")


def plot_model_comparison_chart(df):
    os.makedirs(PLOTS_DIR, exist_ok=True)
    metrics_hib = ["Accuracy", "Precision", "Recall", "F1", "ROC-AUC"]
    models = df.index.tolist()
    x = np.arange(len(models))
    width = 0.15

    fig, axes = plt.subplots(2, 1, figsize=(12, 10),
                              gridspec_kw={'height_ratios': [2, 1]})

    ax = axes[0]
    for i, m in enumerate(metrics_hib):
        ax.bar(x + i * width, df[m].values, width, label=m)
    ax.set_xticks(x + width * (len(metrics_hib) - 1) / 2)
    ax.set_xticklabels(models, rotation=20, ha='right')
    ax.set_ylim(0, 1.05)
    ax.set_ylabel("Score")
    ax.set_title("Model Comparison — Classification Metrics (higher = better)")
    ax.legend(loc="lower right", ncol=5, fontsize=8)
    ax.grid(axis='y', alpha=0.3)

    ax2 = axes[1]
    brier_vals = df['Brier'].values
    colors = ['#22c55e' if v == brier_vals.min() else
              '#ef4444' if v == brier_vals.max() else
              '#3b82f6' for v in brier_vals]
    ax2.bar(models, brier_vals, color=colors)
    ax2.set_ylabel("Brier Score")
    ax2.set_title("Brier Score by Model (lower = better)")
    ax2.set_xticklabels(models, rotation=20, ha='right')
    ax2.grid(axis='y', alpha=0.3)

    plt.tight_layout()
    plt.savefig(f'{PLOTS_DIR}/model_comparison.png', dpi=150)
    plt.close()
    print("  ✅ plots/model_comparison.png")


# ── 5-fold cross-validation with full stacking ensemble ──────────────────────
def full_cv_stacking(X_full, y_full, scaler):
    """
    Trains the full stacking ensemble across 5 stratified folds.
    Returns mean ± std metrics to establish credibility of performance estimates.
    """
    print("\n" + "=" * 60)
    print("  5-Fold Stratified CV — Full Stacking Ensemble")
    print("=" * 60)

    skf = StratifiedKFold(n_splits=5, shuffle=True, random_state=RANDOM_STATE)
    fold_metrics = []
    fold_aucs = []

    for fold_idx, (train_idx, test_idx) in enumerate(skf.split(X_full, y_full), 1):
        print(f"\n── Fold {fold_idx}/5 ─────────────────────────────────────")

        X_tr, X_te = X_full.iloc[train_idx], X_full.iloc[test_idx]
        y_tr, y_te = y_full.iloc[train_idx], y_full.iloc[test_idx]

        # Fit scaler on fold train set only
        fold_scaler = StandardScaler()
        X_tr_s = fold_scaler.fit_transform(X_tr)
        X_te_s = fold_scaler.transform(X_te)

        # Build and train stacking ensemble
        stack = build_stacking_ensemble()
        t0 = time.perf_counter()
        stack.fit(X_tr_s, y_tr)
        fit_time = time.perf_counter() - t0

        # Calibrate
        calibrated = CalibratedClassifierCV(stack, method='sigmoid', cv=5)
        calibrated.fit(X_tr_s, y_tr)

        # Evaluate on held-out fold
        proba = calibrated.predict_proba(X_te_s)[:, 1]
        preds = (proba >= 0.5).astype(int)

        metrics = compute_full_metrics(y_te, preds, proba)
        metrics["Fit Time (s)"] = fit_time
        fold_metrics.append(metrics)
        fold_aucs.append(metrics["ROC-AUC"])

        print(f"  ROC-AUC: {metrics['ROC-AUC']:.4f}  |  Accuracy: {metrics['Accuracy']:.4f}  |  F1: {metrics['F1']:.4f}")

    # Aggregate results
    print("\n" + "=" * 60)
    print("  5-Fold Results Summary")
    print("=" * 60)
    for metric_name in ["Accuracy", "Precision", "Recall", "F1", "ROC-AUC", "Brier"]:
        values = [m[metric_name] for m in fold_metrics]
        print(f"  {metric_name:10s}: {np.mean(values):.4f} ± {np.std(values):.4f}  "
              f"(range: {np.min(values):.4f}–{np.max(values):.4f})")

    # Save results
    cv_results = {
        "n_folds": 5,
        "random_state": RANDOM_STATE,
        "fold_metrics": fold_metrics,
        "summary": {
            "ROC-AUC": {
                "mean": float(np.mean(fold_aucs)),
                "std": float(np.std(fold_aucs)),
                "min": float(np.min(fold_aucs)),
                "max": float(np.max(fold_aucs))
            }
        }
    }

    os.makedirs(RESULTS_DIR, exist_ok=True)
    with open(f"{RESULTS_DIR}/cv_results.json", "w") as f:
        json.dump(cv_results, f, indent=2)
    print(f"\n  ✅ results/cv_results.json")

    return cv_results


# ── Quick CV on RF base (faster than full stack) ───────────────────────────────
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
    print("  AeroSync 2.0 — ML Training & Model Comparison")
    print("=" * 55)

    (X_train, X_test, y_train, y_test,
     X_train_s, X_test_s, scaler, X_full, y_full) = load_and_prepare()

    # ── Stacking ensemble: train + calibrate (unchanged pipeline) ──────────
    t_stack0 = time.perf_counter()
    calibrated, stack, proba, preds, auc = train_and_evaluate(
        X_train, X_test, y_train, y_test,
        X_train_s, X_test_s)
    stack_fit_time = time.perf_counter() - t_stack0

    stacking_metrics = compute_full_metrics(y_test, preds, proba)
    _, stack_inf_ms = time_inference(calibrated, X_test_s)
    stacking_metrics["Fit Time (s)"] = stack_fit_time
    stacking_metrics["Inference (ms/sample)"] = stack_inf_ms

    # ── Individual baseline models — SAME split as above ───────────────────
    baseline_results, baseline_models, baseline_probas = run_baseline_comparison(
        X_train_s, y_train, X_test_s, y_test)

    all_results = {**baseline_results,
                    "Stacking Ensemble (Calibrated)": stacking_metrics}
    all_probas  = {**baseline_probas,
                    "Stacking Ensemble (Calibrated)": proba}

    comparison_df, best_per_metric = build_comparison_table(all_results)
    print_comparison_report(comparison_df, best_per_metric)
    save_comparison_results(comparison_df, best_per_metric,
                             n_train=len(X_train_s), n_test=len(X_test_s))

    importances = extract_feature_importance(stack, FEATURE_COLUMNS)

    # Run 5-fold CV for credibility (Priority 2)
    cv_results = full_cv_stacking(X_full, y_full, scaler)

    quick_cv(X_full, y_full, scaler)

    print("\n── Generating plots ─────────────────────────────────")
    plot_roc_comparison(all_probas, y_test)
    plot_model_comparison_chart(comparison_df)
    plot_results(proba, preds, y_test, importances)

    print("\n── SHAP explainability artefacts ─────────────────────")
    export_shap_artifacts(stack, X_test_s, FEATURE_COLUMNS)

    save_all(calibrated, scaler, importances)

    print("\n" + "=" * 55)
    print(f"  Training complete")
    print(f"  Stacking Ensemble AUC : {auc:.4f}")
    print(f"  Deployed model         : stacking_ensemble (models/ensemble.pkl)")
    print(f"  Comparison table       : results/model_comparison.csv")
    print(f"  Run main.py next")
    print("=" * 55)
