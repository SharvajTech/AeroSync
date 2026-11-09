# aerosync-dashboard/Dockerfile
# Build context: repo root — app.py imports aerosync-ml/main.py and
# aerosync-rag/ directly for its in-process fallback path (works even if
# the ml-service container is briefly unreachable). Same reasoning as
# aerosync-ml/Dockerfile.

FROM python:3.11-slim
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY aerosync-ml/requirements.txt ./aerosync-ml/requirements.txt
COPY aerosync-dashboard/requirements.txt ./aerosync-dashboard/requirements.txt
RUN pip install --no-cache-dir --timeout=300 -r aerosync-ml/requirements.txt \
    && pip install --no-cache-dir --timeout=300 scikit-learn faiss-cpu \
    && pip install --no-cache-dir --timeout=300 -r aerosync-dashboard/requirements.txt
COPY aerosync-ml/ ./aerosync-ml/
COPY aerosync-rag/ ./aerosync-rag/
COPY aerosync-dashboard/ ./aerosync-dashboard/

WORKDIR /app/aerosync-dashboard

EXPOSE 8501

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=6 \
    CMD curl -f http://localhost:8501/_stcore/health || exit 1

CMD ["streamlit", "run", "app.py", "--server.address=0.0.0.0", "--server.port=8501"]
