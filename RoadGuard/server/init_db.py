"""
RoadGuard - database check / initialiser.

Creates the tables (and the Nagpur demo hazards on an empty database) in whatever
DATABASE_URL points at, then prints row counts. Safe to run more than once.

    python init_db.py
"""

from sqlalchemy import func, select

from database import backend_name, create_db_engine, devices, hazard_observations, hazards, init_db, safe_url, trips
from settings import settings


def verify_connection() -> bool:
    engine = create_db_engine(settings.database_url)
    print(f"Connecting to {backend_name(engine)}: {safe_url(engine)} ...")
    try:
        init_db(engine, seed=settings.seed_demo_data)
        print("\nTable row counts:")
        with engine.connect() as conn:
            for table in (hazards, hazard_observations, trips, devices):
                count = conn.execute(select(func.count()).select_from(table)).scalar_one()
                print(f"  {table.name:<30} {count} rows")
        print(f"\n{backend_name(engine)} connection verified successfully!")
        return True
    except Exception as e:  # noqa: BLE001
        print(f"Connection failed: {e}")
        return False


if __name__ == "__main__":
    raise SystemExit(0 if verify_connection() else 1)
