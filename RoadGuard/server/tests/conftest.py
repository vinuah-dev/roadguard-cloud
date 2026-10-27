"""
Test setup: every run gets a fresh SQLite database in a temp folder, so the suite
needs no network, no PostgreSQL and never touches server/data/roadguard.db.
"""

import os
import sys
import tempfile

_TMP_DIR = tempfile.mkdtemp(prefix="roadguard-tests-")
# Set before the app is imported: settings are read once at import time,
# and python-dotenv never overrides variables that already exist.
os.environ["DATABASE_URL"] = "sqlite:///" + os.path.join(_TMP_DIR, "test.db").replace("\\", "/")
os.environ["ROADGUARD_ADMIN_TOKEN"] = ""
os.environ["ROADGUARD_ENABLE_TEST_API"] = "true"
os.environ["ROADGUARD_SEED_DEMO_DATA"] = "true"
os.environ["ROADGUARD_AUTO_INGEST"] = "true"
os.environ["ROADGUARD_PLATFORM"] = "pytest"

SERVER_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if SERVER_DIR not in sys.path:
    sys.path.insert(0, SERVER_DIR)

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402


@pytest.fixture(scope="session")
def client():
    from app import app

    with TestClient(app) as test_client:
        yield test_client
