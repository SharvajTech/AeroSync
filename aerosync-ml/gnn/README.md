# GNN — Cascade-Risk Prediction

A small GraphSAGE-style Graph Neural Network that predicts **cascade risk**:
the probability that a flight becomes affected by an *upstream* disruption
(a delayed predecessor aircraft leg, or a delayed connecting flight).

This is a **learned** model, and it is deliberately separate from
AeroSync's existing NetworkX cascade simulator. See ["How this differs from
the NetworkX cascade simulation"](#how-this-differs-from-the-networkx-cascade-simulation)
below — they answer different questions and neither replaces the other.

## Graph structure

**Nodes** = individual flights, one synthetic day of operations (`graph_dataset.py`),
reusing the same Indian-route network, aircraft types, and route delay
rates as `data_generator.py`, so the two synthetic datasets stay consistent
with each other.

**Edges** (three kinds, all built deterministically from a synthesized daily
schedule, seed=42):

| Edge type | Meaning | Directional for labels? |
|---|---|---|
| `rotation` | this flight → the next leg flown by the **same physical aircraft** | Yes — true rotation order |
| `connection` | a different aircraft's flight departing the same airport within 90 min of this one's arrival (passenger/crew handoff) | Yes — only earlier→later |
| `same_route` | another flight on the same origin→destination pair within 3 hours (shared congestion/OD coupling) | No — contextual only, not causal |

All three are added **bidirectionally** to the graph the GNN actually trains
on (message passing benefits from seeing both directions), but only
`rotation` and `connection` edges — the ones with a real causal/temporal
order — feed into how the target label is generated (see below).

On the shipped run: **1,891 nodes**, mean degree **7.7**, edge mix
`rotation=1341, connection=3542, same_route=2379` (directed pairs before
making them bidirectional).

## Node features (9)

| # | Feature | Source |
|---|---|---|
| 1 | `delay_probability` | This flight's own intrinsic risk — scored by the **actual trained tabular ensemble** (`aerosync-ml/models/ensemble.pkl`), not a second hand-written formula |
| 2 | `weather_score` | Same synthetic weather model as `data_generator.py` |
| 3 | `crew_duty_hours` | **Accumulated across the aircraft's rotation** — leg 3 of a rotation has a higher value than leg 1, capturing real fatigue buildup |
| 4 | `gate_turnaround_mins` | Actual gap between this leg's departure and the previous leg's arrival, derived from the constructed schedule (not a free random draw) |
| 5 | `aircraft_age` | Fixed per aircraft for the whole rotation |
| 6 | `departure_hour` | From the constructed schedule clock |
| 7 | `historical_delay_rate` | Same per-route table as `data_generator.py` |
| 8 | `incoming_delay_signal` | Mean *intrinsic* `delay_probability` of this flight's causal predecessors — "how risky are the flights that feed into me", knowable in advance |
| 9 | `num_connections` | Node degree across all three edge types |

Feature 8 is intentionally the predecessors' **intrinsic** risk score, not
their *realized* delayed/not-delayed outcome — using the realized outcome
here would make it a near-restatement of the label (see below) and give
away the answer directly. This mirrors the same care taken in the tabular
pipeline's leakage audit.

## The target, precisely

Two internal quantities are computed per flight, in strict chronological
order (by scheduled departure clock, so every predecessor is always
processed first):

1. `has_upstream_disruption` — did the flight's rotation predecessor or a
   connecting flight actually end up delayed?
2. `realized_delayed` — `sigmoid-thresholded(0.72 × intrinsic_delay_probability + upstream_boost)`,
   where `upstream_boost` is `0.35` if there's an upstream disruption *and*
   this flight's own turnaround is tight (<30 min), `0.15` if there's an
   upstream disruption with normal turnaround, else `0`.

**`cascade_risk_label = 1` iff `realized_delayed == 1 AND has_upstream_disruption == 1`.**

