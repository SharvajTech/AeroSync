"""
ingestion/chunker.py
────────────────────────────────────────────────────────────────────────────
Splits each loaded Document's sections into retrieval-sized chunks,
preserving exactly the metadata requirements: document name, section, and
chunk id, plus the document's title/id for display purposes.

Chunking strategy: a section is kept as ONE chunk if it's under
max_words; otherwise it's split into overlapping word-windows so no chunk
exceeds max_words, with overlap_words repeated between consecutive chunks
so a sentence spanning a boundary isn't lost to either chunk. All five demo
documents' sections are short enough in this project that most sections
end up as a single chunk — the sliding-window path exists for
completeness (and for a longer document in the future).
"""
from dataclasses import dataclass


@dataclass
class Chunk:
    chunk_id: str        # f"{doc_name}::{section}::{index}" — globally unique
    doc_name: str
    doc_title: str
    document_id: str
    section: str
    text: str
    word_count: int


def _split_words(text: str, max_words: int, overlap_words: int):
    words = text.split()
    if len(words) <= max_words:
        yield words
        return
    step = max(1, max_words - overlap_words)
    for start in range(0, len(words), step):
        window = words[start:start + max_words]
        if not window:
            break
        yield window
        if start + max_words >= len(words):
            break


def chunk_document(document, max_words=150, overlap_words=30) -> list:
    chunks = []
    for section in document.sections:
        windows = list(_split_words(section.text, max_words, overlap_words))
        for i, words in enumerate(windows):
            chunk_id = f"{document.doc_name}::{section.title}::{i}"
            chunks.append(Chunk(
                chunk_id=chunk_id,
                doc_name=document.doc_name,
                doc_title=document.title,
                document_id=document.document_id,
                section=section.title,
                text=" ".join(words),
                word_count=len(words),
            ))
    return chunks


def chunk_documents(documents, max_words=150, overlap_words=30) -> list:
    all_chunks = []
    for doc in documents:
        all_chunks.extend(chunk_document(doc, max_words, overlap_words))
    return all_chunks


if __name__ == "__main__":
    import os
    from loader import load_documents

    here = os.path.dirname(__file__)
    docs = load_documents(os.path.join(here, "..", "documents"))
    chunks = chunk_documents(docs)
    print(f"{len(docs)} documents → {len(chunks)} chunks\n")
    for c in chunks[:5]:
        print(f"[{c.chunk_id}]  ({c.word_count} words)")
        print(f"  {c.text[:120]}...")
