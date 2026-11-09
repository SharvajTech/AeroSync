"""
gnn/baseline.py
────────────────────────────────────────────────────────────────────────────
Simple NON-GRAPH baseline: Logistic Regression on the exact same 9 node
features, trained/evaluated on the exact same node train/val/test masks —
but with the edges thrown away. It never sees a neighbor's raw feature
vector, never does multi-hop aggregation, and gets no gradient signal from
the graph structure itself.

Note it still gets SOME graph-derived information: two of the 9 features
(`incoming_delay_signal`, `num_connections`) are themselves computed FROM
the edges as fixed, hand-engineered numbers. That's intentional — it means
any advantage the GNN shows has to come from LEARNED multi-hop
representations, not merely from "having access to the graph at all", which
is the fairer and more interesting comparison.
"""
from sklearn.linear_model import LogisticRegression

RANDOM_STATE = 42


def train_and_evaluate_baseline(X, y, train_mask, test_mask):
    clf = LogisticRegression(max_iter=1000, random_state=RANDOM_STATE,
                              class_weight="balanced")
    clf.fit(X[train_mask], y[train_mask])
    proba_test = clf.predict_proba(X[test_mask])[:, 1]
    return clf, proba_test
