"""
RoadGuard runtime configuration.

Everything is read once from environment variables (or server/.env), so the same
code runs on a laptop (SQLite, no setup) and on AWS (PostgreSQL, EC2 metadata).
"""

import os
from dataclasses import dataclass, field
from typing import Dict, List

from dotenv import load_dotenv

SERVER_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_DIR = os.path.dirname(SERVER_DIR)

load_dotenv(os.path.join(SERVER_DIR, ".env"))


def _env(name: str, default: str = "") -> str:
    return os.getenv(name, default).strip()


def _env_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() in ("1", "true", "yes", "on")


def _default_database_url() -> str:
    path = os.path.join(SERVER_DIR, "data", "roadguard.db").replace("\\", "/")
    return f"sqlite:///{path}"


@dataclass(frozen=True)
class Settings:
    app_version: str
    database_url: str
    admin_token: str
    enable_test_api: bool
    auto_ingest: bool
    seed_demo_data: bool
    cors_origins: List[str]
    log_level: str
    deployment: Dict[str, str] = field(default_factory=dict)


def load_settings() -> Settings:
    origins = [o.strip() for o in _env("ROADGUARD_CORS_ORIGINS", "*").split(",") if o.strip()]
    return Settings(
        app_version=_env("ROADGUARD_APP_VERSION", "4.0.0"),
        database_url=_env("DATABASE_URL") or _default_database_url(),
        admin_token=_env("ROADGUARD_ADMIN_TOKEN"),
        enable_test_api=_env_bool("ROADGUARD_ENABLE_TEST_API", True),
        auto_ingest=_env_bool("ROADGUARD_AUTO_INGEST", True),
        seed_demo_data=_env_bool("ROADGUARD_SEED_DEMO_DATA", True),
        cors_origins=origins or ["*"],
        log_level=_env("ROADGUARD_LOG_LEVEL", "INFO").upper(),
        # Filled in by deploy/aws/user-data.sh from the EC2 instance metadata service.
        deployment={
            "platform": _env("ROADGUARD_PLATFORM", "Local machine"),
            "region": _env("ROADGUARD_REGION"),
            "availability_zone": _env("ROADGUARD_AZ"),
            "instance_id": _env("ROADGUARD_INSTANCE_ID"),
            "instance_type": _env("ROADGUARD_INSTANCE_TYPE"),
            "public_host": _env("ROADGUARD_PUBLIC_HOST"),
        },
    )


settings = load_settings()
