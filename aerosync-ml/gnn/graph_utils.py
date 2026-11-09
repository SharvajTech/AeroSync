"""
gnn/graph_utils.py
────────────────────────────────────────────────────────────────────────────
Small helpers shared by both the NumPy and PyTorch model backends, so the
graph pre-processing (adjacency construction, feature scaling, mask
splitting) is identical regardless of which backend trains the model.
"""
import numpy as np
from sklearn.model_selection import train_test_split

RANDOM_STATE = 42


def build_dense_mean_adjacency(edge_index: np.ndarray, n_nodes: int) -> np.ndarray:
    """Row-normalized adjacency: A_mean[i] @ X = mean of neighbor features
    for node i. edge_index is (2, E) with columns (src, dst) meaning
    "src is a neighbor that contributes to dst's aggregation" — the graph
    is built bidirectionally already (see graph_dataset.py), so this is
    effectively symmetric mean-aggregation, standard for GraphSAGE.

    N is small here (a few thousand flights at most — "small, understandable
    GNN"), so a dense matrix is simpler to read and fast enough; a real
    fleet-scale graph would use a sparse representation instead.
    """
    A = np.zeros((n_nodes, n_nodes), dtype=np.float32)
    src, dst = edge_index
    A[dst, src] += 1.0
    deg = A.sum(axis=1, keepdims=True)
    deg[deg == 0] = 1.0  # isolated node — leave its aggregation as zero
    return A / deg


def standardize_features(X: np.ndarray, train_mask: np.ndarray):
    """Fit mean/std on TRAIN rows only (mirrors train_model.py's
    StandardScaler.fit on X_train only), apply to every row."""
    mean = X[train_mask].mean(axis=0, keepdims=True)
    std = X[train_mask].std(axis=0, keepdims=True)
    std[std == 0] = 1.0
    return (X - mean) / std, mean, std


def stratified_node_masks(labels: np.ndarray, train_frac=0.7, val_frac=0.15,
                           seed=RANDOM_STATE):
    """Transductive node-classification split: every node's FEATURES stay
    visible to the model (used in graph aggregation) throughout, only the
    LABELS are masked off for val/test — the standard setup for GNN node
    classification (Kipf & Welling), distinct from the tabular model's
    separate train/test ROWS."""
    idx = np.arange(len(labels))
    train_idx, rest_idx = train_test_split(
        idx, train_size=train_frac, random_state=seed, stratify=labels)
    val_size = val_frac / (1 - train_frac)
    val_idx, test_idx = train_test_split(
        rest_idx, train_size=val_size, random_state=seed,
        stratify=labels[rest_idx])

    def mask_of(indices):
        m = np.zeros(len(labels), dtype=bool)
        m[indices] = True
        return m

    return mask_of(train_idx), mask_of(val_idx), mask_of(test_idx)
