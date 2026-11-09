"""
retrieval/embeddings.py
────────────────────────────────────────────────────────────────────────────
Embedding model abstraction for the FAISS retrieval pipeline.

PRIMARY implementation: `SentenceTransformerEmbedder`, wrapping
`sentence-transformers/all-MiniLM-L6-v2` — a real HuggingFace
sentence-transformer, chosen specifically because it's the standard
lightweight, CPU-friendly choice for exactly this kind of small-corpus
semantic search (22M parameters, 384-dim output, no GPU needed, sub-second
encoding for a corpus this size). This is what `build_index.py` and
`retrieve.py` use whenever it's available.

FALLBACK implementation: `TfidfSvdEmbedder` — used ONLY when the real model
can't be loaded. See "Why a fallback exists" below before assuming this is
a design choice rather than an environment constraint.

Both implement the same interface:

    embedder.name              -> str, for logging/config
    embedder.dim                -> int, vector dimensionality
    embedder.fit(texts)          -> no-op for the pretrained model; REQUIRED
                                     before .encode() for the fallback
    embedder.encode(texts)       -> np.ndarray (len(texts), dim), float32,
                                     L2-normalized (so FAISS inner product
                                     search is equivalent to cosine similarity)
    embedder.save(path) / EmbedderClass.load(path)

## Why a fallback exists

This was developed in a sandboxed environment with two independent
blockers, both verified rather than assumed:

1. **No network path to HuggingFace's model hub.** `curl -I
   https://huggingface.co` from this sandbox returns HTTP 403 with header
   `x-deny-reason: host_not_allowed` — the egress allowlist here doesn't
   include huggingface.co or its CDN, so `SentenceTransformer(...)` cannot
   download model weights no matter what's installed.
2. **`sentence-transformers` requires PyTorch**, and (as documented in
   `aerosync-ml/gnn/README.md`) the default PyPI PyTorch wheel here needs
   several GB of CUDA runtime libraries just to `import torch`, which
   doesn't reliably fit this sandbox's disk quota.

**Neither of these should apply to your own machine.** A normal
`pip install sentence-transformers` followed by `build_index.py` will
download `all-MiniLM-L6-v2` (~80MB, cached locally after the first run)
and use the real model automatically — `get_default_embedder()` below
tries it first and only falls back if that genuinely fails.

The fallback, `TfidfSvdEmbedder`, is TF-IDF + Truncated SVD (classic LSA):
it produces real dense vectors and exercises the entire FAISS pipeline
faithfully, but it is fit ON THIS CORPUS rather than pretrained on a huge
general corpus, so it captures shared vocabulary/topic structure rather
than deeper semantic meaning — a genuinely weaker retriever than a
transformer, and the two should not be presented as equivalent.
"""
import os
import json

import numpy as np


class SentenceTransformerEmbedder:
    MODEL_NAME = "sentence-transformers/all-MiniLM-L6-v2"

    def __init__(self):
        from sentence_transformers import SentenceTransformer  # may raise ImportError
        self._model = SentenceTransformer(self.MODEL_NAME)     # may raise on download failure
        self.name = self.MODEL_NAME
        self.dim = self._model.get_sentence_embedding_dimension()

    def fit(self, texts):
        pass  # pretrained — nothing to fit

    def encode(self, texts) -> np.ndarray:
        vecs = self._model.encode(list(texts), normalize_embeddings=True)
        return np.asarray(vecs, dtype=np.float32)

    def save(self, path):
        os.makedirs(path, exist_ok=True)
        with open(os.path.join(path, "embedder_config.json"), "w") as f:
            json.dump({"type": "sentence_transformer", "model_name": self.name}, f)

    @classmethod
    def load(cls, path):
        return cls()  # re-instantiating just re-loads the (cached) pretrained model


class TfidfSvdEmbedder:
    """Offline fallback — see module docstring. Fit on the corpus once at
    index-build time; the fitted vectorizer+SVD are persisted so query-time
    encoding uses the exact same transformation."""

    def __init__(self, n_components=128):
        from sklearn.feature_extraction.text import TfidfVectorizer
        from sklearn.decomposition import TruncatedSVD
        self.name = f"tfidf_svd_{n_components}d (OFFLINE FALLBACK — not a real embedding model)"
        self.dim = n_components
        self._vectorizer = TfidfVectorizer(stop_words="english")
        self._svd = TruncatedSVD(n_components=n_components, random_state=42)
        self._fitted = False

    def fit(self, texts):
        tfidf = self._vectorizer.fit_transform(list(texts))
        n_comp = min(self.dim, tfidf.shape[1] - 1, tfidf.shape[0] - 1)
        if n_comp < self.dim:
            from sklearn.decomposition import TruncatedSVD
            self._svd = TruncatedSVD(n_components=n_comp, random_state=42)
            self.dim = n_comp
        self._svd.fit(tfidf)
        self._fitted = True

    def encode(self, texts) -> np.ndarray:
        if not self._fitted:
            raise RuntimeError("TfidfSvdEmbedder.fit(corpus_texts) must be "
                                "called before encode() — it has no pretrained weights.")
        tfidf = self._vectorizer.transform(list(texts))
        vecs = self._svd.transform(tfidf).astype(np.float32)
        norms = np.linalg.norm(vecs, axis=1, keepdims=True)
        norms[norms == 0] = 1.0
        return vecs / norms

    def save(self, path):
        import joblib
        os.makedirs(path, exist_ok=True)
        joblib.dump({"vectorizer": self._vectorizer, "svd": self._svd, "dim": self.dim},
                    os.path.join(path, "tfidf_svd_embedder.joblib"))
        with open(os.path.join(path, "embedder_config.json"), "w") as f:
            json.dump({"type": "tfidf_svd", "dim": self.dim}, f)

    @classmethod
    def load(cls, path):
        import joblib
        obj = cls.__new__(cls)
        state = joblib.load(os.path.join(path, "tfidf_svd_embedder.joblib"))
        obj._vectorizer = state["vectorizer"]
        obj._svd = state["svd"]
        obj.dim = state["dim"]
        obj._fitted = True
        obj.name = f"tfidf_svd_{obj.dim}d (OFFLINE FALLBACK — not a real embedding model)"
        return obj


def get_default_embedder(verbose=True):
    """Tries the real sentence-transformer first; falls back only on a
    genuine failure (missing package or no network to fetch weights)."""
    try:
        embedder = SentenceTransformerEmbedder()
        if verbose:
            print(f"✅ Using real embedding model: {embedder.name} (dim={embedder.dim})")
        return embedder
    except Exception as e:
        if verbose:
            print(f"⚠️  Could not load sentence-transformers model ({e.__class__.__name__}: {e})")
            print("   Falling back to TfidfSvdEmbedder — see embeddings.py docstring "
                  "for why, and how to get the real model on your machine.")
        embedder = TfidfSvdEmbedder()
        return embedder


def load_embedder(path):
    with open(os.path.join(path, "embedder_config.json")) as f:
        cfg = json.load(f)
    if cfg["type"] == "sentence_transformer":
        return SentenceTransformerEmbedder.load(path)
    return TfidfSvdEmbedder.load(path)
