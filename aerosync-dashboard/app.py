"""
aerosync-dashboard/app.py
────────────────────────────────────────────────────────────────────────────
A lightweight Streamlit dashboard demonstrating AeroSync's full pipeline:

    Flight -> Delay ML -> GNN cascade -> Constraint engine -> RAG -> LLM explanation -> Human approval

Design choice — two integration modes, mixed per panel, always visible to
the user via the sidebar status indicators:

  * ML prediction, SHAP factors, GNN cascade-risk, RAG evidence all run
    IN-PROCESS by importing aerosync-ml/main.py and aerosync-rag directly
    (same code /predict and /rag/retrieve run) — no server needs to be
    running for these panels to work. This is what makes the dashboard
    runnable immediately after `python train_gnn.py` / `build_index.py`,
    with nothing else started.
  * Disruption simulation, the proposed resolution (Decision), and
    Approve/Reject are Java-only logic (ResolutionAlgorithmService /
    AgentService) with no Python equivalent — these panels call the live
    Spring Boot backend over HTTP and are honestly disabled with a clear
    message when it isn't reachable, rather than faking a decision.

Run:
    cd aerosync-dashboard
    pip install -r requirements.txt
    streamlit run app.py
"""
import os
import sys
import json

import numpy as np
import pandas as pd
import requests
import streamlit as st
import plotly.graph_objects as go

# ── Paths ────────────────────────────────────────────────────────────────────
HERE     = os.path.dirname(os.path.abspath(__file__))
ML_DIR   = os.path.join(HERE, "..", "aerosync-ml")
RAG_DIR  = os.path.join(HERE, "..", "aerosync-rag")
GNN_DIR  = os.path.join(ML_DIR, "gnn")

BACKEND_URL = os.environ.get("BACKEND_URL", "http://localhost:8080")
ML_URL      = os.environ.get("ML_URL", "http://localhost:8000")
TIMEOUT     = 3

st.set_page_config(page_title="AeroSync Ops Dashboard", page_icon="✈️", layout="wide")


# ── Connectivity ─────────────────────────────────────────────────────────────
@st.cache_data(ttl=10)
def backend_status():
    try:
        r = requests.get(f"{BACKEND_URL}/api/flights", timeout=TIMEOUT)
        return r.status_code in (200, 401, 403)
    except Exception:
        return False


@st.cache_data(ttl=10)
def ml_service_status():
    try:
        r = requests.get(f"{ML_URL}/health", timeout=TIMEOUT)
        return r.status_code == 200
    except Exception:
        return False


# ── In-process ML / SHAP / RAG (imports aerosync-ml/main.py directly) ───────
@st.cache_resource(show_spinner="Loading ML pipeline (ensemble + SHAP + RAG)...")
def load_ml_backend():
    # main.py loads its artifacts via relative paths ("models/ensemble.pkl"),
    # which only resolve correctly when the process's CWD is aerosync-ml/
    # (true for `python main.py`, not true for this dashboard's own CWD).
    # Fixed here, not in main.py — no reason to touch working, tested code
    # for a constraint that's specific to this dashboard's import.
    original_cwd = os.getcwd()
    try:
        os.chdir(ML_DIR)
        sys.path.insert(0, ML_DIR)
        import main as ml_main  # runs main.py's startup: loads ensemble, SHAP, RAG
        return ml_main
    finally:
        os.chdir(original_cwd)


