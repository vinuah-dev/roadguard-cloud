"""
Live telemetry state and the WebSocket hub that pushes it to every open dashboard.

The ESP32 / Android client (or the dashboard's own simulator) POSTs packets to
/api/telemetry. The last 50 samples (1 s at 50 Hz) of all six IMU axes are kept
here and classified by the ML engine; the result is broadcast over /ws/live.
"""

import asyncio
import logging
import time
import warnings
from collections import deque
from datetime import datetime, timezone
from typing import Any, Deque, Dict, Iterable, List, Optional, Set

import numpy as np
from fastapi import WebSocket

from geo import bearing_deg

log = logging.getLogger("roadguard.telemetry")

WINDOW = 50
AXES = ("ax", "ay", "az", "gx", "gy", "gz")
FRESH_SECONDS = 6.0
MODEL_INTERVAL_S = 0.2  # run the Random Forest at most 5x per second on a 50 Hz stream
DEFAULT_POSITION = (21.1777, 79.0617)  # RCOEM, Katol Road, Nagpur

NORMAL_CLASSIFICATION = {"condition": "NORMAL", "confidence": 98.4, "severity": "LOW", "engine": "idle"}


def _pick(packet: Dict[str, Any], snake: str, camel: str, default):
    value = packet.get(snake, packet.get(camel))
    return default if value is None else value


class TelemetryState:
    def __init__(self, ml_engine):
        self.ml = ml_engine
        self.version = 0
        self.device_id = "RoadGuard-ESP32"
        self.is_connected = False
        self.is_demo_mode = False
        self.last_packet_time = 0.0
        self.packet_times: Deque[float] = deque(maxlen=WINDOW)
        self.measured_hz = 0.0
        self.speed_kmh = 0.0
        self.latitude, self.longitude = DEFAULT_POSITION
        self.heading = 0.0
        self.gps_fix = False
        self.sensor_ok = True
        self.window: Dict[str, Deque[float]] = {
            axis: deque([9.81 if axis == "az" else 0.0] * WINDOW, maxlen=WINDOW) for axis in AXES
        }
        self.classification: Dict[str, Any] = dict(NORMAL_CLASSIFICATION)
        self.heuristic: Optional[Dict[str, Any]] = None
        self.model_result: Optional[Dict[str, Any]] = None
        self._last_model_time = 0.0
        self.last_anomaly_event: Optional[Dict[str, Any]] = None
        # True only for the packet on which a new anomaly first shows up in the window.
        self.new_anomaly = False

    # ----------------------------------------------------------------- ingest
    def update(self, packet: Dict[str, Any]) -> Dict[str, Any]:
        """Apply one packet and return the current classification."""
        self._apply_metadata(packet)
        sample = {
            "ax": float(_pick(packet, "accel_x", "accelX", 0.0)),
            "ay": float(_pick(packet, "accel_y", "accelY", 0.0)),
            "az": float(_pick(packet, "accel_z", "accelZ", 9.81)),
            "gx": float(_pick(packet, "gyro_x", "gyroX", 0.0)),
            "gy": float(_pick(packet, "gyro_y", "gyroY", 0.0)),
            "gz": float(_pick(packet, "gyro_z", "gyroZ", 0.0)),
        }
        for axis in AXES:
            self.window[axis].append(sample[axis])
        return self._classify()

    def push_window(self, samples: Dict[str, Iterable[float]], packet: Dict[str, Any]) -> Dict[str, Any]:
        """Replace the window with a full 1-second burst (used by the test-event injector)."""
        self._apply_metadata(packet)
        for axis in AXES:
            for value in samples[axis]:
                self.window[axis].append(float(value))
        return self._classify(force_model=True)

    def mark_disconnected(self) -> None:
        self.is_connected = False
        self.last_packet_time = 0.0
        self.version += 1

    def _reset_window(self) -> None:
        for axis in AXES:
            self.window[axis].extend([9.81 if axis == "az" else 0.0] * WINDOW)
        self.packet_times.clear()
        self.measured_hz = 0.0
        self.classification = dict(NORMAL_CLASSIFICATION)
        self.model_result = None

    def _apply_metadata(self, packet: Dict[str, Any]) -> None:
        now = time.time()
        if now - self.last_packet_time > FRESH_SECONDS:
            # A new stream after a gap: samples from the previous session are not this road.
            self._reset_window()
        self.last_packet_time = now
        self.is_connected = True
        self.device_id = str(_pick(packet, "device_id", "deviceId", self.device_id))[:64]
        self.is_demo_mode = str(packet.get("source", "SENSOR")).upper() == "TEST" or bool(packet.get("is_demo", False))

        self.packet_times.append(now)
        if len(self.packet_times) >= 5:
            dt = self.packet_times[-1] - self.packet_times[0]
            if dt > 0:
                self.measured_hz = round((len(self.packet_times) - 1) / dt, 1)

        self.gps_fix = bool(_pick(packet, "gps_fix", "gpsFix", False))
        self.sensor_ok = bool(_pick(packet, "sensor_ok", "sensorOk", True))
        self.speed_kmh = float(_pick(packet, "speed_kmh", "speedKmh", self.speed_kmh))

        lat, lng = packet.get("latitude"), packet.get("longitude")
        if lat is not None and lng is not None and (lat, lng) != (0.0, 0.0):
            lat, lng = float(lat), float(lng)
            if self.gps_fix and (lat, lng) != (self.latitude, self.longitude):
                self.heading = bearing_deg(self.latitude, self.longitude, lat, lng)
            self.latitude, self.longitude = lat, lng

    def _classify(self, force_model: bool = False) -> Dict[str, Any]:
        window = {axis: list(self.window[axis]) for axis in AXES}
        self.heuristic = self.ml.classify_window_heuristic(window["az"], self.speed_kmh)
        now = time.time()
        # The model runs on a timer, and immediately whenever the physics rules see something.
        if force_model or self.heuristic["condition"] != "NORMAL" or now - self._last_model_time >= MODEL_INTERVAL_S:
            with warnings.catch_warnings(), np.errstate(all="ignore"):
                warnings.simplefilter("ignore", RuntimeWarning)
                self.model_result = self.ml.predict_window(window, self.speed_kmh)
            self._last_model_time = now
        previous = self.classification["condition"]
        self.classification = self.ml.fuse(self.heuristic, self.model_result)
        condition = self.classification["condition"]
        self.new_anomaly = condition != "NORMAL" and condition != previous
        if condition != "NORMAL":
            self.last_anomaly_event = {
                **{k: self.classification[k] for k in ("condition", "confidence", "severity", "engine")},
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "latitude": self.latitude,
                "longitude": self.longitude,
            }
        self.version += 1
        return self.classification

    def az_peak(self) -> float:
        return float(max(self.window["az"]))

    # --------------------------------------------------------------- snapshot
    def is_fresh(self) -> bool:
        return (time.time() - self.last_packet_time) <= FRESH_SECONDS

    def snapshot(self) -> Dict[str, Any]:
        now = time.time()
        fresh = self.is_fresh()
        if self.is_connected and fresh:
            status = "DEMO MODE" if self.is_demo_mode else "CONNECTED"
        else:
            status = "DEVICE DISCONNECTED"
        recent = {axis: list(self.window[axis])[-30:] for axis in ("ax", "ay", "az")}
        latest = {axis: self.window[axis][-1] for axis in AXES}
        return {
            "device_status": status,
            "device_id": self.device_id,
            "measured_hz": self.measured_hz if fresh else 0.0,
            "last_packet_age_seconds": round(now - self.last_packet_time, 1) if self.last_packet_time > 0 else None,
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "speed_kmh": round(self.speed_kmh, 1) if fresh else 0.0,
            "latitude": self.latitude,
            "longitude": self.longitude,
            "heading": round(self.heading, 1),
            "gps_fix": self.gps_fix if fresh else False,
            "sensor_ok": self.sensor_ok if fresh else False,
            "accel": {"x": latest["ax"], "y": latest["ay"], "z": latest["az"]} if fresh else {"x": 0.0, "y": 0.0, "z": 9.81},
            "gyro": {"x": latest["gx"], "y": latest["gy"], "z": latest["gz"]} if fresh else {"x": 0.0, "y": 0.0, "z": 0.0},
            "recent_samples": {"z": recent["az"], "x": recent["ax"], "y": recent["ay"]},
            "classification": self.classification if fresh else dict(NORMAL_CLASSIFICATION),
            "engines": {"heuristic": self.heuristic, "random_forest": self.model_result} if fresh else None,
            "last_anomaly": self.last_anomaly_event,
            "is_simulated": self.is_demo_mode,
        }


