# RoadGuard API + Cockpit HUD
# Build context: the RoadGuard/ folder (server/ and ml/ are needed; Android, firmware and Supabase are not).
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

COPY server/requirements.txt server/requirements.txt
RUN pip install -r server/requirements.txt

COPY ml ml
COPY server server

# Train the Random Forest once at build time so containers start in seconds and the
# pickled model always matches the scikit-learn version installed above.
RUN cd server && python -c "from ml_inference import ml_engine; assert ml_engine.model is not None" \
    && useradd --system --uid 10001 --home /app roadguard \
    && mkdir -p server/data \
    && chown -R roadguard:roadguard /app

USER roadguard
WORKDIR /app/server
EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health/ready', timeout=4).status == 200 else 1)"

# One worker on purpose: live telemetry and WebSocket clients are kept in process memory.
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers", "--forwarded-allow-ips", "*", "--ws-ping-interval", "20"]