@st.cache_resource(show_spinner="Loading GNN model...")
def load_gnn_backend():
    sys.path.insert(0, GNN_DIR)
    import joblib
    from graph_utils import build_dense_mean_adjacency, standardize_features
    from model_numpy import GraphSAGECascadeNet as NumpyNet

    model_path_joblib = os.path.join(GNN_DIR, "models", "gnn_model.joblib")
    model_path_torch   = os.path.join(GNN_DIR, "models", "gnn_model.pt")
    meta_path  = os.path.join(GNN_DIR, "data", "flight_graph_meta.csv")
    graph_path = os.path.join(GNN_DIR, "data", "flight_graph.npz")
    results_path = os.path.join(GNN_DIR, "results", "gnn_comparison.json")

    if not (os.path.exists(meta_path) and os.path.exists(graph_path)):
        return None

    meta = pd.read_csv(meta_path)
    npz = np.load(graph_path)
    results = None
    if os.path.exists(results_path):
        with open(results_path) as f:
            results = json.load(f)

    node_probs = None
    if os.path.exists(model_path_joblib):
        saved = joblib.load(model_path_joblib)
        net = NumpyNet(in_dim=saved["in_dim"], hidden_dim=saved["hidden_dim"])
        net.load_state_dict(saved["state_dict"])
        X = (npz["node_features"] - saved["feat_mean"]) / saved["feat_std"]
        A = build_dense_mean_adjacency(npz["edge_index"], X.shape[0])
        node_probs = net.predict_proba(X.astype(np.float32), A)
    elif os.path.exists(model_path_torch):
        import torch
        from model_torch import _TorchGraphSAGECascadeNet
        saved = torch.load(model_path_torch, map_location="cpu")
        net = _TorchGraphSAGECascadeNet(saved["in_dim"], saved["hidden_dim"])
        net.load_state_dict(saved["state_dict"])
        net.eval()
        X = (npz["node_features"] - saved["feat_mean"]) / saved["feat_std"]
        A = build_dense_mean_adjacency(npz["edge_index"], X.shape[0])
        with torch.no_grad():
            logits = net(torch.as_tensor(X, dtype=torch.float32),
                         torch.as_tensor(A, dtype=torch.float32))
            node_probs = torch.sigmoid(logits).numpy()

    meta = meta.copy()
    if node_probs is not None:
        meta["gnn_cascade_risk"] = node_probs
    return {"meta": meta, "edge_index": npz["edge_index"], "results": results}


@st.cache_resource(show_spinner="Loading demo flight sample...")
def load_demo_flights():
    csv_path = os.path.join(ML_DIR, "data", "flight_data.csv")
    df = pd.read_csv(csv_path).sample(n=15, random_state=42).reset_index(drop=True)
    airports = ["BOM", "DEL", "BLR", "HYD", "CCU", "MAA", "AMD", "GOI", "PNQ"]
    aircraft = ["A320", "B737", "A321", "ATR72", "B777"]
    df["flight_id"] = [f"AI-{100+i}" for i in range(len(df))]
    df["origin"] = [airports[i % len(airports)] for i in range(len(df))]
    df["destination"] = [airports[(i + 3) % len(airports)] for i in range(len(df))]
    df["aircraft_type"] = [aircraft[i % len(aircraft)] for i in range(len(df))]
    return df


def risk_badge(level):
    color = {"LOW": "🟢", "MEDIUM": "🟡", "HIGH": "🔴"}.get(level, "⚪")
    return f"{color} {level}"


# ── Sidebar ──────────────────────────────────────────────────────────────────
st.sidebar.title("✈️ AeroSync")
st.sidebar.caption("AI Flight Disruption Management")

backend_up = backend_status()
ml_up = ml_service_status()

st.sidebar.markdown("### Service status")
st.sidebar.markdown(f"{'🟢' if backend_up else '🟡'} Spring Boot backend "
                     f"({'connected' if backend_up else 'offline — Decision/Approval panels limited'})")
st.sidebar.markdown(f"{'🟢' if ml_up else '🟡'} FastAPI ML service "
                     f"({'connected' if ml_up else 'offline — using in-process model'})")

ml_main = load_ml_backend()
st.sidebar.markdown(f"{'🟢' if ml_main.SHAP_AVAILABLE else '🟡'} SHAP explainability")
st.sidebar.markdown(f"{'🟢' if ml_main.RAG_AVAILABLE else '🟡'} RAG retrieval "
                     f"({getattr(ml_main, 'RAG_BACKEND_NAME', 'n/a')})")

st.sidebar.markdown("---")

if backend_up:
    try:
        flights_resp = requests.get(f"{BACKEND_URL}/api/flights", timeout=TIMEOUT).json()
        flights_df = pd.DataFrame(flights_resp)
        st.sidebar.success(f"{len(flights_df)} live flights loaded")
    except Exception:
        flights_df = load_demo_flights()
        st.sidebar.warning("Backend reachable but /api/flights failed — using demo flights")
else:
    flights_df = load_demo_flights()
    st.sidebar.info("Demo mode — 15 sample flights from the synthetic dataset")

flight_ids = flights_df["flight_id"].tolist()
selected_id = st.sidebar.selectbox("Select a flight", flight_ids)
flight_row = flights_df[flights_df["flight_id"] == selected_id].iloc[0].to_dict()