That is: not just "is this flight delayed" (the tabular model's job), but
"is this flight delayed *and* is that tied to something upstream". On the
shipped run this is **27.2%** positive (515 / 1,891) — a meaningful
minority class, not degenerate. Because propagation is computed leg-by-leg
along real rotation chains, the effect is genuinely multi-hop: a leg's risk
depends on its predecessor's realized outcome, which already folded in
*its* predecessor's outcome, and so on — a real chain, not a one-step toy.

## Architecture

```
Input (9 features)
   ↓
GraphSAGE layer  (concat(self, mean of neighbor features) → Linear → ReLU)   [hidden=32]
   ↓
GraphSAGE layer  (same)                                                       [hidden=32]
   ↓
Linear layer → sigmoid
   ↓
Cascade probability
```

Each "GraphSAGE layer" is the standard mean-aggregator form (Hamilton et
al., 2017): concatenate a node's own features with the *mean* of its
neighbors' features, then a linear layer + ReLU. Two layers means each
node's final representation is influenced by its 2-hop neighborhood —
enough to see "my predecessor's predecessor" through the chain, which is
exactly the kind of pattern the non-graph baseline (one fixed, pre-computed
aggregate feature) can't fully capture.

Trained with Adam (lr=0.02, weight_decay=5e-4, 300 epochs, full-batch — the
graph is small enough that batching isn't needed), binary cross-entropy
loss computed **only over the training-node mask** (see "train/val/test
split" below).

## A note on the PyTorch install

This was built with `torch.nn` as the primary, intended implementation —
see `model_torch.py`, a plain `nn.Module` with a hand-written SAGE layer (no
`torch_geometric` required; a commented-out PyTorch Geometric variant using
`SAGEConv`/`GATConv` is at the bottom of that file for anyone who has PyG
installed and prefers it).

**In the sandboxed environment this was developed in, `pip install torch`
could not be made to import.** This was verified in detail, not assumed:
the default PyPI wheel for this platform is a CUDA-13 build whose core
extension (`libtorch_python.so`) has hard `DT_NEEDED` links to
`libcudart.so.13`, `libcudnn.so.9`, `libnccl.so.2`, etc. even for pure-CPU
tensor ops (confirmed with `readelf -d`), and installing the full matching
`nvidia-*-cu13` package set needed to satisfy those links is several
gigabytes — more than this sandbox's disk quota reliably supports (it
caused two `ENOSPC` failures during the attempt). There is no network path
from this sandbox to `download.pytorch.org`, which hosts the genuinely
CPU-only wheels that avoid this entirely.

**This should not affect you.** `train_gnn.py` auto-detects PyTorch (`import
torch`) and uses `model_torch.py` automatically when available — which is
the normal case on an ordinary machine with normal disk space, e.g. via
`pip install torch` or, for a much smaller CPU-only download,
`pip install torch --index-url https://download.pytorch.org/whl/cpu`. Only
when `import torch` fails does it fall back to `model_numpy.py`, a
from-scratch NumPy port of the *exact same architecture* (hand-derived
forward/backward pass, Adam optimizer, same layer sizes) — written so the
deliverable trains and produces real results in any environment, including
this one. The results below were produced by that NumPy fallback; expect
very similar (not necessarily identical) numbers from the PyTorch path.

## Train/val/test split

Transductive node-classification setup (Kipf & Welling): every node's
**features** stay visible to the model throughout, for message passing —
only the **labels** are masked off for validation/test. This is standard
for GNN node classification and is deliberately different from the tabular
model's separate train/test *rows* — worth being able to explain the
distinction if asked. Split is stratified by label, 70/15/15, seed=42.

## Results (this run — NumPy backend; see `results/gnn_comparison.json`)

| Metric | GNN | Non-graph baseline (Logistic Regression) |
|---|---|---|
| Accuracy | 0.9860 | 0.9333 |
| Precision | 0.9868 | 0.8391 |
| Recall | 0.9615 | 0.9359 |
| F1 | 0.9740 | 0.8848 |
| ROC-AUC | 0.9972 | 0.9843 |
| Brier Score ↓ | 0.0114 | 0.0468 |
| Confusion matrix (test) | `[[206, 1], [3, 75]]` | — |

The GNN wins on every metric here, most clearly on Precision and Brier —
consistent with the design intent: the baseline gets one pre-computed,
1-hop `incoming_delay_signal` feature, while the GNN's two SAGE layers can
combine raw neighbor features over 2 hops, capturing more of the rotation
chain. Both numbers are high in absolute terms because the synthetic
label-generation rule is a fairly clean function of the graph — the
meaningful comparison is the *gap* between the two, not the absolute
values in isolation.

## Files

```
gnn/
├── graph_dataset.py   # builds the reproducible synthetic flight-network graph
├── graph_utils.py     # dense mean-adjacency, feature scaling, node-mask splitting
├── model_torch.py     # real PyTorch nn.Module (primary implementation)
├── model_numpy.py     # NumPy fallback, identical architecture (see note above)
├── model.py           # picks a backend automatically
├── baseline.py         # non-graph Logistic Regression baseline
├── train_gnn.py        # run this — trains, evaluates, compares, saves everything
├── data/                # generated: flight_graph.npz, flight_graph_meta.csv
├── models/              # generated: gnn_model.pt (torch) or .joblib (numpy)
├── results/             # generated: gnn_comparison.json
└── plots/               # generated: gnn_roc_comparison.png, gnn_confusion_matrix.png,
                          #            gnn_vs_baseline.png
```

## Run it

```bash
cd aerosync-ml/gnn
python train_gnn.py
```

No CLI arguments — all hyperparameters are constants at the top of
`train_gnn.py`. Takes a few seconds (NumPy backend) to a couple of minutes
(PyTorch, first-run overhead) on CPU.

## How this differs from the NetworkX cascade simulation

The existing `aerosync-backend`'s cascade logic (NetworkX, graph
construction + traversal) is **not** a GNN, and this project is careful not
to describe it as one:

| | NetworkX cascade simulator (existing) | GNN (this module) |
|---|---|---|
| What it is | Deterministic graph traversal (BFS/DFS) over explicit, rule-based propagation logic | A learned statistical model, trained by gradient descent |
| Question it answers | "**Given that THIS specific flight is delayed right now**, what happens downstream?" — a what-if / forward simulation | "**Across today's whole schedule**, which flights look statistically at risk of a cascade?" — a proactive risk score |
| Input | One triggering event + the current graph state | The full graph's structure and every flight's features, all at once |
| Output | An explicit, explainable propagation trace (which flights, how delayed, why) | A probability per flight, with no built-in causal trace |
| Where it fits | `ResolutionAlgorithmService` — deciding what to do about a disruption that has already happened | Triage/monitoring — flagging flights worth watching before anything has gone wrong |

They're complementary, not competing: the GNN could eventually flag
*candidates* worth simulating; the simulator explains *what would happen*
if one of them tips over. **Part 5** (not this part) is where any actual
wiring into the backend happens — this module is intentionally standalone
for now.
