"""
train_gnn.py
────────────────────────────────────────────────────────────────────────────
Trains a small GraphSAGE-style GNN to predict CASCADE RISK — the
probability that a flight becomes affected by an upstream disruption —
over a reproducible synthetic flight-network graph (see graph_dataset.py).

    Input -> GraphSAGE layer -> GraphSAGE layer -> Linear layer -> cascade probability

Run:
    cd aerosync-ml/gnn
    python train_gnn.py

Uses PyTorch automatically if `import torch` succeeds in this environment,
otherwise a from-scratch NumPy implementation of the identical architecture
(see model.py / model_numpy.py / README "A note on the PyTorch install").

Does NOT touch the tabular pipeline (train_model.py), the NetworkX cascade
simulator, Spring Boot, or RAG — this is a standalone module, per the task
scope ("do NOT yet integrate into Spring Boot or RAG").
"""
import os
import json
import time
from datetime import datetime

import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from sklearn.metrics import (
    roc_auc_score, roc_curve, precision_score, recall_score,
    f1_score, accuracy_score, confusion_matrix, brier_score_loss
)

from graph_dataset import build_graph_dataset, save_dataset, RANDOM_STATE
from graph_utils import build_dense_mean_adjacency, standardize_features, stratified_node_masks
from model import GraphSAGECascadeNet, BACKEND_NAME
from baseline import train_and_evaluate_baseline

RESULTS_DIR = "results"
PLOTS_DIR = "plots"
MODELS_DIR = "models"
EPOCHS = 300
LR = 0.02
WEIGHT_DECAY = 5e-4
HIDDEN_DIM = 32
EVAL_EVERY = 25


def evaluate(y_true, proba, threshold=0.5):
    preds = (proba >= threshold).astype(int)
    return {
        "Accuracy":  float(accuracy_score(y_true, preds)),
        "Precision": float(precision_score(y_true, preds, zero_division=0)),
        "Recall":    float(recall_score(y_true, preds, zero_division=0)),
        "F1":        float(f1_score(y_true, preds, zero_division=0)),
        "ROC-AUC":   float(roc_auc_score(y_true, proba)),
        "Brier":     float(brier_score_loss(y_true, proba)),
        "confusion_matrix": confusion_matrix(y_true, preds).tolist(),
    }


