# aerosync-dashboard — Operations Dashboard (Streamlit)

A lightweight, single-file Streamlit dashboard for demonstrating AeroSync's
full pipeline. Deliberately not a React app — this is for demoing the
system, not a production operations UI.

```
Flight -> Delay ML -> GNN Cascade -> Constraint Engine -> RAG -> LLM Explanation -> Human Approval
```

## Two integration modes (visible in the sidebar, always)

Most panels work **with no other service running** — the dashboard imports
`aerosync-ml/main.py` and `aerosync-rag/` directly, in-process, the same
code `/predict` and `/rag/retrieve` run:

| Panel | Without any server running | With FastAPI / Spring Boot running |
|---|---|---|
| Delay ML + SHAP | ✅ in-process (`main.py`) | ✅ live `POST /predict` |
| GNN cascade-risk | ✅ loads saved model/results directly | (not yet wired to a live endpoint — see Roadmap) |
| Cascade visualization | ✅ from the GNN's saved graph data | — |
| RAG evidence | ✅ in-process (`aerosync-rag`) | ✅ live `POST /rag/retrieve` |
| Disruption simulation | ⚠️ illustrative only | ✅ live `POST /api/disruptions/trigger` |
| Proposed resolution (Decision/Evidence/Explanation) | ⚠️ disabled, clear message | ✅ live `POST /api/agents/propose/{id}` |
| Approve / Reject | ⚠️ disabled | ✅ live `POST /api/agents/{approve,reject}/{id}` |

The Decision/Approval panels are honestly gated behind a live backend
rather than faked, because that logic is Java-only
(`ResolutionAlgorithmService` / `AgentService`) — there's no Python
equivalent to fall back to, and reimplementing it in the dashboard would
mean two sources of truth for the same decision, which is exactly what
this project has been careful to avoid elsewhere (see the tabular ML vs.
GNN vs. NetworkX distinctions in the main README).

## Run it

```bash
cd aerosync-dashboard
pip install -r requirements.txt
streamlit run app.py
```

Opens at `http://localhost:8501`. No other service needs to be running for
the first five panels; start the FastAPI ML service and/or the Spring Boot
backend (`http://localhost:8000` / `:8080` — override with the `ML_URL` /
`BACKEND_URL` environment variables) to light up the rest.

## Demo flow

1. Open the dashboard — sidebar shows which services are live vs. offline.
2. Pick a flight from the sidebar (live flights if the backend is up,
   otherwise 15 sample flights from the synthetic dataset).
3. **Delay ML** panel shows risk level, probability, and the SHAP factor
   chart for that flight, computed live.
4. **Disruption Simulation** — pick a type, delay, passenger count, click
   *Simulate disruption*. With the backend running this creates a real
   `Disruption` row; without it, a clear offline notice is shown instead
   of a fake one.
5. **GNN Cascade-Risk** and **Cascade Visualization** show the trained
   GNN's own results and a sample rotation subgraph, colored by predicted
   cascade risk.
6. With a live disruption, click **Propose resolution** — see the three
   panels side by side: **Decision** (from the algorithm), **Evidence**
   (RAG policy chunks), **Explanation** (Gemini, or the algorithmic
   fallback if Gemini fails).
7. **Approve** or **Reject** (with a reason) — mirrors the backend's
   `PROPOSE → APPROVE/REJECT` flow exactly; nothing is written to the
   database until Approve is clicked.

## Files

```
aerosync-dashboard/
├── app.py              # the whole dashboard — one file, ~350 lines
├── requirements.txt
└── README.md
```
