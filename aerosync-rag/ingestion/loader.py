"""
ingestion/loader.py
────────────────────────────────────────────────────────────────────────────
Loads the plain-text policy documents under aerosync-rag/documents/ and
parses their `## Section Title` headers into a structured form, so the
chunker (chunker.py) can preserve per-section metadata rather than treating
each file as one undifferentiated blob of text.

Document format expected (see any file in documents/ for a real example):

    DOCUMENT STATUS: SYNTHETIC / DEMONSTRATION ONLY
    ...free-text preamble...

    TITLE: <human-readable title>
    DOCUMENT ID: <short id>

    ## Section One
    ...section body...

    ## Section Two
    ...section body...

Any line starting with "## " begins a new section; everything before the
first "## " is captured as a "Preamble" section (this is where the
SYNTHETIC/DEMONSTRATION notice and TITLE/DOCUMENT ID live, so that notice
travels with the chunk metadata rather than being silently dropped).
"""
import os
from dataclasses import dataclass, field


@dataclass
class Section:
    title: str
    text: str


@dataclass
class Document:
    doc_name: str          # filename without extension, e.g. "crew_operations_policy"
    file_path: str
    title: str              # from the TITLE: line, falls back to doc_name
    document_id: str        # from the DOCUMENT ID: line, falls back to ""
    sections: list = field(default_factory=list)  # list[Section]


def _parse_sections(raw_text: str) -> list:
    lines = raw_text.splitlines()
    sections = []
    current_title = "Preamble"
    current_lines = []

    for line in lines:
        if line.startswith("## "):
            if current_lines:
                sections.append(Section(current_title, "\n".join(current_lines).strip()))
            current_title = line[3:].strip()
            current_lines = []
        else:
            current_lines.append(line)

    if current_lines:
        sections.append(Section(current_title, "\n".join(current_lines).strip()))

    return [s for s in sections if s.text]  # drop empty sections


def _extract_field(raw_text: str, field_name: str) -> str:
    for line in raw_text.splitlines():
        prefix = f"{field_name}:"
        if line.strip().startswith(prefix):
            return line.strip()[len(prefix):].strip()
    return ""


def load_document(file_path: str) -> Document:
    with open(file_path, "r", encoding="utf-8") as f:
        raw_text = f.read()

    doc_name = os.path.splitext(os.path.basename(file_path))[0]
    title = _extract_field(raw_text, "TITLE") or doc_name
    document_id = _extract_field(raw_text, "DOCUMENT ID")
    sections = _parse_sections(raw_text)

    return Document(doc_name=doc_name, file_path=file_path, title=title,
                     document_id=document_id, sections=sections)


def load_documents(documents_dir: str) -> list:
    """Loads every .txt file in documents_dir. Returns list[Document],
    sorted by filename for reproducible ordering (matters for chunk_id
    numbering downstream)."""
    docs = []
    for fname in sorted(os.listdir(documents_dir)):
        if not fname.endswith(".txt"):
            continue
        docs.append(load_document(os.path.join(documents_dir, fname)))
    return docs


if __name__ == "__main__":
    here = os.path.dirname(__file__)
    docs = load_documents(os.path.join(here, "..", "documents"))
    for d in docs:
        print(f"{d.doc_name}  (id={d.document_id})  — {len(d.sections)} sections")
        for s in d.sections:
            print(f"    · {s.title}  ({len(s.text.split())} words)")
