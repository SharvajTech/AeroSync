"""
Stage 1 compatibility checks: the new operational network graph in the
backend (airports + flight instances + dependencies) must not change the
existing GNN cascade-risk model.

* The committed GNN artefacts still load and reproduce the recorded test
  ROC-AUC exactly (inference path unchanged).
* The GNN airport encoding (graph_dataset.AIRPORT_ENCODE) and the ML service
  airport map (main.AIRPORT_MAP) still hold the original nine airports, in
  the original order: the six extra operational airports were NOT added to
  the GNN/ML encoders, so saved models keep their input semantics.
* Every GNN airport and every route of the GNN/tabular training data exists
  in the backend's operational network (migration V2 + synthetic routes), so
  GNN-scored flights can be located on the operational graph.

Run from aerosync-ml/:  python -m pytest tests/test_gnn_compatibility.py
"""
import ast
import json
import re
import sys
from pathlib import Path

import numpy as np
import pytest

ML_DIR = Path(__file__).resolve().parents[1]
REPO_DIR = ML_DIR.parent
BACKEND_DIR = REPO_DIR / "aerosync-backend"
if str(ML_DIR) not in sys.path:
    sys.path.insert(0, str(ML_DIR))

GNN_AIRPORTS = ["BOM", "DEL", "BLR", "HYD", "CCU", "MAA", "AMD", "GOI", "PNQ"]
REFERENCE_MIGRATION = BACKEND_DIR / "src/main/resources/db/migration/V2__reference_airports.sql"
GENERATOR_SOURCE = BACKEND_DIR / "src/main/java/com/aerosync/synthetic/SyntheticNetworkGenerator.java"


def _module_constant(path: Path, name: str):
    """Read a literal module-level constant without importing the module
    (importing graph_dataset/main pulls in xgboost, FastAPI, ...)."""
    tree = ast.parse(path.read_text(encoding="utf-8"))
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(
                isinstance(t, ast.Name) and t.id == name for t in node.targets):
            return ast.literal_eval(node.value)
    raise AssertionError(f"{name} not found in {path}")


def test_gnn_airport_encoding_is_unchanged():
    encode = _module_constant(ML_DIR / "gnn" / "graph_dataset.py", "AIRPORT_ENCODE")
    assert list(encode.keys()) == GNN_AIRPORTS
    assert list(encode.values()) == list(range(len(GNN_AIRPORTS)))


def test_ml_service_airport_map_matches_gnn_encoding():
    airport_map = _module_constant(ML_DIR / "main.py", "AIRPORT_MAP")
    encode = _module_constant(ML_DIR / "gnn" / "graph_dataset.py", "AIRPORT_ENCODE")
    assert airport_map == encode


def test_saved_gnn_model_reproduces_recorded_test_metrics():
    joblib = pytest.importorskip("joblib")
    sklearn_metrics = pytest.importorskip("sklearn.metrics")
    from gnn.graph_utils import build_dense_mean_adjacency, stratified_node_masks
    from gnn.model_numpy import GraphSAGECascadeNet

    artefact = joblib.load(ML_DIR / "gnn" / "models" / "gnn_model.joblib")
    graph = np.load(ML_DIR / "gnn" / "data" / "flight_graph.npz")
    feature_names = json.loads((ML_DIR / "gnn" / "data" / "feature_names.json").read_text())
    recorded = json.loads((ML_DIR / "gnn" / "results" / "gnn_comparison.json").read_text())

    X_raw = graph["node_features"]
    labels = graph["labels"]
    assert X_raw.shape[1] == artefact["in_dim"] == len(feature_names)
    assert artefact["feature_names"] == feature_names

    X = (X_raw - artefact["feat_mean"]) / artefact["feat_std"]
    A_mean = build_dense_mean_adjacency(graph["edge_index"], X_raw.shape[0])
    model = GraphSAGECascadeNet(in_dim=artefact["in_dim"], hidden_dim=artefact["hidden_dim"], seed=42)
    model.load_state_dict(artefact["state_dict"])
    proba = np.asarray(model.predict_proba(X, A_mean)).reshape(-1)

    assert proba.shape == (X_raw.shape[0],)
    assert np.all((proba >= 0.0) & (proba <= 1.0))

    _, _, test_mask = stratified_node_masks(labels)
    auc = sklearn_metrics.roc_auc_score(labels[test_mask], proba[test_mask])
    assert auc == pytest.approx(recorded["gnn_test_metrics"]["ROC-AUC"], abs=1e-6)


def test_operational_reference_airports_include_every_gnn_airport_first():
    sql = REFERENCE_MIGRATION.read_text(encoding="utf-8")
    codes = re.findall(r"\('[0-9a-f-]{36}', '([A-Z]{3})'", sql)
    assert len(codes) == len(set(codes)) == 15
    assert codes[:len(GNN_AIRPORTS)] == GNN_AIRPORTS


def test_operational_network_serves_every_gnn_training_route():
    routes = _module_constant(ML_DIR / "data_generator.py", "ROUTES")
    source = GENERATOR_SOURCE.read_text(encoding="utf-8")
    served = {frozenset(pair) for pair in re.findall(r'new Route\("([A-Z]{3})", "([A-Z]{3})", \d+\)', source)}
    assert served, "no routes parsed from SyntheticNetworkGenerator"
    missing = sorted({tuple(sorted((o, d))) for o, d, _ in routes} - {tuple(sorted(p)) for p in served})
    assert not missing, f"GNN routes missing from the operational network: {missing}"