# ── Header: pipeline indicator ──────────────────────────────────────────────
st.title("AeroSync Operations Dashboard")
pipeline_stages = ["Flight", "Delay ML", "GNN Cascade", "Constraint Engine",
                    "RAG", "LLM Explanation", "Human Approval"]
cols = st.columns(len(pipeline_stages))
for c, stage in zip(cols, pipeline_stages):
    c.markdown(f"<div style='text-align:center;padding:6px;border-radius:6px;"
               f"background:#1f2937;color:white;font-size:13px'>{stage}</div>",
               unsafe_allow_html=True)
st.markdown("")


# ── 1. Flight overview ──────────────────────────────────────────────────────
st.header("1 · Flight")
c1, c2, c3, c4 = st.columns(4)
c1.metric("Flight", selected_id)
c2.metric("Route", f"{flight_row.get('origin','?')} → {flight_row.get('destination','?')}")
c3.metric("Aircraft", flight_row.get("aircraft_type", "?"))
c4.metric("Departure hour", flight_row.get("departure_hour", "?"))


# ── 2. Delay ML ──────────────────────────────────────────────────────────────
st.header("2 · Delay ML")


def build_predict_request(row):
    return dict(
        flight_id=selected_id,
        departure_hour=int(row.get("departure_hour", 10)),
        day_of_week=int(row.get("day_of_week", 2)),
        month=int(row.get("month", 6)),
        origin_airport=row.get("origin", "BOM"),
        destination_airport=row.get("destination", "DEL"),
        route_distance_km=float(row.get("route_distance_km", 1000)),
        weather_score=int(row.get("weather_score", 0)),
        crew_duty_hours=float(row.get("crew_duty_hours", 6.0)),
        historical_delay_rate=float(row.get("historical_delay_rate", 0.2)),
        aircraft_age=int(row.get("aircraft_age", 8)),
        gate_turnaround_mins=int(row.get("gate_turnaround_mins", 45)),
        aircraft_type=row.get("aircraft_type", "A320"),
    )


@st.cache_data(ttl=30, show_spinner=False)
def get_prediction(payload, use_live):
    if use_live:
        try:
            r = requests.post(f"{ML_URL}/predict", json=payload, timeout=TIMEOUT)
            if r.status_code == 200:
                return r.json(), "live FastAPI service"
        except Exception:
            pass
    # In-process fallback — same code /predict runs, no server needed
    req = ml_main.FlightPredictionRequest(**payload)
    return ml_main.predict(req), "in-process (aerosync-ml/main.py)"


predict_payload = build_predict_request(flight_row)
prediction, pred_source = get_prediction(predict_payload, ml_up)

pc1, pc2, pc3 = st.columns([1, 1, 2])
pc1.metric("Risk level", risk_badge(prediction["risk_level"]))
pc2.metric("Delay probability", f"{prediction['delay_probability']*100:.1f}%")
pc3.caption(f"Source: {pred_source} · model: {prediction.get('model_used','?')}")

st.subheader("Top SHAP factors (local explanation)")
explanation = prediction.get("explanation", [])
if explanation:
    fig = go.Figure(go.Bar(
        x=[e["contribution"] for e in explanation][::-1],
        y=[e["feature"] for e in explanation][::-1],
        orientation="h",
        marker_color=["#ef4444" if e["impact"] == "increases_risk" else "#22c55e"
                      for e in explanation][::-1],
    ))
    fig.update_layout(height=260, margin=dict(l=10, r=10, t=10, b=10),
                       xaxis_title="SHAP contribution (log-odds)")
    st.plotly_chart(fig, use_container_width=True)
    st.caption("Red = increases risk, green = decreases risk. From "
               "shap.TreeExplainer on the XGBoost base-learner.")
else:
    st.info("SHAP explanation unavailable for this prediction "
            "(SHAP_AVAILABLE is False — see sidebar).")


# ── 3. Disruption simulation ────────────────────────────────────────────────
st.header("3 · Disruption Simulation")

dc1, dc2, dc3, dc4 = st.columns([2, 1, 1, 1])
disruption_type = dc1.selectbox(
    "Disruption type",
    ["WEATHER_DELAY", "CREW_TIMEOUT", "AIRCRAFT_FAULT", "GATE_CONFLICT", "CASCADING_DELAY"])
