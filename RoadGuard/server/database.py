"""
RoadGuard database layer (SQLAlchemy Core).

One schema, two backends:
  * SQLite      - default, zero setup, used for local development and the test suite.
  * PostgreSQL  - Docker / AWS (container on EC2 or Amazon RDS), selected with DATABASE_URL.

IDs are UUID strings and timestamps are stored in UTC so both backends behave the same.
"""

import logging
import os
import random
import uuid
from datetime import datetime, timedelta, timezone

from sqlalchemy import (
    Boolean,
    Column,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    MetaData,
    String,
    Table,
    create_engine,
    event,
    func,
    insert,
    select,
)
from sqlalchemy.engine import Engine

log = logging.getLogger("roadguard.db")

metadata = MetaData()

hazards = Table(
    "hazards",
    metadata,
    Column("id", String(36), primary_key=True),
    Column("hazard_type", String(20), nullable=False, index=True),
    Column("latitude", Float, nullable=False),
    Column("longitude", Float, nullable=False),
    Column("severity", String(10), nullable=False, default="MEDIUM"),
    Column("confidence", Float, nullable=False, default=0.75),
    Column("roughness_score", Float, nullable=False, default=5.0),
    Column("observation_count", Integer, nullable=False, default=1),
    Column("verification_status", String(12), nullable=False, default="PENDING"),
    Column("source", String(20), nullable=False, default="SENSOR", index=True),
    Column("address_description", String(255), nullable=False, default=""),
    Column("is_active", Boolean, nullable=False, default=True, index=True),
    Column("first_detected_at", DateTime(timezone=True), nullable=False),
    Column("last_detected_at", DateTime(timezone=True), nullable=False),
    Column("resolved_at", DateTime(timezone=True), nullable=True),
)
Index("ix_hazards_lat_lng", hazards.c.latitude, hazards.c.longitude)

hazard_observations = Table(
    "hazard_observations",
    metadata,
    Column("id", String(36), primary_key=True),
    Column("hazard_id", String(36), ForeignKey("hazards.id", ondelete="CASCADE"), index=True),
    Column("trip_id", String(64), nullable=True),
    Column("device_id", String(64), nullable=True),
    Column("hazard_type", String(20), nullable=False),
    Column("latitude", Float, nullable=False),
    Column("longitude", Float, nullable=False),
    Column("severity", String(10), nullable=False),
    Column("confidence", Float, nullable=False),
    Column("speed_kmh", Float, nullable=False, default=0.0),
    Column("az_peak", Float, nullable=False, default=0.0),
    Column("roughness_score", Float, nullable=False, default=0.0),
    Column("source", String(20), nullable=False, default="SENSOR"),
    Column("observed_at", DateTime(timezone=True), nullable=False, index=True),
)

trips = Table(
    "trips",
    metadata,
    Column("id", String(36), primary_key=True),
    Column("device_id", String(64), nullable=True),
    Column("status", String(12), nullable=False, default="ACTIVE"),
    Column("started_at", DateTime(timezone=True), nullable=False),
    Column("ended_at", DateTime(timezone=True), nullable=True),
    Column("distance_meters", Float, nullable=False, default=0.0),
    Column("duration_seconds", Integer, nullable=False, default=0),
    Column("potholes_count", Integer, nullable=False, default=0),
    Column("speed_breakers_count", Integer, nullable=False, default=0),
    Column("avg_roughness", Float, nullable=False, default=0.0),
)

devices = Table(
    "devices",
    metadata,
    Column("id", String(36), primary_key=True),
    Column("device_identifier", String(64), nullable=False, unique=True),
    Column("device_name", String(100), nullable=False),
    Column("status", String(20), nullable=False, default="ACTIVE"),
    Column("last_seen_at", DateTime(timezone=True), nullable=True),
    Column("created_at", DateTime(timezone=True), nullable=False),
)


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def new_id() -> str:
    return str(uuid.uuid4())


def iso(value):
    """Serialise a DB timestamp as ISO-8601 UTC (SQLite hands back naive datetimes)."""
    if value is None or isinstance(value, str):
        return value
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc).isoformat()


def normalise_url(url: str) -> str:
    """Accept the usual postgres:// forms and route them to the psycopg 3 driver."""
    if url.startswith("postgres://"):
        url = "postgresql://" + url[len("postgres://"):]
    if url.startswith("postgresql://"):
        url = "postgresql+psycopg://" + url[len("postgresql://"):]
    return url


