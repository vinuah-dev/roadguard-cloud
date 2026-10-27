"""
Hazard store: ingestion with 15 m spatial de-duplication, map queries, proximity
search, moderation, analytics and export. Works on SQLite and PostgreSQL alike.
"""

import threading
from collections import Counter
from datetime import date, datetime, timedelta, timezone
from typing import Any, Dict, List, Optional, Tuple

from sqlalchemy import and_, delete, desc, func, insert, select, text, update
from sqlalchemy.engine import Engine

from database import devices, hazard_observations, hazards, iso, new_id, trips, utcnow
from geo import approach_status, bearing_deg, degree_pad, haversine_m

DEDUP_RADIUS_M = 15.0
VERIFY_AFTER_OBSERVATIONS = 3
DEVICE_TOUCH_INTERVAL_S = 10.0

HAZARD_TYPES = ("POTHOLE", "SPEED_BREAKER")
SEVERITIES = ("LOW", "MEDIUM", "HIGH", "CRITICAL", "UNKNOWN")
SEVERITY_RANK = {"UNKNOWN": 0, "LOW": 1, "MEDIUM": 2, "HIGH": 3, "CRITICAL": 4}
SOURCES = ("SENSOR", "USER_REPORT", "ADMIN_IMPORT", "TEST")
VERIFICATION_STATES = ("PENDING", "VERIFIED", "DISPUTED", "REJECTED")


def normalise_source(source: Optional[str]) -> str:
    value = (source or "SENSOR").upper().strip()
    return value if value in SOURCES else "SENSOR"


def normalise_severity(severity: Optional[str]) -> str:
    value = (severity or "MEDIUM").upper().strip()
    return value if value in SEVERITIES else "MEDIUM"


def _as_utc(value: Optional[datetime]) -> Optional[datetime]:
    if value is None:
        return None
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value


def _hazard_dict(row) -> Dict[str, Any]:
    d = dict(row._mapping)
    for key in ("first_detected_at", "last_detected_at", "resolved_at"):
        d[key] = iso(d.get(key))
    return d


def _observation_dict(row) -> Dict[str, Any]:
    d = dict(row._mapping)
    d["observed_at"] = iso(d.get("observed_at"))
    return d


