# aerosync-ml/Dockerfile
# Build context: repo root (NOT aerosync-ml/) — main.py imports aerosync-rag/
# via a "../aerosync-rag" relative path (see main.py's RAG_AVAILABLE block),
# so this image needs both directories copied in with the same relative
# layout they have in the repo. docker-compose.yml sets this build context
# correctly; if building by hand, run from the repo root:
#   docker build -f aerosync-ml/Dockerfile -t aerosync-ml .

FROM python:3.11-slim
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY aerosync-ml/requirements.txt ./aerosync-ml/requirements.txt
RUN pip install --no-cache-dir -r aerosync-ml/requirements.txt
RUN pip install --no-cache-dir scikit-learn faiss-cpu
# scikit-learn + faiss-cpu only, NOT sentence-transformers, by default —
# sentence-transformers pulls in PyTorch (a large, slow download that can
# also be flaky depending on your network), and aerosync-rag's retriever
# already falls back to an offline TF-IDF embedder automatically when it
# isn't present (see aerosync-rag/retrieval/embeddings.py). Uncomment the
# next line and rebuild to use the real sentence-transformer model instead.
RUN pip install --no-cache-dir scikit-learn faiss-cpu
# RUN pip install --no-cache-dir sentence-transformers

COPY aerosync-ml/ ./aerosync-ml/
COPY aerosync-rag/ ./aerosync-rag/

WORKDIR /app/aerosync-ml

EXPOSE 8000

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=6 \
    CMD curl -f http://localhost:8000/health || exit 1

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