def create_db_engine(url: str) -> Engine:
    url = normalise_url(url)
    if url.startswith("sqlite"):
        path = url.split(":///", 1)[1] if ":///" in url else ""
        if path and path != ":memory:":
            os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        engine = create_engine(url, connect_args={"check_same_thread": False})

        @event.listens_for(engine, "connect")
        def _sqlite_pragmas(dbapi_conn, _record):
            cur = dbapi_conn.cursor()
            cur.execute("PRAGMA foreign_keys=ON")
            cur.execute("PRAGMA journal_mode=WAL")
            cur.close()

        return engine
    return create_engine(url, pool_pre_ping=True, pool_size=5, max_overflow=5, pool_recycle=1800)


def backend_name(engine: Engine) -> str:
    return {"sqlite": "SQLite", "postgresql": "PostgreSQL"}.get(engine.dialect.name, engine.dialect.name)


def safe_url(engine: Engine) -> str:
    """Connection URL with the password masked, for /health and logs."""
    return engine.url.render_as_string(hide_password=True)


# Landmarks around RCOEM, Katol Road, Nagpur (same records as the original schema_postgres.sql seed).
SEED_HAZARDS = [
    # type, lat, lng, severity, roughness, confirmations, description, days ago first seen
    ("POTHOLE", 21.1782, 79.0625, "HIGH", 8.2, 18, "Near RCOEM Main Gate, Katol Road, Nagpur", 13),
    ("SPEED_BREAKER", 21.1765, 79.0608, "MEDIUM", 5.6, 26, "Opposite Ramdeobaba Temple entrance curve", 11),
    ("POTHOLE", 21.1795, 79.0640, "CRITICAL", 9.4, 32, "Gittikhadan Square outer intersection", 9),
    ("SPEED_BREAKER", 21.1750, 79.0590, "LOW", 4.1, 12, "Near IDEA Lab engineering block", 6),
    ("POTHOLE", 21.1820, 79.0670, "HIGH", 7.9, 15, "Friends Colony main road turn", 3),
]


def seed_demo_data(engine: Engine) -> int:
    """Insert the Nagpur demo hazards (with their observation history) into an empty database."""
    rng = random.Random(42)
    now = utcnow()
    with engine.begin() as conn:
        if conn.execute(select(func.count()).select_from(hazards)).scalar_one() > 0:
            return 0
        for h_type, lat, lng, sev, rough, count, desc, days_ago in SEED_HAZARDS:
            hazard_id = new_id()
            first_seen = now - timedelta(days=days_ago, hours=rng.uniform(1, 9))
            span = (now - first_seen).total_seconds()
            times = sorted(first_seen + timedelta(seconds=rng.uniform(0, span)) for _ in range(count - 1))
            times.insert(0, first_seen)
            conn.execute(insert(hazards).values(
                id=hazard_id, hazard_type=h_type, latitude=lat, longitude=lng, severity=sev,
                confidence=round(min(0.99, 0.80 + count / 200), 2), roughness_score=rough,
                observation_count=count, verification_status="VERIFIED", source="SENSOR",
                address_description=desc, is_active=True,
                first_detected_at=first_seen, last_detected_at=times[-1],
            ))
            conn.execute(insert(hazard_observations), [
                {
                    "id": new_id(), "hazard_id": hazard_id, "trip_id": None, "device_id": "RoadGuard-ESP32",
                    "hazard_type": h_type,
                    "latitude": lat + rng.uniform(-0.00006, 0.00006),
                    "longitude": lng + rng.uniform(-0.00006, 0.00006),
                    "severity": sev, "confidence": round(rng.uniform(0.78, 0.97), 2),
                    "speed_kmh": round(rng.uniform(18, 55), 1),
                    "az_peak": round(9.81 + rough * rng.uniform(1.1, 1.8), 2),
                    "roughness_score": round(rough + rng.uniform(-0.6, 0.6), 2),
                    "source": "SENSOR", "observed_at": t,
                }
                for t in times
            ])
    return len(SEED_HAZARDS)


def init_db(engine: Engine, seed: bool = True) -> None:
    metadata.create_all(engine)
    if seed:
        added = seed_demo_data(engine)
        if added:
            log.info("Seeded %d demo hazards", added)
