# aerosync-rag — Aviation Operations Knowledge Base

A small, independently-testable retrieval pipeline over a set of synthetic
aviation operations policy documents, so AeroSync can eventually retrieve
relevant SOP/policy context when explaining a proposed disruption
resolution. Two retrieval backends now exist side by side, behind the same
interface: **sparse (TF-IDF)** and **dense (embeddings + FAISS)**.

**Still no LLM, still no Spring Boot.** Both parts implemented so far are
retrieval only — nothing generates an explanation yet, nothing is wired
into the backend.

## Documents

All five documents under `documents/` are **synthetic, written for this
project** — not sourced from, and not to be presented as, actual DGCA
regulations or any real airline's official manual. Each file says so at
the top, in a `DOCUMENT STATUS: SYNTHETIC / DEMONSTRATION ONLY` header.

| File | Covers |
|---|---|
| `crew_operations_policy.txt` | Duty time limits, crew reassignment rules, standby activation |
| `gate_operations_policy.txt` | Gate assignment priority, minimum turnaround by aircraft type, reassignment triggers |
| `aircraft_swap_policy.txt` | When a swap should be considered, eligibility conditions, cascade cost evaluation |
| `passenger_rebooking_policy.txt` | Rebooking priority order, automatic vs. manual rebooking, compensation thresholds |
| `disruption_response_sop.txt` | The end-to-end response sequence: detection → cascade assessment → options → human review → execution → post-event review |

Each document uses `## Section Title` headers, which the loader parses
directly — see `ingestion/loader.py`.

## Pipeline (both backends)

```
documents/*.txt
      │
      ▼
ingestion/loader.py     — parses each file into a Document with named Sections
      │                    (preamble/title/document_id captured, not dropped)
      ▼
ingestion/chunker.py    — splits each Section into one or more Chunks,
      │                    each carrying: chunk_id, doc_name, doc_title,
      │                    document_id, section, text, word_count
      ▼
  ┌───────────────────────┴───────────────────────┐
  ▼                                                 ▼
retrieval/retriever.py                    retrieval/embeddings.py
  TfidfRetriever                            HuggingFace sentence-transformer
  (TF-IDF + cosine similarity)              (or an offline fallback — see below)
                                                     │
                                                     ▼
                                           retrieval/faiss_retriever.py
                                             FaissRetriever — FAISS IndexFlatIP
                                             + metadata.json stored separately
```

Both `TfidfRetriever` and `FaissRetriever` implement the identical
`Retriever` interface (`.index(chunks)` / `.retrieve(query, top_k)`) —
interchangeable by anything that calls one.

Chunking: a section under 150 words stays as one chunk (true for every
section in the current five documents); a longer section would be split
into overlapping 150-word windows (30-word overlap) — see
`ingestion/chunker.py::_split_words`.

Metadata preserved on every chunk, exactly as required: **document name**
(`doc_name`), **section** (`section`), **chunk id** (`chunk_id`, globally
unique as `{doc_name}::{section}::{window_index}`) — plus `doc_title` and
`document_id` for nicer display.

## Sparse retrieval (TF-IDF)

`TfidfRetriever` (scikit-learn TF-IDF + cosine similarity) is a real,
standard retrieval method, not a stub. It matches on term overlap — it
finds "Crew Reassignment Rules" for a crew-duty query via the words
"crew", "reassign(ed)" and "duty" appearing in both, not semantic meaning.

```bash
python retrieval/retriever.py     # the exact example query from the spec
```

## Dense retrieval (HuggingFace embeddings + FAISS)

### The embedding model

`retrieval/embeddings.py::SentenceTransformerEmbedder` wraps
**`sentence-transformers/all-MiniLM-L6-v2`** — 22M parameters, 384-dim
output, the standard lightweight choice for CPU semantic search over a
small corpus like this one (sub-second to embed all 36 chunks, no GPU
needed).

### Why embeddings, not just TF-IDF

