"""
gnn/model_numpy.py
────────────────────────────────────────────────────────────────────────────
A from-scratch NumPy implementation of the exact architecture requested:

    Input -> GraphSAGE layer -> GraphSAGE layer -> Linear layer -> cascade probability

Each "GraphSAGE layer" here is the standard mean-aggregator formulation
(Hamilton et al., 2017): for node i,

    h_i = ReLU( W · concat(x_i, mean_{j in N(i)}(x_j)) + b )

implemented for the whole graph at once as dense matrix ops (N is small —
a few thousand flights — so a dense (N,N) mean-adjacency is simpler to
read than a sparse/scatter implementation, at the cost of not scaling to
huge graphs; see graph_utils.build_dense_mean_adjacency).

WHY THIS FILE EXISTS: this was developed in a sandboxed environment where
`pip install torch` could not complete (see README "A note on the PyTorch
install" for the exact, verified reason — it isn't a code issue). This file
mirrors model_torch.py's architecture and train_gnn.py's training loop
line-for-line in spirit, so the two are interchangeable: train_gnn.py uses
model_torch when `import torch` succeeds, and this file otherwise. On a
normal machine with a working PyTorch install, this file is simply never
imported.
"""
import numpy as np


class _SAGELayer:
    """concat(self, mean-neighbor) -> Linear -> ReLU, with its own Adam state."""

    def __init__(self, in_dim, out_dim, rng):
        limit = np.sqrt(6.0 / (2 * in_dim + out_dim))
        self.W = rng.uniform(-limit, limit, (2 * in_dim, out_dim)).astype(np.float32)
        self.b = np.zeros(out_dim, dtype=np.float32)
        self._reset_adam()

    def _reset_adam(self):
        self.mW = np.zeros_like(self.W); self.vW = np.zeros_like(self.W)
        self.mb = np.zeros_like(self.b); self.vb = np.zeros_like(self.b)

    def forward(self, X, A_mean, train=True):
        AGG = A_mean @ X
        C = np.concatenate([X, AGG], axis=1)
        Z = C @ self.W + self.b
        H = np.maximum(Z, 0.0)
        if train:
            self._cache = (X, C, Z, A_mean)
        return H

    def backward(self, dH):
        X, C, Z, A_mean = self._cache
        dZ = dH * (Z > 0).astype(np.float32)
        self.dW = C.T @ dZ
        self.db = dZ.sum(axis=0)
        dC = dZ @ self.W.T
        in_dim = X.shape[1]
        dX_self = dC[:, :in_dim]
        dAGG = dC[:, in_dim:]
        dX = dX_self + A_mean.T @ dAGG
        return dX

    def adam_step(self, lr, t, weight_decay=0.0, beta1=0.9, beta2=0.999, eps=1e-8):
        for pname, dname, mname, vname in [("W", "dW", "mW", "vW"), ("b", "db", "mb", "vb")]:
            param = getattr(self, pname)
            grad = getattr(self, dname)
            if weight_decay and pname == "W":
                grad = grad + weight_decay * param
            m = getattr(self, mname); v = getattr(self, vname)
            m[:] = beta1 * m + (1 - beta1) * grad
            v[:] = beta2 * v + (1 - beta2) * (grad ** 2)
            m_hat = m / (1 - beta1 ** t)
            v_hat = v / (1 - beta2 ** t)
            param -= lr * m_hat / (np.sqrt(v_hat) + eps)


class _LinearHead:
    """Final Linear(hidden_dim, 1) — returns raw logits (sigmoid applied
    outside, and the BCE-with-logits gradient simplifies to (p - y))."""

    def __init__(self, in_dim, rng):
        limit = np.sqrt(6.0 / (in_dim + 1))
        self.W = rng.uniform(-limit, limit, (in_dim, 1)).astype(np.float32)
        self.b = np.zeros(1, dtype=np.float32)
        self.mW = np.zeros_like(self.W); self.vW = np.zeros_like(self.W)
        self.mb = np.zeros_like(self.b); self.vb = np.zeros_like(self.b)

    def forward(self, X, train=True):
        Z = X @ self.W + self.b
        if train:
            self._cache = X
        return Z.squeeze(-1)

    def backward(self, dZ):
        X = self._cache
        dZ2 = dZ.reshape(-1, 1)
        self.dW = X.T @ dZ2
        self.db = dZ2.sum(axis=0)
        return dZ2 @ self.W.T

    def adam_step(self, lr, t, weight_decay=0.0, beta1=0.9, beta2=0.999, eps=1e-8):
        for pname, dname, mname, vname in [("W", "dW", "mW", "vW"), ("b", "db", "mb", "vb")]:
            param = getattr(self, pname)
            grad = getattr(self, dname)
            if weight_decay and pname == "W":
                grad = grad + weight_decay * param
            m = getattr(self, mname); v = getattr(self, vname)
            m[:] = beta1 * m + (1 - beta1) * grad
            v[:] = beta2 * v + (1 - beta2) * (grad ** 2)
            m_hat = m / (1 - beta1 ** t)
            v_hat = v / (1 - beta2 ** t)
            param -= lr * m_hat / (np.sqrt(v_hat) + eps)


class GraphSAGECascadeNet:
    """Input -> SAGE(hidden) -> SAGE(hidden) -> Linear(1) -> sigmoid."""

    BACKEND = "numpy"

    def __init__(self, in_dim, hidden_dim=32, seed=42):
        rng = np.random.default_rng(seed)
        self.layer1 = _SAGELayer(in_dim, hidden_dim, rng)
        self.layer2 = _SAGELayer(hidden_dim, hidden_dim, rng)
        self.head = _LinearHead(hidden_dim, rng)
        self._t = 0

    def _forward_logits(self, X, A_mean, train=True):
        h1 = self.layer1.forward(X, A_mean, train=train)
        h2 = self.layer2.forward(h1, A_mean, train=train)
        return self.head.forward(h2, train=train)

    def predict_proba(self, X, A_mean):
        logits = self._forward_logits(X, A_mean, train=False)
        return 1.0 / (1.0 + np.exp(-logits))

    def train_step(self, X, A_mean, y, train_mask, lr=0.01, weight_decay=1e-4):
        logits = self._forward_logits(X, A_mean, train=True)
        P = 1.0 / (1.0 + np.exp(-logits))
        eps = 1e-7
        Pc = np.clip(P, eps, 1 - eps)
        loss_terms = -(y * np.log(Pc) + (1 - y) * np.log(1 - Pc))
        loss = float(loss_terms[train_mask].mean())

        n_train = train_mask.sum()
        dZ = np.zeros_like(P)
        dZ[train_mask] = (P[train_mask] - y[train_mask]) / n_train

        dH2 = self.head.backward(dZ)
        dH1 = self.layer2.backward(dH2)
        self.layer1.backward(dH1)

        self._t += 1
        self.head.adam_step(lr, self._t, weight_decay=0.0)
        self.layer2.adam_step(lr, self._t, weight_decay=weight_decay)
        self.layer1.adam_step(lr, self._t, weight_decay=weight_decay)
        return loss

    def state_dict(self):
        return {
            "layer1_W": self.layer1.W, "layer1_b": self.layer1.b,
            "layer2_W": self.layer2.W, "layer2_b": self.layer2.b,
            "head_W": self.head.W, "head_b": self.head.b,
        }

    def load_state_dict(self, sd):
        self.layer1.W, self.layer1.b = sd["layer1_W"], sd["layer1_b"]
        self.layer2.W, self.layer2.b = sd["layer2_W"], sd["layer2_b"]
        self.head.W, self.head.b = sd["head_W"], sd["head_b"]
