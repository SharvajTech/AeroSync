"""
build_index.py
────────────────────────────────────────────────────────────────────────────
Documents -> Chunking -> HuggingFace embeddings -> FAISS index -> saved to disk.

Run:
    cd aerosync-rag/retrieval
    python build_index.py
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "ingestion"))

from loader import load_documents
from chunker import chunk_documents
from embeddings import get_default_embedder
from faiss_retriever import FaissRetriever

DOCUMENTS_DIR = os.path.join(os.path.dirname(__file__), "..", "documents")
INDEX_DIR = os.path.join(os.path.dirname(__file__), "..", "index")


def main():
    print("── Loading + chunking documents ──────────────────────")
    docs = load_documents(DOCUMENTS_DIR)
    chunks = chunk_documents(docs)
    print(f"  {len(docs)} documents → {len(chunks)} chunks")

    print("\n── Loading embedding model ────────────────────────────")
    embedder = get_default_embedder()

    print("\n── Embedding chunks + building FAISS index ────────────")
    retriever = FaissRetriever(embedder=embedder)
    retriever.index_chunks(chunks)

    print("\n── Saving index ────────────────────────────────────────")
    retriever.save(INDEX_DIR)

    print("\nDone. Query it with: python retrieve.py \"<your question>\"")


if __name__ == "__main__":
    main()
