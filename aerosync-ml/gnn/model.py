"""
gnn/model.py
────────────────────────────────────────────────────────────────────────────
Picks the model backend at import time: real PyTorch (model_torch.py) when
`import torch` succeeds, otherwise the NumPy fallback (model_numpy.py).
Both expose the identical interface:

    model = GraphSAGECascadeNet(in_dim, hidden_dim=32, seed=42)
    loss  = model.train_step(X, A_mean, y, train_mask, lr=0.01, weight_decay=1e-4)
    proba = model.predict_proba(X, A_mean)
    model.state_dict() / model.load_state_dict(sd)

train_gnn.py imports BACKEND_NAME to report which one actually ran.
"""
import model_torch

if model_torch.TORCH_IMPORT_OK:
    from model_torch import GraphSAGECascadeNet
    BACKEND_NAME = "pytorch"
else:
    from model_numpy import GraphSAGECascadeNet
    BACKEND_NAME = "numpy (PyTorch unavailable in this environment — see README)"
