"""
RoadGuard REST API, live WebSocket stream & Cockpit HUD server (v4)
Department of Computer Science & Engineering | IDEA Lab
Ramdeobaba University, Academic Year 2026-27
Guide: Dr. Suresh Balpande
Team: Bhoomi Magarde, Mohammad Ejaz Mehendi, Nidhish Dadhe

v4 runs on its own database (SQLite locally, PostgreSQL in Docker / on AWS) instead of
a hosted Supabase project, classifies live telemetry with the trained Random Forest
fused with the physics heuristic, pushes updates to dashboards over WebSocket, and
adds analytics, hazard moderation, an admin token and cloud deployment metadata.
"""

import asyncio
import csv
import hmac
import io
import json
import logging
import os
import threading
import time
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

import numpy as np
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, Response, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

from database import backend_name, create_db_engine, init_db, safe_url
from hazards import HAZARD_TYPES, HazardStore
from ml_inference import ml_engine
from settings import SERVER_DIR, settings
from telemetry import LiveHub, TelemetryState

# ml_inference puts ml/scripts on sys.path, so the training-set generator is importable here.
from dataset_generator import (  # noqa: E402
    generate_normal_window,
    generate_pothole_window,
    generate_speed_breaker_window,
)

logging.basicConfig(level=settings.log_level, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("roadguard.api")

STATIC_DIR = os.path.join(SERVER_DIR, "static")
AUTO_INGEST_COOLDOWN_S = 3.0
AUTO_INGEST_MIN_CONFIDENCE = 75.0

engine = create_db_engine(settings.database_url)
store = HazardStore(engine)
telemetry = TelemetryState(ml_engine)
telemetry_lock = threading.Lock()
hub = LiveHub()
started_at = time.time()
request_counter = {"total": 0, "errors": 0}
last_auto_ingest = {"time": 0.0}


def _init_database(retries: int = 30, delay: float = 2.0) -> None:
    """Create tables (and demo data). Retries while a PostgreSQL container is still booting."""
    for attempt in range(1, retries + 1):
        try:
            init_db(engine, seed=settings.seed_demo_data)
            return
        except Exception as e:  # noqa: BLE001
            if attempt == retries:
                raise
            log.warning("Database not ready (%s), retry %d/%d", e.__class__.__name__, attempt, retries)
            time.sleep(delay)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    _init_database()
    hub.loop = asyncio.get_running_loop()
    pump = asyncio.create_task(hub.telemetry_pump(telemetry))
    log.info("RoadGuard %s ready: database=%s model=%s platform=%s",
             settings.app_version, backend_name(engine), ml_engine.get_model_status()["model_type"],
             settings.deployment.get("platform"))
    try:
        yield
    finally:
        pump.cancel()


app = FastAPI(
    title="RoadGuard Smart Hazard Detection API",
    description="Real-time REST + WebSocket API for the RoadGuard pothole and speed breaker detection system.",
    version=settings.app_version,
    lifespan=lifespan,
)

app.add_middleware(GZipMiddleware, minimum_size=1000)
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def timing_and_security_headers(request: Request, call_next):
    started = time.perf_counter()
    request_counter["total"] += 1
    response = await call_next(request)
    if response.status_code >= 500:
        request_counter["errors"] += 1
    response.headers["X-Response-Time-ms"] = f"{(time.perf_counter() - started) * 1000:.1f}"
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("X-Frame-Options", "SAMEORIGIN")
    response.headers.setdefault("Referrer-Policy", "strict-origin-when-cross-origin")
    return response


# -----------------------------------------------------------------------------
# Access control
# -----------------------------------------------------------------------------
def require_admin(x_admin_token: Optional[str] = Header(default=None)) -> None:
    """Moderation and data resets need ROADGUARD_ADMIN_TOKEN when one is configured."""
    if settings.admin_token and not hmac.compare_digest(x_admin_token or "", settings.admin_token):
        raise HTTPException(status_code=401, detail="Admin token required (send it in the X-Admin-Token header).")


def require_test_api() -> None:
    if not settings.enable_test_api:
        raise HTTPException(status_code=403, detail="The test/diagnostics API is disabled on this deployment.")


# -----------------------------------------------------------------------------
# Data Models
# -----------------------------------------------------------------------------
class IngestObservationRequest(BaseModel):
    hazard_type: str = Field(..., description="POTHOLE or SPEED_BREAKER (NORMAL is not saved)")
    latitude: float = Field(..., ge=-90.0, le=90.0)
    longitude: float = Field(..., ge=-180.0, le=180.0)
    severity: str = "HIGH"
    roughness_score: float = Field(6.0, ge=0.0, le=10.0)
    speed_kmh: float = Field(40.0, ge=0.0, le=300.0)
    az_peak: float = 14.5
    confidence: Optional[float] = Field(None, ge=0.0, le=100.0)
    trip_id: Optional[str] = None
    device_id: Optional[str] = None
    source: str = "SENSOR"
    address_description: str = Field("", max_length=255)


class TelemetryIngestRequest(BaseModel):
    device_id: Optional[str] = "RoadGuard-ESP32"
    timestamp: Optional[int] = None
    accel_x: float = 0.0
    accel_y: float = 0.0
    accel_z: float = 9.81
    gyro_x: float = 0.0
    gyro_y: float = 0.0
    gyro_z: float = 0.0
    latitude: Optional[float] = Field(21.1777, ge=-90.0, le=90.0)
    longitude: Optional[float] = Field(79.0617, ge=-180.0, le=180.0)
    speed_kmh: float = 0.0
    gps_fix: bool = False
    sensor_ok: bool = True
    source: str = "SENSOR"


class SyncBatchItem(BaseModel):
    operation_id: Optional[str] = None
    hazard_type: str
    latitude: float = Field(..., ge=-90.0, le=90.0)
    longitude: float = Field(..., ge=-180.0, le=180.0)
    severity: str = "MEDIUM"
    roughness_score: float = 6.0
    speed_kmh: float = 35.0
    az_peak: float = 12.0
    observed_at: Optional[datetime] = None
    trip_id: Optional[str] = None
    source: str = "SENSOR"


class SyncBatchRequest(BaseModel):
    device_id: Optional[str] = "RoadGuard-ESP32"
    records: List[SyncBatchItem] = Field(default_factory=list, max_length=1000)


class TripCreateRequest(BaseModel):
    device_id: Optional[str] = None
    distance_meters: float = 0.0
    duration_seconds: int = 0
    potholes_count: int = 0
    speed_breakers_count: int = 0
    avg_roughness: float = 0.0


class HazardUpdateRequest(BaseModel):
    is_active: Optional[bool] = Field(None, description="false marks the hazard as repaired/resolved")
    verification_status: Optional[str] = None
    severity: Optional[str] = None
    address_description: Optional[str] = Field(None, max_length=255)


class TestEventRequest(BaseModel):
    event_type: str = Field(..., description="POTHOLE, SPEED_BREAKER, NORMAL, GPS_INVALID, BLE_DISCONNECT")
    latitude: Optional[float] = Field(21.1777, ge=-90.0, le=90.0)
    longitude: Optional[float] = Field(79.0617, ge=-180.0, le=180.0)
    speed_kmh: Optional[float] = 38.0
    save_to_db: Optional[bool] = True


# -----------------------------------------------------------------------------
# Helpers
# -----------------------------------------------------------------------------
def deployment_info() -> Dict[str, str]:
    return {k: v for k, v in settings.deployment.items() if v}


def publish_hazard(hazard: Dict[str, Any], event: str) -> None:
    hub.publish({"type": "hazard", "event": event, "data": hazard})


def roughness_from_peak(az_peak: float) -> float:
    return round(min(10.0, max(0.5, abs(az_peak - 9.81) / 1.5)), 2)


def maybe_auto_ingest() -> Optional[Dict[str, Any]]:
    """Store a hazard detected from the live stream: once per new anomaly, GPS fix required."""
    cls = telemetry.classification
    if (not settings.auto_ingest or not telemetry.new_anomaly or not telemetry.gps_fix
            or cls["confidence"] < AUTO_INGEST_MIN_CONFIDENCE):
        return None
    now = time.time()
    if now - last_auto_ingest["time"] < AUTO_INGEST_COOLDOWN_S:
        return None
    last_auto_ingest["time"] = now
    peak = telemetry.az_peak()
    hazard, created = store.ingest(
        hazard_type=cls["condition"], latitude=telemetry.latitude, longitude=telemetry.longitude,
        severity=cls["severity"], roughness_score=roughness_from_peak(peak), speed_kmh=telemetry.speed_kmh,
        az_peak=peak, confidence=cls["confidence"], device_id=telemetry.device_id,
        source="TEST" if telemetry.is_demo_mode else "SENSOR",
    )
    publish_hazard(hazard, "created" if created else "observed")
    return hazard


# -----------------------------------------------------------------------------
# Static Dashboard
# -----------------------------------------------------------------------------
@app.get("/", include_in_schema=False)
def get_dashboard():
    static_file = os.path.join(STATIC_DIR, "index.html")
    if os.path.exists(static_file):
        return FileResponse(static_file, headers={"Cache-Control": "no-cache"})
    return {"message": "RoadGuard Backend API is running. Go to /docs for Swagger UI"}


@app.get("/favicon.ico", include_in_schema=False)
def get_favicon():
    return Response(status_code=204)


# -----------------------------------------------------------------------------
# Health & Info
# -----------------------------------------------------------------------------
@app.get("/health")
def health_check():
    """Subsystem health: database, ML engine, device stream and deployment."""
    db_ok, error_msg = False, None
    counts = {"hazards": 0, "active_hazards": 0, "observations": 0}
    try:
        counts = store.counts()
        db_ok = True
    except Exception as e:  # noqa: BLE001
        error_msg = str(e).splitlines()[0][:300]

    backend = backend_name(engine)
    db_status = "CONNECTED" if db_ok else "DISCONNECTED"
    snap = telemetry.snapshot()
    return {
        "status": "ok" if db_ok else "degraded",
        "version": settings.app_version,
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "uptime_seconds": int(time.time() - started_at),
        "database": {
            "name": backend,
            "provider": backend,
            "status": db_status,
            "display_status": f"{backend.upper()} {db_status}",
            "backend": backend,
            "url": safe_url(engine),
            "hazards_count": counts["hazards"],
            "active_hazards_count": counts["active_hazards"],
            "observations_count": counts["observations"],
            "error": error_msg,
        },
        "ml_engine": ml_engine.get_model_status(),
        "device_stream": {
            "device_status": snap["device_status"],
            "device_id": snap["device_id"],
            "measured_hz": snap["measured_hz"],
            "gps_fix": snap["gps_fix"],
            "is_simulated": snap["is_simulated"],
        },
        "realtime": {"websocket_clients": len(hub.clients)},
        "deployment": deployment_info(),
    }


@app.get("/health/ready")
def readiness_check():
    try:
        store.ping()
        return {"status": "ready", "database": "connected", "backend": backend_name(engine)}
    except Exception as e:  # noqa: BLE001
        raise HTTPException(status_code=503, detail=f"Database unreachable: {str(e).splitlines()[0][:200]}")


@app.get("/api/info")
def get_info():
    """Build, runtime and cloud information shown in the dashboard's System panel."""
    return {
        "name": "RoadGuard",
        "version": settings.app_version,
        "uptime_seconds": int(time.time() - started_at),
        "started_at": datetime.fromtimestamp(started_at, tz=timezone.utc).isoformat(),
        "database_backend": backend_name(engine),
        "deployment": deployment_info(),
        "requests_served": request_counter["total"],
        "server_errors": request_counter["errors"],
        "websocket_clients": len(hub.clients),
        "test_api_enabled": settings.enable_test_api,
        "admin_token_required": bool(settings.admin_token),
        "auto_ingest": settings.auto_ingest,
        "ml": ml_engine.get_model_status(),
    }


# -----------------------------------------------------------------------------
# Devices & Telemetry
# -----------------------------------------------------------------------------
@app.get("/api/devices")
def get_devices():
    try:
        rows = store.list_devices()
    except Exception:  # noqa: BLE001
        rows = []
    snap = telemetry.snapshot()
    if not rows:
        return [{
            "id": "d0000000-0000-0000-0000-000000000001",
            "device_name": "RoadGuard-ESP32",
            "device_identifier": "RoadGuard-ESP32",
            "status": snap["device_status"],
            "last_seen_at": snap["timestamp"],
            "sampling_rate_hz": snap["measured_hz"],
        }]
    return [
        {**r, "live_status": snap["device_status"], "sampling_rate_hz": snap["measured_hz"]}
        if r.get("device_identifier") == snap["device_id"] else r
        for r in rows
    ]


@app.post("/api/telemetry")
def ingest_telemetry(req: TelemetryIngestRequest):
    """Ingest one IMU + GPS packet (50 Hz from the ESP32 / Android client)."""
    packet = req.model_dump()
    with telemetry_lock:
        classification = telemetry.update(packet)
        hazard = maybe_auto_ingest()
    if not telemetry.is_demo_mode:
        store.touch_device(telemetry.device_id, time.time())
    return {
        "status": "received",
        "measured_hz": telemetry.measured_hz,
        "classification": classification,
        "hazard_id": hazard["id"] if hazard else None,
    }


@app.get("/api/telemetry/live")
def get_live_telemetry():
    return telemetry.snapshot()


@app.websocket("/ws/live")
async def live_socket(ws: WebSocket):
    """Push channel for dashboards: telemetry (<= 4/s) and hazard create/update/reset events."""
    await hub.connect(ws)
    try:
        await ws.send_json({"type": "hello", "data": {
            "version": settings.app_version,
            "deployment": deployment_info(),
            "database_backend": backend_name(engine),
            "telemetry": telemetry.snapshot(),
        }})
        while True:
            message = await ws.receive_text()
            if message == "ping":
                await ws.send_json({"type": "pong", "time": time.time()})
    except WebSocketDisconnect:
        pass
    finally:
        hub.disconnect(ws)


# -----------------------------------------------------------------------------
# Hazards
# -----------------------------------------------------------------------------
@app.get("/api/hazards")
def get_hazards(
    lat: Optional[float] = Query(None, ge=-90.0, le=90.0),
    lng: Optional[float] = Query(None, ge=-180.0, le=180.0),
    radius: float = Query(5000.0, gt=0, le=200_000),
    hazard_type: Optional[str] = None,
    severity: Optional[str] = None,
    source: Optional[str] = None,
    include_inactive: bool = False,
    min_lat: Optional[float] = None,
    max_lat: Optional[float] = None,
    min_lon: Optional[float] = None,
    max_lon: Optional[float] = None,
    limit: int = Query(300, ge=1, le=5000),
    offset: int = Query(0, ge=0),
):
    bbox = None
    if None not in (min_lat, max_lat, min_lon, max_lon):
        bbox = (min_lat, max_lat, min_lon, max_lon)
    try:
        return store.list(
            hazard_type=hazard_type, severity=severity, source=source, include_inactive=include_inactive,
            lat=lat, lng=lng, radius=radius if lat is not None else None, bbox=bbox, limit=limit, offset=offset,
        )
    except Exception as e:  # noqa: BLE001
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/hazards/nearby")
def get_nearby_hazards(
    lat: float = Query(..., ge=-90.0, le=90.0),
    lng: float = Query(..., ge=-180.0, le=180.0),
    radius: float = Query(150.0, gt=0, le=20_000),
    heading: Optional[float] = Query(None, ge=0.0, le=360.0),
):
    try:
        results = store.nearby(lat, lng, radius, heading)
    except Exception as e:  # noqa: BLE001
        raise HTTPException(status_code=500, detail=str(e))
    for r in results:
        r["roughness_score"] = r.get("roughness_score") or 6.0
    return results


@app.get("/api/hazards/{hazard_id}")
def get_hazard_details(hazard_id: str):
    result = store.get(hazard_id)
    if result is None:
        raise HTTPException(status_code=404, detail="Hazard not found")
    return result


@app.post("/api/hazards")
@app.post("/api/observations")
def ingest_observation(req: IngestObservationRequest):
    h_type = req.hazard_type.upper().strip()
    if h_type == "NORMAL":
        return {"status": "ignored", "message": "NORMAL condition is not stored as a hazard record."}
    if h_type not in HAZARD_TYPES:
        raise HTTPException(status_code=400, detail="Invalid hazard_type. Must be POTHOLE or SPEED_BREAKER.")
    try:
        hazard, created = store.ingest(
            hazard_type=h_type, latitude=req.latitude, longitude=req.longitude, severity=req.severity,
            roughness_score=req.roughness_score, speed_kmh=req.speed_kmh, az_peak=req.az_peak,
            confidence=req.confidence, trip_id=req.trip_id, device_id=req.device_id,
            source=req.source, address_description=req.address_description,
        )
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    publish_hazard(hazard, "created" if created else "observed")
    return {
        "status": "success",
        "hazard_id": hazard["id"],
        "hazard_type": h_type,
        "created": created,
        "observation_count": hazard["observation_count"],
        "verification_status": hazard["verification_status"],
    }


@app.patch("/api/hazards/{hazard_id}", dependencies=[Depends(require_admin)])
def update_hazard(hazard_id: str, req: HazardUpdateRequest):
    """Moderate a hazard: mark it repaired (is_active=false), reopen it, verify or re-grade it."""
    try:
        hazard = store.update(hazard_id, req.model_dump(exclude_unset=True))
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    if hazard is None:
        raise HTTPException(status_code=404, detail="Hazard not found")
    publish_hazard(hazard, "updated")
    return {"status": "success", "hazard": hazard}


# -----------------------------------------------------------------------------
# Offline Sync
# -----------------------------------------------------------------------------
@app.post("/api/sync")
def sync_offline_records(batch: SyncBatchRequest):
    synced = skipped = 0
    for item in batch.records:
        h_type = item.hazard_type.upper().strip()
        if h_type not in HAZARD_TYPES:
            skipped += 1
            continue
        try:
            hazard, created = store.ingest(
                hazard_type=h_type, latitude=item.latitude, longitude=item.longitude, severity=item.severity,
                roughness_score=item.roughness_score, speed_kmh=item.speed_kmh, az_peak=item.az_peak,
                trip_id=item.trip_id, device_id=batch.device_id, source=item.source, observed_at=item.observed_at,
            )
            publish_hazard(hazard, "created" if created else "observed")
            synced += 1
        except ValueError:
            skipped += 1
    return {"status": "success", "synced_count": synced, "skipped_count": skipped,
            "timestamp": datetime.now(timezone.utc).isoformat()}


# -----------------------------------------------------------------------------
# Trips
# -----------------------------------------------------------------------------
@app.get("/api/trips")
def get_trips(limit: int = Query(50, ge=1, le=500), offset: int = Query(0, ge=0)):
    return store.list_trips(limit, offset)


@app.post("/api/trips")
def create_trip(req: TripCreateRequest):
    trip_id = store.create_trip(req.model_dump())
    return {"status": "success", "trip_id": trip_id}


# -----------------------------------------------------------------------------
# Analytics, ML Metrics & Export
# -----------------------------------------------------------------------------
@app.get("/api/stats")
def get_stats(days: int = Query(14, ge=1, le=90)):
    """Totals, severity/source breakdown and a daily detection timeline (test data excluded)."""
    return store.stats(days)


@app.get("/api/metrics")
def get_ml_metrics():
    return ml_engine.compute_evaluation_metrics()


@app.get("/api/export")
def export_hazards(
    format: str = Query("json", pattern="^(json|csv)$"),
    source: Optional[str] = None,
    hazard_type: Optional[str] = None,
    include_inactive: bool = True,
):
    try:
        rows = store.export_rows(source=source, hazard_type=hazard_type, include_inactive=include_inactive)
    except Exception as e:  # noqa: BLE001
        raise HTTPException(status_code=500, detail=str(e))

    ts = int(time.time())
    if format == "csv":
        out = io.StringIO()
        if rows:
            writer = csv.DictWriter(out, fieldnames=list(rows[0].keys()))
            writer.writeheader()
            writer.writerows(rows)
        else:
            out.write("id,hazard_type,latitude,longitude,severity,roughness_score\n")
        return Response(content=out.getvalue(), media_type="text/csv",
                        headers={"Content-Disposition": f"attachment; filename=roadguard_hazards_{ts}.csv"})
    return Response(content=json.dumps(rows, indent=2, default=str), media_type="application/json",
                    headers={"Content-Disposition": f"attachment; filename=roadguard_hazards_{ts}.json"})


# -----------------------------------------------------------------------------
# Test / Diagnostics
# -----------------------------------------------------------------------------
def _event_window(event: str, speed_kmh: float) -> Dict[str, np.ndarray]:
    """A realistic 1-second IMU burst from the same physics model that built the training set."""
    if event == "POTHOLE":
        window = generate_pothole_window(speed_kmh=speed_kmh, severity=1.1)
    elif event == "SPEED_BREAKER":
        window = generate_speed_breaker_window(speed_kmh=speed_kmh, severity=1.1)
    else:
        window = generate_normal_window(speed_kmh=speed_kmh)
    # Move the event towards the end so it shows up in the dashboard's 30-sample trace.
    return {axis: np.roll(values, 12) for axis, values in window.items()}


@app.post("/api/test/event", dependencies=[Depends(require_test_api)])
def inject_test_event(req: TestEventRequest):
    event = req.event_type.upper()
    speed = req.speed_kmh or 38.0
    meta = {"source": "TEST", "gps_fix": True, "sensor_ok": True, "speed_kmh": speed,
            "latitude": req.latitude, "longitude": req.longitude}

    if event == "GPS_INVALID":
        with telemetry_lock:
            telemetry.update({"source": "TEST", "gps_fix": False, "latitude": 0.0, "longitude": 0.0, "speed_kmh": 0.0})
        return {"status": "test_applied", "event": "GPS_INVALID"}
    if event == "BLE_DISCONNECT":
        telemetry.mark_disconnected()
        return {"status": "test_applied", "event": "BLE_DISCONNECT"}
    if event not in ("NORMAL", "POTHOLE", "SPEED_BREAKER"):
        raise HTTPException(status_code=400, detail="Invalid event_type.")

    with telemetry_lock:
        classification = telemetry.push_window(_event_window(event, speed), meta)
        peak = telemetry.az_peak()
    if event == "NORMAL":
        return {"status": "test_applied", "event": "NORMAL", "classification": classification}

    hazard_id = None
    if req.save_to_db:
        is_pothole = event == "POTHOLE"
        hazard, created = store.ingest(
            hazard_type=event, latitude=req.latitude, longitude=req.longitude,
            severity=classification["severity"] if classification["condition"] == event else ("HIGH" if is_pothole else "MEDIUM"),
            roughness_score=8.4 if is_pothole else 6.2, speed_kmh=speed, az_peak=peak,
            confidence=classification["confidence"] if classification["condition"] == event else None,
            device_id=telemetry.device_id, source="TEST",
        )
        hazard_id = hazard["id"]
        publish_hazard(hazard, "created" if created else "observed")
    return {"status": "test_applied", "event": event, "hazard_id": hazard_id, "source": "TEST",
            "classification": classification}


@app.post("/api/test/reset", dependencies=[Depends(require_test_api), Depends(require_admin)])
def reset_test_data():
    hazards_deleted, obs_deleted = store.reset_test_data()
    telemetry.is_demo_mode = False
    hub.publish({"type": "hazards_reset", "data": {"hazards_deleted": hazards_deleted}})
    return {"status": "success", "hazards_deleted": hazards_deleted, "observations_deleted": obs_deleted}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("app:app", host="0.0.0.0", port=int(os.getenv("PORT", "8000")),
                reload=os.getenv("ROADGUARD_RELOAD", "false").lower() == "true")
