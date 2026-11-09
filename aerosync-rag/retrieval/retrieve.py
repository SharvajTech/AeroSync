"""
retrieve.py
────────────────────────────────────────────────────────────────────────────
Loads the FAISS index built by build_index.py and runs a top-k similarity
search for a natural-language query.

Run:
    cd aerosync-rag/retrieval
    python retrieve.py "Can this crew be reassigned if their duty period is already close to the limit?"
    python retrieve.py "your question here" --top_k 5
"""
import os
import sys
import argparse

from faiss_retriever import FaissRetriever

INDEX_DIR = os.path.join(os.path.dirname(__file__), "..", "index")

DEFAULT_QUERY = "Can this crew be reassigned if their duty period is already close to the limit?"


def retrieve(query: str, top_k: int = 3, index_dir: str = INDEX_DIR):
    retriever = FaissRetriever.load(index_dir)
    return retriever.retrieve(query, top_k=top_k)


def main():
    parser = argparse.ArgumentParser(description="Query the FAISS policy index.")
    parser.add_argument("query", nargs="?", default=DEFAULT_QUERY,
                         help="Natural-language question (default: the example crew-duty query)")
    parser.add_argument("--top_k", type=int, default=3, help="Number of chunks to return")
    args = parser.parse_args()

    if not os.path.exists(os.path.join(INDEX_DIR, "flight_policies.faiss")):
        print("No index found — run `python build_index.py` first.")
        sys.exit(1)

    results = retrieve(args.query, top_k=args.top_k)

    print(f"Query: {args.query}\n")
    for r in results:
        print(f"[{r.score:.3f}] {r.doc_name} :: {r.section}  (chunk_id={r.chunk_id})")
        print(f"    {r.text[:200]}...\n")


if __name__ == "__main__":
    main()