def main():
    os.makedirs(RESULTS_DIR, exist_ok=True)
    os.makedirs(PLOTS_DIR, exist_ok=True)
    os.makedirs(MODELS_DIR, exist_ok=True)

    print("=" * 60)
    print("  AeroSync — GNN Cascade-Risk Training")
    print("=" * 60)
    print(f"Backend: {BACKEND_NAME}")

    print("\n── Building flight-network graph ─────────────────────")
    ds = build_graph_dataset(seed=RANDOM_STATE)
    save_dataset(ds)

    X_raw = ds["node_features"]
    y = ds["labels"].astype(np.float32)
    edge_index = ds["edge_index"]
    feature_names = ds["feature_names"]
    N = X_raw.shape[0]

    train_mask, val_mask, test_mask = stratified_node_masks(ds["labels"])
    print(f"\nNode split — train: {train_mask.sum()}  val: {val_mask.sum()}  "
          f"test: {test_mask.sum()}  (stratified, transductive — every "
          f"node's features are visible throughout; only labels are masked)")

    X, feat_mean, feat_std = standardize_features(X_raw, train_mask)
    A_mean = build_dense_mean_adjacency(edge_index, N)

    print("\n── Training GNN ───────────────────────────────────────")
    model = GraphSAGECascadeNet(in_dim=X.shape[1], hidden_dim=HIDDEN_DIM, seed=RANDOM_STATE)
    t0 = time.perf_counter()
    history = []
    for epoch in range(1, EPOCHS + 1):
        loss = model.train_step(X, A_mean, y, train_mask, lr=LR, weight_decay=WEIGHT_DECAY)
        if epoch % EVAL_EVERY == 0 or epoch == 1 or epoch == EPOCHS:
            val_proba = model.predict_proba(X, A_mean)[val_mask]
            val_auc = roc_auc_score(y[val_mask], val_proba)
            print(f"  epoch {epoch:4d}  train_loss={loss:.4f}  val_ROC-AUC={val_auc:.4f}")
            history.append({"epoch": epoch, "train_loss": loss, "val_auc": float(val_auc)})
    fit_time = time.perf_counter() - t0
    print(f"── Training complete ({fit_time:.1f}s, {BACKEND_NAME}) ──")

    all_proba = model.predict_proba(X, A_mean)
    gnn_test_proba = all_proba[test_mask]
    gnn_metrics = evaluate(y[test_mask], gnn_test_proba)
    gnn_metrics["fit_time_s"] = fit_time
    n_gnn_params = (
        sum(p.numel() for p in model.net.parameters())
        if BACKEND_NAME == "pytorch"
        else sum(a.size for a in model.state_dict().values())
    )
    gnn_metrics["n_parameters"] = int(n_gnn_params)

    print("\n── GNN test-set results ──────────────────────────────")
    for k in ["Accuracy", "Precision", "Recall", "F1", "ROC-AUC", "Brier"]:
        print(f"  {k:10s}: {gnn_metrics[k]:.4f}")
    print(f"  Confusion matrix [[TN,FP],[FN,TP]]: {gnn_metrics['confusion_matrix']}")

    print("\n── Training non-graph baseline (Logistic Regression) ──")
    baseline_clf, baseline_test_proba = train_and_evaluate_baseline(
        X, ds["labels"], train_mask, test_mask)
    baseline_metrics = evaluate(y[test_mask], baseline_test_proba)
    print("── Baseline test-set results ─────────────────────────")
    for k in ["Accuracy", "Precision", "Recall", "F1", "ROC-AUC", "Brier"]:
        print(f"  {k:10s}: {baseline_metrics[k]:.4f}")

    print("\n" + "=" * 60)
    print("  GNN vs. non-graph baseline (same features, same split)")
    print("=" * 60)
    for k in ["Accuracy", "Precision", "Recall", "F1", "ROC-AUC", "Brier"]:
        better = "lower better" if k == "Brier" else "higher better"
        gnn_v, base_v = gnn_metrics[k], baseline_metrics[k]
        winner = "GNN" if (gnn_v > base_v) != (k == "Brier") else \
                 ("Baseline" if gnn_v != base_v else "tie")
        print(f"  {k:10s}  GNN={gnn_v:.4f}  Baseline={base_v:.4f}  "
              f"→ {winner} ({better})")

    # ── Save results ────────────────────────────────────────────────────
    payload = {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "backend": BACKEND_NAME,
        "graph": {
            "n_nodes": int(N),
            "n_edges_directed_pairs": int(edge_index.shape[1] // 2),
            "cascade_risk_positive_rate": float(y.mean()),
        },
        "split": {
            "train": int(train_mask.sum()), "val": int(val_mask.sum()),
            "test": int(test_mask.sum()), "random_state": RANDOM_STATE,
        },
        "hyperparameters": {
            "hidden_dim": HIDDEN_DIM, "epochs": EPOCHS, "lr": LR,
            "weight_decay": WEIGHT_DECAY, "architecture":
                "Input -> SAGE(hidden) -> SAGE(hidden) -> Linear(1) -> sigmoid",
        },
        "gnn_test_metrics": gnn_metrics,
        "baseline_test_metrics": baseline_metrics,
        "training_history": history,
        "feature_names": feature_names,
        "note": ("GNN vs. baseline share identical features and the identical "
                 "train/test node split; the baseline discards the graph "
                 "edges, so any GNN advantage reflects learned multi-hop "
                 "message passing, not merely having graph-derived features."),
    }
    with open(f"{RESULTS_DIR}/gnn_comparison.json", "w") as f:
        json.dump(payload, f, indent=2)
    print(f"\n  ✅ {RESULTS_DIR}/gnn_comparison.json")

    # ── Save model ──────────────────────────────────────────────────────
    model_path = f"{MODELS_DIR}/gnn_model"
    if BACKEND_NAME == "pytorch":
        import torch
        torch.save({
            "state_dict": model.state_dict(),
            "in_dim": X.shape[1], "hidden_dim": HIDDEN_DIM,
            "feat_mean": feat_mean, "feat_std": feat_std,
            "feature_names": feature_names,
        }, model_path + ".pt")
        print(f"  ✅ {model_path}.pt")
    else:
        import joblib
        joblib.dump({
            "state_dict": model.state_dict(),
            "in_dim": X.shape[1], "hidden_dim": HIDDEN_DIM,
            "feat_mean": feat_mean, "feat_std": feat_std,
            "feature_names": feature_names,
        }, model_path + ".joblib")
        print(f"  ✅ {model_path}.joblib")

    # ── Plots ───────────────────────────────────────────────────────────
    plt.figure(figsize=(7, 6))
    for name, proba in [("GNN (GraphSAGE)", gnn_test_proba),
                         ("Baseline (Logistic Regression)", baseline_test_proba)]:
        fpr, tpr, _ = roc_curve(y[test_mask], proba)
        auc_v = roc_auc_score(y[test_mask], proba)
        plt.plot(fpr, tpr, linewidth=2, label=f"{name} (AUC={auc_v:.3f})")
    plt.plot([0, 1], [0, 1], "k--", linewidth=0.8)
    plt.xlabel("False Positive Rate"); plt.ylabel("True Positive Rate")
    plt.title("Cascade-Risk ROC — GNN vs. Non-Graph Baseline")
    plt.legend(loc="lower right")
    plt.tight_layout()
    plt.savefig(f"{PLOTS_DIR}/gnn_roc_comparison.png", dpi=150)
    plt.close()
    print(f"  ✅ {PLOTS_DIR}/gnn_roc_comparison.png")

    cm = np.array(gnn_metrics["confusion_matrix"])
    plt.figure(figsize=(5, 4))
    plt.imshow(cm, cmap="Blues")
    for i in range(2):
        for j in range(2):
            plt.text(j, i, str(cm[i, j]), ha="center", va="center",
                      color="white" if cm[i, j] > cm.max() / 2 else "black")
    plt.xticks([0, 1], ["Not cascade-affected", "Cascade-affected"])
    plt.yticks([0, 1], ["Not cascade-affected", "Cascade-affected"])
    plt.xlabel("Predicted"); plt.ylabel("Actual")
    plt.title("Confusion Matrix — GNN (test set)")
    plt.tight_layout()
    plt.savefig(f"{PLOTS_DIR}/gnn_confusion_matrix.png", dpi=150)
    plt.close()
    print(f"  ✅ {PLOTS_DIR}/gnn_confusion_matrix.png")

    metrics_to_plot = ["Accuracy", "Precision", "Recall", "F1", "ROC-AUC"]
    x = np.arange(len(metrics_to_plot)); width = 0.35
    plt.figure(figsize=(8, 5))
    plt.bar(x - width / 2, [gnn_metrics[m] for m in metrics_to_plot], width, label="GNN")
    plt.bar(x + width / 2, [baseline_metrics[m] for m in metrics_to_plot], width, label="Baseline (LR)")
    plt.xticks(x, metrics_to_plot)
    plt.ylim(0, 1.05)
    plt.ylabel("Score")
    plt.title("GNN vs. Non-Graph Baseline — Same Features, Same Split")
    plt.legend()
    plt.tight_layout()
    plt.savefig(f"{PLOTS_DIR}/gnn_vs_baseline.png", dpi=150)
    plt.close()
    print(f"  ✅ {PLOTS_DIR}/gnn_vs_baseline.png")

    print("\n" + "=" * 60)
    print("  Done. See results/gnn_comparison.json and plots/*.png")
    print("=" * 60)


if __name__ == "__main__":
    main()