class LiveHub:
    """Fan-out of JSON messages to every connected dashboard WebSocket."""

    def __init__(self):
        self.clients: Set[WebSocket] = set()
        self.loop: Optional[asyncio.AbstractEventLoop] = None

    async def connect(self, ws: WebSocket) -> None:
        await ws.accept()
        self.clients.add(ws)

    def disconnect(self, ws: WebSocket) -> None:
        self.clients.discard(ws)

    async def broadcast(self, message: Dict[str, Any]) -> None:
        dead: List[WebSocket] = []
        for ws in list(self.clients):
            try:
                await ws.send_json(message)
            except Exception:  # noqa: BLE001 - a closed socket just gets dropped
                dead.append(ws)
        for ws in dead:
            self.clients.discard(ws)

    def publish(self, message: Dict[str, Any]) -> None:
        """Broadcast from sync code running in FastAPI's threadpool."""
        if not self.clients or self.loop is None or self.loop.is_closed():
            return
        try:
            running = asyncio.get_running_loop()
        except RuntimeError:
            running = None
        if running is self.loop:
            self.loop.create_task(self.broadcast(message))
        else:
            asyncio.run_coroutine_threadsafe(self.broadcast(message), self.loop)

    async def telemetry_pump(self, telemetry: TelemetryState, interval: float = 0.25, heartbeat: float = 2.0) -> None:
        """Push telemetry when it changes (max 4/s) and at least every 2 s for freshness."""
        last_version, last_sent = -1, 0.0
        while True:
            await asyncio.sleep(interval)
            if not self.clients:
                continue
            now = time.time()
            if telemetry.version != last_version or now - last_sent >= heartbeat:
                last_version, last_sent = telemetry.version, now
                await self.broadcast({"type": "telemetry", "data": telemetry.snapshot()})