delay_minutes = dc2.number_input("Delay (min)", min_value=15, max_value=300, value=75, step=5)
passengers = dc3.number_input("Passengers affected", min_value=0, max_value=400, value=150, step=10)
simulate_clicked = dc4.button("🚨 Simulate disruption", type="primary", use_container_width=True)

if "disruption_id" not in st.session_state:
    st.session_state.disruption_id = None
if "proposal" not in st.session_state:
    st.session_state.proposal = None

if simulate_clicked:
    if backend_up:
        try:
            r = requests.post(
                f"{BACKEND_URL}/api/disruptions/trigger",
                params={"flightId": selected_id, "type": disruption_type,
                        "delayMinutes": int(delay_minutes)},
                timeout=TIMEOUT)
            r.raise_for_status()
            disruption = r.json()
            st.session_state.disruption_id = disruption["id"]
            st.session_state.proposal = None
            st.success(f"Disruption #{disruption['id']} triggered on {selected_id}.")
        except Exception as e:
            st.error(f"Could not trigger a live disruption: {e}")
    else:
        st.session_state.disruption_id = "DEMO"
        st.session_state.proposal = None
        st.warning("Backend offline — showing an illustrative disruption "
                   "(no real Decision is computed; connect the backend for that).")


# ── 4. GNN cascade-risk ──────────────────────────────────────────────────────
st.header("4 · GNN Cascade-Risk")
gnn = load_gnn_backend()
if gnn is None:
    st.info("No GNN artifacts found — run `aerosync-ml/gnn/train_gnn.py` first.")
else:
    meta = gnn["meta"]
    if gnn["results"]:
        gm = gnn["results"]["gnn_test_metrics"]
        bm = gnn["results"]["baseline_test_metrics"]
        gcol1, gcol2, gcol3 = st.columns(3)
        gcol1.metric("GNN ROC-AUC", f"{gm['ROC-AUC']:.3f}",
                     delta=f"{gm['ROC-AUC']-bm['ROC-AUC']:+.3f} vs baseline")
        gcol2.metric("GNN Accuracy", f"{gm['Accuracy']:.3f}")
        gcol3.metric("Cascade-affected rate", f"{gnn['results']['graph']['cascade_risk_positive_rate']*100:.1f}%")
    st.caption("This panel shows the GNN's own demo flight-network "
               "(1,891 synthetic flights — see aerosync-ml/gnn/README.md). "
               "It is not yet linked to the specific flight selected above "
               "— see Roadmap.")
    if "gnn_cascade_risk" in meta.columns:
        top_risk = meta.sort_values("gnn_cascade_risk", ascending=False).head(10)
        st.dataframe(
            top_risk[["flight_id", "origin", "destination", "aircraft_type",
                      "gnn_cascade_risk", "has_upstream_disruption"]]
            .rename(columns={"gnn_cascade_risk": "GNN cascade risk"}),
            use_container_width=True, hide_index=True)


# ── 5. Cascade visualization ─────────────────────────────────────────────────
st.header("5 · Cascade Visualization")
if gnn is not None:
    meta = gnn["meta"]
    edge_index = gnn["edge_index"]
    # Small, readable subgraph: one aircraft's rotation + its direct neighbors
    sample_aircraft = int(meta.iloc[0]["aircraft_id"])
    sub_ids = meta[meta["aircraft_id"] == sample_aircraft].index.tolist()
    neighbor_ids = set(sub_ids)
    for s, d in zip(edge_index[0], edge_index[1]):
        if s in sub_ids or d in sub_ids:
            neighbor_ids.add(int(s)); neighbor_ids.add(int(d))
    neighbor_ids = list(neighbor_ids)[:25]

    pos_x = {n: i for i, n in enumerate(neighbor_ids)}
    edge_x, edge_y = [], []
    for s, d in zip(edge_index[0], edge_index[1]):
        if s in neighbor_ids and d in neighbor_ids:
            edge_x += [pos_x[s], pos_x[d], None]
            edge_y += [0, 0, None]

    risk_col = "gnn_cascade_risk" if "gnn_cascade_risk" in meta.columns else None
    node_colors = [meta.loc[n, risk_col] if risk_col else 0.3 for n in neighbor_ids]
    node_labels = [meta.loc[n, "flight_id"] for n in neighbor_ids]

    fig = go.Figure()
    fig.add_trace(go.Scatter(x=edge_x, y=edge_y, mode="lines",
                              line=dict(color="#9ca3af", width=1), hoverinfo="none"))
    fig.add_trace(go.Scatter(
        x=list(pos_x.values()), y=[0.05] * len(pos_x), mode="markers+text",
        text=node_labels, textposition="top center",
        marker=dict(size=18, color=node_colors, colorscale="RdYlGn_r",
                    cmin=0, cmax=1, showscale=True,
                    colorbar=dict(title="Cascade risk"))))
    fig.update_layout(height=260, showlegend=False,
                       xaxis=dict(visible=False), yaxis=dict(visible=False, range=[-0.2, 0.3]),
                       margin=dict(l=10, r=10, t=10, b=10))
    st.plotly_chart(fig, use_container_width=True)
    st.caption("One sample aircraft rotation and its directly connected flights, "
               "colored by GNN cascade-risk (red = higher).")
