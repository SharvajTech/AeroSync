"""
test_pipeline.py
────────────────────────────────────────────────────────────────────────────
Independent smoke test for the RAG pipeline — run this on its own, with no
other AeroSync component running, to verify loading -> chunking ->
retrieval works end to end.

    cd aerosync-rag
    python test_pipeline.py
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "ingestion"))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "retrieval"))

from loader import load_documents          # noqa: E402
from chunker import chunk_documents          # noqa: E402
from retriever import TfidfRetriever         # noqa: E402

DOCUMENTS_DIR = os.path.join(os.path.dirname(__file__), "documents")

EXAMPLE_QUERIES = [
    "Can this crew be reassigned if their duty period is already close to the limit?",
    "When should we swap the aircraft instead of waiting for a repair?",
    "How do we decide which passengers get rebooked first after a cancellation?",
    "What counts as a severe disruption that needs human review?",
]


def main():
    print("── Loading documents ─────────────────────────────────")
    docs = load_documents(DOCUMENTS_DIR)
    assert len(docs) == 5, f"expected 5 policy documents, found {len(docs)}"
    for d in docs:
        print(f"  {d.doc_name:28s} id={d.document_id:14s} "
              f"{len(d.sections)} sections")

    print("\n── Chunking ───────────────────────────────────────────")
    chunks = chunk_documents(docs)
    assert len(chunks) > 0, "chunking produced no chunks"
    for c in chunks[:3]:
        assert c.doc_name and c.section and c.chunk_id, "chunk missing metadata"
    print(f"  {len(docs)} documents → {len(chunks)} chunks "
          f"(metadata verified: doc_name, section, chunk_id present)")

    print("\n── Indexing (TF-IDF) ───────────────────────────────────")
    retriever = TfidfRetriever()
    retriever.index(chunks)
    print(f"  Indexed {len(chunks)} chunks")

    print("\n── Example queries ─────────────────────────────────────")
    for query in EXAMPLE_QUERIES:
        results = retriever.retrieve(query, top_k=3)
        assert len(results) > 0, f"no results for query: {query}"
        print(f"\nQuery: {query}")
        for r in results:
            print(f"  [{r.score:.3f}] {r.doc_name} :: {r.section}")
            print(f"      {r.text[:140]}...")

    print("\n✅ All checks passed — pipeline runs independently end to end.")


if __name__ == "__main__":
    main()
