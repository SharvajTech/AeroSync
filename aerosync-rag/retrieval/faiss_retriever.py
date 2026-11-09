"""
retrieval/faiss_retriever.py
────────────────────────────────────────────────────────────────────────────
Dense retrieval: Documents -> Chunking -> HuggingFace embeddings -> FAISS
index -> similarity search -> top-k chunks.

Implements the exact same `Retriever` interface as `retriever.py`'s
`TfidfRetriever` (`.index(chunks)` / `.retrieve(query, top_k)`), so either
can be used interchangeably by anything that calls a `Retriever` — this is
the swap `retriever.py` was architected for in the previous part.

Metadata is stored SEPARATELY from the FAISS index (a parallel Python list,
persisted as metadata.json), per the requirement that each FAISS result
must map back to its document/section/chunk — FAISS itself only ever
stores and returns vectors and integer row ids, never text.

Uses `faiss.IndexFlatIP` (exact inner-product search over L2-normalized
vectors, i.e. exact cosine similarity) — the simplest correct choice for a
corpus this size (a few dozen to a few thousand chunks); no approximate
index (IVF/HNSW) is needed until the corpus is much larger, and exact
search keeps this CPU-friendly and easy to reason about.
"""
import os
import json

import numpy as np
import faiss

from embeddings import get_default_embedder, load_embedder


class RetrievedChunk:
    """Matches retriever.py's RetrievedChunk shape so results from either
    backend look identical to calling code."""
    __slots__ = ("chunk_id", "doc_name", "doc_title", "document_id", "section", "text", "score")

    def __init__(self, chunk_id, doc_name, doc_title, document_id, section, text, score):
        self.chunk_id = chunk_id
        self.doc_name = doc_name
        self.doc_title = doc_title
        self.document_id = document_id
        self.section = section
        self.text = text
        self.score = score

    def __repr__(self):
        return f"RetrievedChunk({self.doc_name}::{self.section}, score={self.score:.3f})"


class FaissRetriever:
    def __init__(self, embedder=None):
        self.embedder = embedder or get_default_embedder()
        self.index_ = None
        self.metadata = []  # list[dict], row i of the FAISS index <-> metadata[i]

    def index_chunks(self, chunks) -> None:
        """Alias kept for readability in build_index.py; identical to .index()."""
        self.index(chunks)

    def index(self, chunks) -> None:
        texts = [c.text for c in chunks]
        if not self.embedder.__class__.__name__.startswith("SentenceTransformer"):
            self.embedder.fit(texts)  # fallback embedder must be fit on the corpus
        vectors = self.embedder.encode(texts).astype("float32")

        self.index_ = faiss.IndexFlatIP(vectors.shape[1])
        self.index_.add(vectors)
        self.metadata = [
            {"chunk_id": c.chunk_id, "doc_name": c.doc_name, "doc_title": c.doc_title,
             "document_id": c.document_id, "section": c.section, "text": c.text}
            for c in chunks
        ]

    def retrieve(self, query: str, top_k: int = 3) -> list:
        qvec = self.embedder.encode([query]).astype("float32")
        scores, idx = self.index_.search(qvec, top_k)
        results = []
        for score, i in zip(scores[0], idx[0]):
            if i == -1:
                continue
            m = self.metadata[i]
            results.append(RetrievedChunk(
                chunk_id=m["chunk_id"], doc_name=m["doc_name"], doc_title=m["doc_title"],
                document_id=m["document_id"], section=m["section"], text=m["text"],
                score=float(score),
            ))
        return results

    def save(self, index_dir: str) -> None:
        os.makedirs(index_dir, exist_ok=True)
        faiss.write_index(self.index_, os.path.join(index_dir, "flight_policies.faiss"))
        with open(os.path.join(index_dir, "metadata.json"), "w") as f:
            json.dump(self.metadata, f, indent=2)
        self.embedder.save(index_dir)
        print(f"  ✅ {index_dir}/flight_policies.faiss  ({self.index_.ntotal} vectors, "
              f"dim={self.index_.d})")
        print(f"  ✅ {index_dir}/metadata.json  ({len(self.metadata)} entries)")
        print(f"  ✅ {index_dir}/embedder_config.json  (embedder: {self.embedder.name})")

    @classmethod
    def load(cls, index_dir: str) -> "FaissRetriever":
        embedder = load_embedder(index_dir)
        retriever = cls(embedder=embedder)
        retriever.index_ = faiss.read_index(os.path.join(index_dir, "flight_policies.faiss"))
        with open(os.path.join(index_dir, "metadata.json")) as f:
            retriever.metadata = json.load(f)
        return retriever