else:
    st.info("Run the GNN training script to populate this panel.")


# ── 6/7/8. Constraint engine (Decision), RAG (Evidence), LLM (Explanation) ──
st.header("6-8 · Proposed Resolution — Decision / Evidence / Explanation")

if backend_up and st.session_state.disruption_id and st.session_state.disruption_id != "DEMO":
    if st.button("📋 Propose resolution"):
        try:
            r = requests.post(
                f"{BACKEND_URL}/api/agents/propose/{st.session_state.disruption_id}",
                timeout=15)
            r.raise_for_status()
            st.session_state.proposal = r.json()
        except Exception as e:
            st.error(f"Propose failed: {e}")

    proposal = st.session_state.proposal
    if proposal:
        dcol, ecol, xcol = st.columns(3)
        with dcol:
            st.subheader("Decision")
            st.caption("deterministic_algorithm")
            for key in ["gate_resolution", "crew_resolution",
                        "passenger_resolution", "aircraft_resolution"]:
                if proposal.get(key):
                    st.json(proposal[key], expanded=False)
        with ecol:
            st.subheader("Evidence")
            st.caption("rag_retrieval")
            for chunk in proposal.get("policy_evidence", []):
                st.markdown(f"**{chunk.get('doc_name')} :: {chunk.get('section')}** "
                            f"({chunk.get('score', 0):.3f})")
                st.caption(chunk.get("text", "")[:220] + "...")
            if not proposal.get("policy_evidence"):
                st.caption("No policy evidence retrieved.")
        with xcol:
            st.subheader("Explanation")
            st.caption(proposal.get("explanation_source", "?"))
            st.write(proposal.get("ai_explanation", ""))

        st.markdown("### 9 · Human Approval")
        acol, rcol = st.columns(2)
        if acol.button("✅ Approve", type="primary", use_container_width=True):
            try:
                r = requests.post(
                    f"{BACKEND_URL}/api/agents/approve/{st.session_state.disruption_id}",
                    json=proposal, timeout=15)
                r.raise_for_status()
                st.success("Resolution approved and applied.")
                st.json(r.json(), expanded=False)
            except Exception as e:
                st.error(f"Approve failed: {e}")
        reason = rcol.text_input("Rejection reason (required to reject)")
        if rcol.button("❌ Reject", use_container_width=True):
            if not reason:
                st.warning("Enter a reason to reject.")
            else:
                try:
                    r = requests.post(
                        f"{BACKEND_URL}/api/agents/reject/{st.session_state.disruption_id}",
                        json={"reason": reason}, timeout=15)
                    r.raise_for_status()
                    st.session_state.proposal = r.json()
                    st.info("Agent re-reasoned excluding the rejected resource — see revised proposal above on next refresh.")
                except Exception as e:
                    st.error(f"Reject failed: {e}")
else:
    st.warning("Decision / Approval require the live Spring Boot backend "
               "(not reachable, or no disruption simulated yet). RAG evidence "
               "can still be previewed below without it.")
    st.subheader("Preview: RAG evidence for this disruption type")
    query = f"{disruption_type.replace('_', ' ').lower()} policy response"
    if ml_main.RAG_AVAILABLE:
        results = ml_main.rag_retriever.retrieve(query, top_k=3)
        for r in results:
            st.markdown(f"**{r.doc_name} :: {r.section}** ({r.score:.3f})")
            st.caption(r.text[:220] + "...")
    else:
        st.info("RAG index not available — run aerosync-rag/retrieval/build_index.py.")

st.markdown("---")
st.caption("AeroSync — AI flight disruption management demo. "
           "Synthetic data and demo policy documents; not real airline operations.")