class HazardStore:
    def __init__(self, engine: Engine):
        self.engine = engine
        # One API process owns the database writes, so a lock is enough to make
        # "find nearby hazard, then insert or update" atomic for concurrent requests.
        self._ingest_lock = threading.Lock()
        self._device_seen: Dict[str, float] = {}

    # ------------------------------------------------------------------ health
    def ping(self) -> None:
        with self.engine.connect() as conn:
            conn.execute(text("SELECT 1"))

    def counts(self) -> Dict[str, int]:
        with self.engine.connect() as conn:
            total = conn.execute(select(func.count()).select_from(hazards)).scalar_one()
            active = conn.execute(
                select(func.count()).select_from(hazards).where(hazards.c.is_active.is_(True))
            ).scalar_one()
            observations = conn.execute(select(func.count()).select_from(hazard_observations)).scalar_one()
        return {"hazards": total, "active_hazards": active, "observations": observations}

    # --------------------------------------------------------------- ingestion
    def ingest(
        self,
        *,
        hazard_type: str,
        latitude: float,
        longitude: float,
        severity: str = "MEDIUM",
        roughness_score: float = 6.0,
        speed_kmh: float = 0.0,
        az_peak: float = 0.0,
        confidence: Optional[float] = None,
        trip_id: Optional[str] = None,
        device_id: Optional[str] = None,
        source: str = "SENSOR",
        observed_at: Optional[datetime] = None,
        address_description: str = "",
    ) -> Tuple[Dict[str, Any], bool]:
        """Record one observation. Returns (hazard, created) where created is False
        when the observation was merged into an existing hazard within 15 m."""
        h_type = hazard_type.upper().strip()
        if h_type not in HAZARD_TYPES:
            raise ValueError("hazard_type must be POTHOLE or SPEED_BREAKER")
        src = normalise_source(source)
        sev = normalise_severity(severity)
        if confidence is None:
            confidence = roughness_score / 10.0
        elif confidence > 1.0:
            confidence = confidence / 100.0
        conf = min(1.0, max(0.1, round(float(confidence), 2)))
        when = _as_utc(observed_at) or utcnow()
        d_lat, d_lon = degree_pad(latitude, DEDUP_RADIUS_M)

        with self._ingest_lock, self.engine.begin() as conn:
            candidates = conn.execute(
                select(hazards).where(and_(
                    hazards.c.hazard_type == h_type,
                    hazards.c.is_active.is_(True),
                    hazards.c.latitude.between(latitude - d_lat, latitude + d_lat),
                    hazards.c.longitude.between(longitude - d_lon, longitude + d_lon),
                ))
            ).fetchall()
            matched, best = None, DEDUP_RADIUS_M
            for row in candidates:
                dist = haversine_m(latitude, longitude, row.latitude, row.longitude)
                if dist <= best:
                    matched, best = row, dist

            if matched is not None:
                hazard_id = matched.id
                count = (matched.observation_count or 1) + 1
                old_sev = matched.severity or "MEDIUM"
                values = {
                    "observation_count": count,
                    "last_detected_at": max(when, _as_utc(matched.last_detected_at) or when),
                    "confidence": min(1.0, max(0.1, round(((matched.confidence or 0.75) * (count - 1) + conf) / count, 2))),
                    "roughness_score": round((matched.roughness_score or roughness_score) * 0.7 + roughness_score * 0.3, 2),
                    "severity": sev if SEVERITY_RANK[sev] > SEVERITY_RANK.get(old_sev, 0) else old_sev,
                }
                if matched.verification_status == "PENDING" and count >= VERIFY_AFTER_OBSERVATIONS:
                    values["verification_status"] = "VERIFIED"
                conn.execute(update(hazards).where(hazards.c.id == hazard_id).values(**values))
                created = False
            else:
                hazard_id = new_id()
                conn.execute(insert(hazards).values(
                    id=hazard_id, hazard_type=h_type, latitude=latitude, longitude=longitude,
                    severity=sev, confidence=conf, roughness_score=round(roughness_score, 2),
                    observation_count=1, verification_status="PENDING", source=src,
                    address_description=(address_description or "")[:255], is_active=True,
                    first_detected_at=when, last_detected_at=when,
                ))
                created = True

            conn.execute(insert(hazard_observations).values(
                id=new_id(), hazard_id=hazard_id, trip_id=trip_id, device_id=device_id,
                hazard_type=h_type, latitude=latitude, longitude=longitude, severity=sev,
                confidence=conf, speed_kmh=speed_kmh, az_peak=az_peak,
                roughness_score=roughness_score, source=src, observed_at=when,
            ))
            hazard = conn.execute(select(hazards).where(hazards.c.id == hazard_id)).one()
        return _hazard_dict(hazard), created

    # ------------------------------------------------------------------ queries
    def list(
        self,
        *,
        hazard_type: Optional[str] = None,
        severity: Optional[str] = None,
        source: Optional[str] = None,
        include_inactive: bool = False,
        lat: Optional[float] = None,
        lng: Optional[float] = None,
        radius: Optional[float] = None,
        bbox: Optional[Tuple[float, float, float, float]] = None,
        limit: int = 300,
        offset: int = 0,
    ) -> List[Dict[str, Any]]:
        query = select(hazards)
        if hazard_type and hazard_type.upper() != "ALL":
            query = query.where(hazards.c.hazard_type == hazard_type.upper())
        if severity and severity.upper() != "ALL":
            query = query.where(hazards.c.severity == severity.upper())
        if source and source.upper() != "ALL":
            query = query.where(hazards.c.source == source.upper())
        if not include_inactive:
            query = query.where(hazards.c.is_active.is_(True))

        if lat is not None and lng is not None:
            radius = radius or 5000.0
            d_lat, d_lon = degree_pad(lat, radius)
            query = query.where(and_(
                hazards.c.latitude.between(lat - d_lat, lat + d_lat),
                hazards.c.longitude.between(lng - d_lon, lng + d_lon),
            ))
            with self.engine.connect() as conn:
                rows = conn.execute(query).fetchall()
            result = []
            for row in rows:
                dist = haversine_m(lat, lng, row.latitude, row.longitude)
                if dist <= radius:
                    item = _hazard_dict(row)
                    item["distance_meters"] = round(dist, 1)
                    result.append(item)
            result.sort(key=lambda h: h["distance_meters"])
            return result[offset:offset + limit]

        if bbox is not None:
            min_lat, max_lat, min_lon, max_lon = bbox
            query = query.where(and_(
                hazards.c.latitude.between(min_lat, max_lat),
                hazards.c.longitude.between(min_lon, max_lon),
            )).order_by(desc(hazards.c.last_detected_at))
        else:
            query = query.order_by(desc(hazards.c.first_detected_at))
        with self.engine.connect() as conn:
            rows = conn.execute(query.limit(limit).offset(offset)).fetchall()
        return [_hazard_dict(r) for r in rows]

    def nearby(self, lat: float, lng: float, radius: float = 150.0,
               heading: Optional[float] = None, limit: int = 20) -> List[Dict[str, Any]]:
        results = []
        for h in self.list(lat=lat, lng=lng, radius=radius, limit=500):
            bearing = bearing_deg(lat, lng, h["latitude"], h["longitude"])
            h["bearing_degrees"] = round(bearing, 1)
            h["approach_status"] = approach_status(bearing, heading)
            results.append(h)
        return results[:limit]

    def get(self, hazard_id: str) -> Optional[Dict[str, Any]]:
        with self.engine.connect() as conn:
            row = conn.execute(select(hazards).where(hazards.c.id == hazard_id)).first()
            if row is None:
                return None
            obs = conn.execute(
                select(hazard_observations)
                .where(hazard_observations.c.hazard_id == hazard_id)
                .order_by(desc(hazard_observations.c.observed_at))
                .limit(25)
            ).fetchall()
        return {"hazard": _hazard_dict(row), "observations": [_observation_dict(o) for o in obs]}

    def update(self, hazard_id: str, changes: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        values: Dict[str, Any] = {}
        if "is_active" in changes and changes["is_active"] is not None:
            values["is_active"] = bool(changes["is_active"])
            values["resolved_at"] = None if values["is_active"] else utcnow()
        if changes.get("verification_status"):
            status = changes["verification_status"].upper()
            if status not in VERIFICATION_STATES:
                raise ValueError(f"verification_status must be one of {', '.join(VERIFICATION_STATES)}")
            values["verification_status"] = status
        if changes.get("severity"):
            sev = changes["severity"].upper()
            if sev not in SEVERITIES:
                raise ValueError(f"severity must be one of {', '.join(SEVERITIES)}")
            values["severity"] = sev
        if changes.get("address_description") is not None:
            values["address_description"] = str(changes["address_description"])[:255]
        with self.engine.begin() as conn:
            if values:
                result = conn.execute(update(hazards).where(hazards.c.id == hazard_id).values(**values))
                if result.rowcount == 0:
                    return None
            row = conn.execute(select(hazards).where(hazards.c.id == hazard_id)).first()
        return _hazard_dict(row) if row else None

    # ---------------------------------------------------------------- analytics
    def stats(self, days: int = 14) -> Dict[str, Any]:
        days = max(1, min(days, 90))
        now = utcnow()
        since = now - timedelta(days=days - 1)
        since_midnight = datetime(since.year, since.month, since.day, tzinfo=timezone.utc)
        with self.engine.connect() as conn:
            rows = conn.execute(select(
                hazards.c.hazard_type, hazards.c.severity, hazards.c.source,
                hazards.c.verification_status, hazards.c.is_active, hazards.c.first_detected_at,
                hazards.c.observation_count,
            )).fetchall()
            obs_times = conn.execute(
                select(hazard_observations.c.observed_at, hazard_observations.c.hazard_type)
                .where(hazard_observations.c.observed_at >= since_midnight)
            ).fetchall()

        real = [r for r in rows if r.source != "TEST"]
        active_real = [r for r in real if r.is_active]
        day_keys = [(since_midnight + timedelta(days=i)).date() for i in range(days)]
        new_by_day: Counter = Counter()
        for r in real:
            first = _as_utc(r.first_detected_at)
            if first and first >= since_midnight:
                new_by_day[(first.date(), r.hazard_type)] += 1
        obs_by_day: Counter = Counter()
        obs_24h = 0
        for o in obs_times:
            t = _as_utc(o.observed_at)
            obs_by_day[t.date()] += 1
            if t >= now - timedelta(hours=24):
                obs_24h += 1

        def day_label(d: date) -> str:
            return d.isoformat()

        return {
            "generated_at": now.isoformat(),
            "totals": {
                "hazards": len(real),
                "active": len(active_real),
                "resolved": len(real) - len(active_real),
                "potholes": sum(1 for r in active_real if r.hazard_type == "POTHOLE"),
                "speed_breakers": sum(1 for r in active_real if r.hazard_type == "SPEED_BREAKER"),
                "verified": sum(1 for r in active_real if r.verification_status == "VERIFIED"),
                "observations": sum(int(r.observation_count or 0) for r in real),
                "observations_24h": obs_24h,
                "test_records": len(rows) - len(real),
            },
            "by_severity": {s: sum(1 for r in active_real if r.severity == s) for s in ("LOW", "MEDIUM", "HIGH", "CRITICAL")},
            "by_source": dict(Counter(r.source for r in active_real)),
            "daily": [
                {
                    "date": day_label(d),
                    "new_potholes": new_by_day[(d, "POTHOLE")],
                    "new_speed_breakers": new_by_day[(d, "SPEED_BREAKER")],
                    "observations": obs_by_day[d],
                }
                for d in day_keys
            ],
        }

    def export_rows(self, source: Optional[str] = None, hazard_type: Optional[str] = None,
                    include_inactive: bool = True) -> List[Dict[str, Any]]:
        return self.list(source=source, hazard_type=hazard_type,
                         include_inactive=include_inactive, limit=100_000)

    # --------------------------------------------------------------- test data
    def reset_test_data(self) -> Tuple[int, int]:
        with self._ingest_lock, self.engine.begin() as conn:
            ids = [r.id for r in conn.execute(select(hazards.c.id).where(hazards.c.source == "TEST"))]
            if not ids:
                return 0, 0
            obs_deleted = conn.execute(
                delete(hazard_observations).where(hazard_observations.c.hazard_id.in_(ids))
            ).rowcount
            hazards_deleted = conn.execute(delete(hazards).where(hazards.c.id.in_(ids))).rowcount
        return hazards_deleted, obs_deleted

    # ------------------------------------------------------------ trips/devices
    def list_trips(self, limit: int = 50, offset: int = 0) -> List[Dict[str, Any]]:
        with self.engine.connect() as conn:
            rows = conn.execute(select(trips).order_by(desc(trips.c.started_at)).limit(limit).offset(offset)).fetchall()
        out = []
        for r in rows:
            d = dict(r._mapping)
            d["started_at"], d["ended_at"] = iso(d["started_at"]), iso(d["ended_at"])
            out.append(d)
        return out

    def create_trip(self, values: Dict[str, Any]) -> str:
        trip_id = new_id()
        with self.engine.begin() as conn:
            conn.execute(insert(trips).values(id=trip_id, status="ACTIVE", started_at=utcnow(), **values))
        return trip_id

    def touch_device(self, identifier: str, now_ts: float) -> None:
        """Remember when a device last streamed, at most once every 10 s per device."""
        if not identifier or now_ts - self._device_seen.get(identifier, 0.0) < DEVICE_TOUCH_INTERVAL_S:
            return
        self._device_seen[identifier] = now_ts
        now = utcnow()
        with self.engine.begin() as conn:
            result = conn.execute(
                update(devices).where(devices.c.device_identifier == identifier).values(last_seen_at=now, status="ACTIVE")
            )
            if result.rowcount == 0:
                conn.execute(insert(devices).values(
                    id=new_id(), device_identifier=identifier[:64], device_name=identifier[:100],
                    status="ACTIVE", last_seen_at=now, created_at=now,
                ))

    def list_devices(self) -> List[Dict[str, Any]]:
        with self.engine.connect() as conn:
            rows = conn.execute(select(devices).order_by(desc(devices.c.last_seen_at))).fetchall()
        out = []
        for r in rows:
            d = dict(r._mapping)
            d["last_seen_at"], d["created_at"] = iso(d["last_seen_at"]), iso(d["created_at"])
            out.append(d)
        return out
