"""
retrieval/retriever.py
────────────────────────────────────────────────────────────────────────────
A simple retrieval interface: natural-language query in, ranked chunks with
metadata out.

THIS PART deliberately does NOT use embeddings or FAISS — the task scope
here is "prepare the system for HuggingFace embeddings and FAISS in the
next part". So `TfidfRetriever` uses scikit-learn's TF-IDF + cosine
similarity: a real, standard retrieval method (not a placeholder), using a
dependency already in this project, with zero new installs.

The `Retriever` interface below is the seam for that future swap: anything
implementing `.index(chunks)` and `.retrieve(query, top_k)` can be dropped
in — a future `DenseRetriever` (sentence-transformers embeddings + a FAISS
index) would implement the same two methods and nothing calling a
`Retriever` would need to change.
"""
from dataclasses import dataclass
from typing import Protocol

from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.metrics.pairwise import cosine_similarity


@dataclass
class RetrievedChunk:
    chunk_id: str
    doc_name: str
    doc_title: str
    document_id: str
    section: str
    text: str
    score: float


class Retriever(Protocol):
    """Interface every retrieval backend implements — TF-IDF today,
    a HuggingFace-embeddings + FAISS backend later, without changing
    any calling code."""

    def index(self, chunks: list) -> None: ...
    def retrieve(self, query: str, top_k: int = 3) -> list: ...


class TfidfRetriever:
    """Retrieval backend for this part of the project. Swap target for
    next part: replace the TfidfVectorizer fit/transform below with
    sentence-transformers embeddings and a faiss.IndexFlatIP (or similar);
    everything outside this class stays the same."""

    def __init__(self):
        self.vectorizer = TfidfVectorizer(stop_words="english")
        self.chunks = []
        self.matrix = None

    def index(self, chunks: list) -> None:
        self.chunks = chunks
        texts = [c.text for c in chunks]
        self.matrix = self.vectorizer.fit_transform(texts)

    def retrieve(self, query: str, top_k: int = 3) -> list:
        if self.matrix is None:
            raise RuntimeError("Call .index(chunks) before .retrieve(query).")
        query_vec = self.vectorizer.transform([query])
        scores = cosine_similarity(query_vec, self.matrix)[0]
        ranked_idx = scores.argsort()[::-1][:top_k]
        results = []
        for i in ranked_idx:
            c = self.chunks[i]
            results.append(RetrievedChunk(
                chunk_id=c.chunk_id, doc_name=c.doc_name, doc_title=c.doc_title,
                document_id=c.document_id, section=c.section, text=c.text,
                score=float(scores[i]),
            ))
        return results


def build_default_retriever(documents_dir: str = None) -> TfidfRetriever:
    """Convenience entry point: load -> chunk -> index, in one call."""
    import os
    import sys
    sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "ingestion"))
    from loader import load_documents
    from chunker import chunk_documents

    documents_dir = documents_dir or os.path.join(
        os.path.dirname(__file__), "..", "documents")
    docs = load_documents(documents_dir)
    chunks = chunk_documents(docs)
    retriever = TfidfRetriever()
    retriever.index(chunks)
    return retriever


if __name__ == "__main__":
    retriever = build_default_retriever()
    query = "Can this crew be reassigned if their duty period is already close to the limit?"
    print(f"Query: {query}\n")
    for r in retriever.retrieve(query, top_k=3):
        print(f"[{r.score:.3f}] {r.doc_name} :: {r.section}  (chunk_id={r.chunk_id})")
        print(f"    {r.text[:200]}...\n")