TF-IDF only matches shared *words*. A query like "the flight is running
very late and the pilot is almost out of legal hours" shares almost no
vocabulary with "Crew Reassignment Rules" (which talks about "duty
period", "reassigned", "limit") — TF-IDF would likely miss it. A
sentence-transformer embeds both into the same vector space by *meaning*,
so semantically related but differently-worded text still ends up close
together — that's the entire reason to add embeddings on top of Part 6's
TF-IDF retriever rather than stopping there.

### How FAISS retrieves similar chunks

Every chunk is embedded once (at index-build time) into a 384-dim
(or fallback: 128-dim) vector, L2-normalized, and added to a
`faiss.IndexFlatIP` — an exact inner-product index, which for normalized
vectors is mathematically equivalent to cosine similarity. At query time,
the query is embedded with the *same* model, and `index.search(query_vec,
k)` computes the inner product against every stored vector and returns the
`k` largest — exact nearest-neighbor search, no approximation, which is
both correct and fast enough at this corpus size (an approximate index
like IVF or HNSW only starts to matter at a much larger scale). Metadata
(document/section/chunk text) is **not** stored inside FAISS — FAISS only
ever holds vectors and integer row ids — so `faiss_retriever.py` keeps a
parallel `metadata.json`, indexed by row, and joins the two after search.

### Build and query the index

```bash
cd aerosync-rag/retrieval
python build_index.py                                    # build + save the FAISS index
python retrieve.py                                        # the example crew-duty query, top_k=3
python retrieve.py "your question here" --top_k 5         # any query, configurable top_k
```

`build_index.py` and `retrieve.py` are the two files requested for this
part; both are thin CLI wrappers around `FaissRetriever`, which does index
creation, index loading, query embedding, and top-k search as documented
methods (`.index()`, `.load()`, `.retrieve()`).

### A note on the embedding model in this environment

This was developed in a sandbox that could not reach HuggingFace at all —
`curl -I https://huggingface.co` returns HTTP 403 with header
`x-deny-reason: host_not_allowed` — so `SentenceTransformer(...)` cannot
download `all-MiniLM-L6-v2`'s weights no matter what's installed (the same
sandbox also can't reliably install PyTorch itself; see
`aerosync-ml/gnn/README.md` for the verified reason). Neither blocker
should apply to your machine.

`get_default_embedder()` tries the real model first and only falls back on
a genuine failure, to **`TfidfSvdEmbedder`** — TF-IDF + Truncated SVD
(classic LSA), fit on this corpus, producing real dense vectors so the
entire FAISS pipeline (index build, save, load, search, configurable
top-k) is genuinely exercised end to end even without network/GPU access.
It is explicitly weaker than a real transformer (fit on ~36 chunks of
vocabulary, not pretrained on a huge general corpus) and is labeled as an
offline fallback everywhere it shows up (logs, saved config, `embedder.name`).
**The results below are from this fallback** — expect similar but not
identical numbers with the real model.

### Example query (real output)

```
$ python retrieve.py
Query: Can this crew be reassigned if their duty period is already close to the limit?

[0.847] crew_operations_policy :: Crew Reassignment Rules
    Crew may be reassigned to cover a disrupted flight only if all of the
    following hold: 1. The reassignment does not push the crew member's
    projected duty period past the applicable limit...

[0.643] crew_operations_policy :: Duty Time Limits
    A cockpit or cabin crew member's duty period begins at report time and
    ends 30 minutes after the final flight of the day blocks in...

[0.355] disruption_response_sop :: Escalation
    Any event where no resolution option can be generated that avoids
    breaching a hard limit in the referenced policies (duty time, minimum
    turnaround, swap eligibility) must be escalated to a duty manager...
```

```
$ python retrieve.py "When should we swap the aircraft instead of waiting for a repair?" --top_k 5
[0.551] aircraft_swap_policy :: When a Swap Should Be Considered
[0.414] aircraft_swap_policy :: Purpose
[0.401] aircraft_swap_policy :: Preamble
[0.392] aircraft_swap_policy :: Documentation
[0.367] disruption_response_sop :: Step 3 — Option Generation
```

## Run everything

```bash
cd aerosync-rag
python test_pipeline.py                    # sparse (TF-IDF) pipeline, 4 example queries

cd retrieval
python build_index.py                       # dense pipeline: build the FAISS index
python retrieve.py                           # dense pipeline: query it
```

To use the real embedding model instead of the fallback (recommended on
your own machine): `pip install sentence-transformers faiss-cpu`, delete
`aerosync-rag/index/` if it already exists from a fallback run, then
re-run `build_index.py` — `get_default_embedder()` will pick it up
automatically, no code changes needed.

## Files

```
aerosync-rag/
├── documents/                     # 5 synthetic policy .txt files
├── ingestion/
│   ├── loader.py                  # Document/Section parsing
│   └── chunker.py                 # Chunk splitting, metadata preservation
├── retrieval/
│   ├── retriever.py               # Retriever protocol + TfidfRetriever (sparse)
│   ├── embeddings.py              # SentenceTransformerEmbedder + TfidfSvdEmbedder fallback
│   ├── faiss_retriever.py         # FaissRetriever (dense) — same Retriever interface
│   ├── build_index.py             # CLI — builds + saves the FAISS index
│   └── retrieve.py                # CLI — loads the index, runs a query, configurable top_k
├── index/                         # generated: flight_policies.faiss, metadata.json, embedder_config.json
├── test_pipeline.py               # standalone end-to-end smoke test (sparse backend)
└── README.md
```

## Explicitly out of scope so far

- No LLM call — retrieval only, nothing generates an explanation yet
- No cloud vector database — FAISS is local, on-disk, no external service
- No Spring Boot integration
- No connection to the GNN or the tabular ML pipeline — this is a fully
  independent module
