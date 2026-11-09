"""
gnn/model_torch.py
────────────────────────────────────────────────────────────────────────────
The real PyTorch implementation of:

    Input -> GraphSAGE layer -> GraphSAGE layer -> Linear layer -> cascade probability

train_gnn.py imports this automatically whenever `import torch` succeeds; it
falls back to model_numpy.py (a from-scratch NumPy port of the exact same
architecture) only in environments where PyTorch itself cannot be installed
— see the README "A note on the PyTorch install" for why that applies to
the sandbox this was developed in, and why it should NOT apply to a normal
machine.

This is a plain `torch.nn.Module` — no torch_geometric dependency, so the
only install requirement is `pip install torch`. The SAGE-mean layer is
~10 lines of readable torch code rather than a library black box, which
suits "small, understandable GNN". A drop-in PyTorch Geometric version
(using `torch_geometric.nn.SAGEConv` or `GATConv`) is given at the bottom
of this file, commented out, for anyone who has PyG installed and prefers
the library layer — same architecture, same interface.
"""
import numpy as np

try:
    import torch
    import torch.nn as nn
    import torch.nn.functional as F
    TORCH_IMPORT_OK = True
except ImportError:
    TORCH_IMPORT_OK = False


if TORCH_IMPORT_OK:

    class SAGELayer(nn.Module):
        """concat(self, mean-neighbor) -> Linear -> ReLU.
        A_mean is a dense (N, N) row-normalized adjacency (mean-aggregator);
        see graph_utils.build_dense_mean_adjacency."""

        def __init__(self, in_dim, out_dim):
            super().__init__()
            self.linear = nn.Linear(2 * in_dim, out_dim)

        def forward(self, X, A_mean):
            agg = A_mean @ X                       # mean of neighbor features
            h = torch.cat([X, agg], dim=1)
            return F.relu(self.linear(h))


    class _TorchGraphSAGECascadeNet(nn.Module):
        def __init__(self, in_dim, hidden_dim=32):
            super().__init__()
            self.sage1 = SAGELayer(in_dim, hidden_dim)
            self.sage2 = SAGELayer(hidden_dim, hidden_dim)
            self.head = nn.Linear(hidden_dim, 1)

        def forward(self, X, A_mean):
            h = self.sage1(X, A_mean)
            h = self.sage2(h, A_mean)
            return self.head(h).squeeze(-1)        # raw logits


    class GraphSAGECascadeNet:
        """Thin wrapper giving this the same predict_proba/train_step
        interface as model_numpy.GraphSAGECascadeNet, so train_gnn.py
        doesn't need to know which backend it's using."""

        BACKEND = "pytorch"

        def __init__(self, in_dim, hidden_dim=32, seed=42):
            torch.manual_seed(seed)
            self.net = _TorchGraphSAGECascadeNet(in_dim, hidden_dim)
            self.optimizer = None  # built lazily once weight_decay is known

        def _ensure_optimizer(self, lr, weight_decay):
            if self.optimizer is None:
                self.optimizer = torch.optim.Adam(
                    self.net.parameters(), lr=lr, weight_decay=weight_decay)

        def predict_proba(self, X, A_mean):
            self.net.eval()
            with torch.no_grad():
                Xt = torch.as_tensor(X, dtype=torch.float32)
                At = torch.as_tensor(A_mean, dtype=torch.float32)
                logits = self.net(Xt, At)
                return torch.sigmoid(logits).numpy()

        def train_step(self, X, A_mean, y, train_mask, lr=0.01, weight_decay=1e-4):
            self._ensure_optimizer(lr, weight_decay)
            self.net.train()
            Xt = torch.as_tensor(X, dtype=torch.float32)
            At = torch.as_tensor(A_mean, dtype=torch.float32)
            yt = torch.as_tensor(y, dtype=torch.float32)
            mask = torch.as_tensor(train_mask)

            self.optimizer.zero_grad()
            logits = self.net(Xt, At)
            loss = F.binary_cross_entropy_with_logits(logits[mask], yt[mask])
            loss.backward()
            self.optimizer.step()
            return float(loss.item())

        def state_dict(self):
            return self.net.state_dict()

        def load_state_dict(self, sd):
            self.net.load_state_dict(sd)

else:
    GraphSAGECascadeNet = None  # signals train_gnn.py to use the NumPy backend


# ──────────────────────────────────────────────────────────────────────────
# Optional: PyTorch Geometric variant (commented out — enable if you have
# torch_geometric installed and prefer the library's SAGEConv/GATConv over
# the hand-written SAGELayer above; same architecture, same interface).
# ──────────────────────────────────────────────────────────────────────────
#
# from torch_geometric.nn import SAGEConv, GATConv
#
# class PyGGraphSAGECascadeNet(nn.Module):
#     def __init__(self, in_dim, hidden_dim=32, use_gat=False):
#         super().__init__()
#         Conv = GATConv if use_gat else SAGEConv
#         self.conv1 = Conv(in_dim, hidden_dim)
#         self.conv2 = Conv(hidden_dim, hidden_dim)
#         self.head = nn.Linear(hidden_dim, 1)
#
#     def forward(self, x, edge_index):
#         h = F.relu(self.conv1(x, edge_index))
#         h = F.relu(self.conv2(h, edge_index))
#         return self.head(h).squeeze(-1)
#
# # edge_index here is the (2, E) LongTensor form (graph_dataset.py already
# # saves this), not the dense A_mean matrix the hand-written layer uses.
