"""
RoadGuard Automated Test Suite
Validates API endpoints, database deduplication, 3-class classification handling,
spatial proximity calculations, ML metrics, export, test isolation, and the v4
additions: live ML fusion, auto-ingest, analytics, moderation, admin token and WebSocket push.
"""

import dataclasses

import numpy as np
import pytest

import app as app_module


def test_health_endpoint(client):
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ok"
    assert "database" in data
    assert data["database"]["status"] == "CONNECTED"
    assert "ml_engine" in data
    assert data["ml_engine"]["model_configured"] is True
    assert "NORMAL" in data["ml_engine"]["classes"]
    assert "POTHOLE" in data["ml_engine"]["classes"]
    assert "SPEED_BREAKER" in data["ml_engine"]["classes"]


def test_readiness_endpoint(client):
    response = client.get("/health/ready")
    assert response.status_code == 200
    assert response.json()["status"] == "ready"


def test_devices_endpoint(client):
    response = client.get("/api/devices")
    assert response.status_code == 200
    devices = response.json()
    assert isinstance(devices, list)
    assert len(devices) > 0
    assert "RoadGuard-ESP32" in [d["device_name"] for d in devices]


def test_telemetry_ingest_and_live_polling(client):
    packet = {
        "device_id": "RoadGuard-ESP32-TEST",
        "accel_x": 0.15,
        "accel_y": -0.05,
        "accel_z": 9.81,
        "speed_kmh": 40.0,
        "latitude": 21.1777,
        "longitude": 79.0617,
        "gps_fix": True,
        "sensor_ok": True,
        "source": "TEST",
    }
    post_res = client.post("/api/telemetry", json=packet)
    assert post_res.status_code == 200
    assert post_res.json()["status"] == "received"

    get_res = client.get("/api/telemetry/live")
    assert get_res.status_code == 200
    snap = get_res.json()
    assert snap["device_id"] == "RoadGuard-ESP32-TEST"
    assert snap["gps_fix"] is True
    assert "classification" in snap


def test_normal_road_never_saved_as_hazard(client):
    payload = {"hazard_type": "NORMAL", "latitude": 21.1777, "longitude": 79.0617, "severity": "LOW", "source": "TEST"}
    response = client.post("/api/hazards", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ignored"
    assert "NORMAL condition is not stored" in data["message"]


def test_hazard_ingest_and_deduplication(client):
    test_lat, test_lng = 21.1850, 79.0700
    payload = {
        "hazard_type": "POTHOLE", "latitude": test_lat, "longitude": test_lng, "severity": "HIGH",
        "roughness_score": 8.0, "speed_kmh": 35.0, "az_peak": 22.0, "source": "TEST",
    }
    res1 = client.post("/api/hazards", json=payload)
    assert res1.status_code == 200
    assert res1.json()["created"] is True
    hazard_id1 = res1.json()["hazard_id"]

    # ~7 m away: must merge into the same hazard
    payload2 = {**payload, "latitude": test_lat + 0.00005, "longitude": test_lng + 0.00005,
                "roughness_score": 8.5, "speed_kmh": 36.0, "az_peak": 23.0}
    res2 = client.post("/api/hazards", json=payload2)
    assert res2.status_code == 200
    assert res2.json()["hazard_id"] == hazard_id1, "Nearby observations within 15m must deduplicate to same hazard!"
    assert res2.json()["created"] is False
    assert res2.json()["observation_count"] == 2

    # Every observation is kept in hazard_observations
    details = client.get(f"/api/hazards/{hazard_id1}").json()
    assert details["hazard"]["observation_count"] == 2
    assert len(details["observations"]) == 2

    client.post("/api/test/reset")


def test_third_observation_verifies_hazard(client):
    base = {"hazard_type": "SPEED_BREAKER", "latitude": 21.1900, "longitude": 79.0800, "source": "TEST"}
    for _ in range(3):
        res = client.post("/api/hazards", json=base).json()
    assert res["observation_count"] == 3
    assert res["verification_status"] == "VERIFIED"
    client.post("/api/test/reset")


def test_nearby_hazards_and_approach_direction(client):
    response = client.get("/api/hazards/nearby?lat=21.1777&lng=79.0617&radius=1000&heading=55")
    assert response.status_code == 200
    nearby = response.json()
    assert isinstance(nearby, list)
    assert nearby, "seeded hazards are within 1 km of the default position"
    first = nearby[0]
    assert "distance_meters" in first
    assert "bearing_degrees" in first
    assert first["approach_status"] in ["APPROACHING", "RECEDING", "LATERAL", "PROXIMITY_ONLY"]


def test_ml_metrics_endpoint(client):
    response = client.get("/api/metrics")
    assert response.status_code == 200
    metrics = response.json()
    assert metrics["status"] == "EVALUATED"
    assert "overall_accuracy" in metrics
    for cls in ("NORMAL", "POTHOLE", "SPEED_BREAKER"):
        assert cls in metrics["per_class_metrics"]
    matrix = metrics["confusion_matrix"]["matrix"]
    assert len(matrix) == 3 and sum(map(sum, matrix)) == metrics["test_samples"]
    # Importances come from the trained model, so they are real fractions of 100 %
    assert metrics["top_features"] and all(0 < f["importance_pct"] <= 100 for f in metrics["top_features"])


def test_export_endpoints(client):
    res_json = client.get("/api/export?format=json")
    assert res_json.status_code == 200
    assert res_json.headers["content-type"] == "application/json"

    res_csv = client.get("/api/export?format=csv")
    assert res_csv.status_code == 200
    assert "text/csv" in res_csv.headers["content-type"]
    assert "latitude" in res_csv.text and "hazard_type" in res_csv.text


def test_offline_sync_batch(client):
    batch = {
        "device_id": "RoadGuard-Android-App",
        "records": [
            {"hazard_type": "SPEED_BREAKER", "latitude": 21.1755, "longitude": 79.0595, "severity": "MEDIUM",
             "roughness_score": 5.5, "speed_kmh": 25.0, "az_peak": 14.0, "source": "TEST"},
            {"hazard_type": "NORMAL", "latitude": 21.1756, "longitude": 79.0596, "source": "TEST"},
        ],
    }
    response = client.post("/api/sync", json=batch)
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "success"
    assert data["synced_count"] == 1
    assert data["skipped_count"] == 1  # NORMAL is skipped
    client.post("/api/test/reset")


def test_test_data_isolation_and_reset(client):
    client.post("/api/test/event", json={"event_type": "POTHOLE", "latitude": 21.2850, "longitude": 79.1850, "save_to_db": True})

    hazards_before = client.get("/api/hazards?source=TEST").json()
    assert len(hazards_before) >= 1
    assert all(h["source"] == "TEST" for h in hazards_before)

    reset_res = client.post("/api/test/reset")
    assert reset_res.status_code == 200
    assert reset_res.json()["hazards_deleted"] >= 1

    assert len(client.get("/api/hazards?source=TEST").json()) == 0
    assert len(client.get("/api/hazards?source=SENSOR").json()) > 0


def test_health_database_provider_contract(client):
    db = client.get("/health").json()["database"]
    assert db["provider"] == "SQLite"
    assert db["status"] == "CONNECTED"
    assert db["display_status"] == "SQLITE CONNECTED"
    assert db["error"] is None
    assert db["hazards_count"] >= 3


def test_map_filters_backend(client):
    potholes = client.get("/api/hazards?hazard_type=POTHOLE").json()
    assert potholes and all(h["hazard_type"] == "POTHOLE" for h in potholes)
    breakers = client.get("/api/hazards?hazard_type=SPEED_BREAKER").json()
    assert breakers and all(h["hazard_type"] == "SPEED_BREAKER" for h in breakers)
    sensor_data = client.get("/api/hazards?source=SENSOR").json()
    assert all(h["source"] == "SENSOR" for h in sensor_data)


def test_gps_loss_and_recovery(client):
    res = client.post("/api/test/event", json={"event_type": "GPS_INVALID"})
    assert res.status_code == 200
    assert client.get("/api/telemetry/live").json()["gps_fix"] is False

    client.post("/api/telemetry", json={
        "device_id": "RoadGuard-ESP32-TEST", "accel_x": 0.0, "accel_y": 0.0, "accel_z": 9.81,
        "latitude": 21.1777, "longitude": 79.0617, "speed_kmh": 40.0, "gps_fix": True, "sensor_ok": True,
        "source": "TEST",
    })
    snap2 = client.get("/api/telemetry/live").json()
    assert snap2["gps_fix"] is True
    assert snap2["latitude"] == 21.1777


def test_empty_and_failed_requests(client):
    assert client.get("/api/hazards/00000000-0000-0000-0000-000000000000").status_code == 404
    res_bad = client.post("/api/hazards", json={"hazard_type": "UNKNOWN_CRATER", "latitude": 21.1777, "longitude": 79.0617})
    assert res_bad.status_code == 400
    res_bad_coords = client.post("/api/hazards", json={"hazard_type": "POTHOLE", "latitude": 999.0, "longitude": 79.0617})
    assert res_bad_coords.status_code == 422
    assert client.post("/api/test/event", json={"event_type": "METEOR"}).status_code == 400


def test_proximity_calculation_and_straight_line(client):
    res = client.get("/api/hazards/nearby?lat=21.1777&lng=79.0617&radius=1000&heading=0")
    assert res.status_code == 200
    for h in res.json():
        assert "distance_meters" in h and "bearing_degrees" in h and "approach_status" in h
        assert h["distance_meters"] <= 1000.0


def test_dashboard_html_contains_required_elements(client):
    res = client.get("/")
    assert res.status_code == 200
    html = res.text
    for text in [
        "TEST SIREN", "TEST VOICE ALERT", "VOICE ALERTS", "STOP ALERT", "AUDIO READY",
        "Simulate Approaching Hazard",
        "Warning! Pothole ahead. Reduce speed.", "Warning! Speed breaker ahead. Reduce speed.",
        "Potholes Only", "Breakers Only", "Real Detections", "Test Data",
        "STRAIGHT-LINE DISTANCE",
        "/ws/live", "Analytics", "Report Hazard",
    ]:
        assert text in html, text


# --------------------------------------------------------------------------- v4


@pytest.mark.parametrize("event", ["POTHOLE", "SPEED_BREAKER", "NORMAL"])
def test_live_ml_classifies_injected_events(client, event):
    np.random.seed(7)
    res = client.post("/api/test/event", json={"event_type": event, "latitude": 21.30, "longitude": 79.20, "save_to_db": False})
    assert res.status_code == 200
    cls = res.json()["classification"]
    assert cls["condition"] == event
    assert cls["engine"] in ("fused", "random_forest", "heuristic")

    snap = client.get("/api/telemetry/live").json()
    assert snap["engines"]["random_forest"]["probabilities"].keys() == {"NORMAL", "POTHOLE", "SPEED_BREAKER"}


def test_auto_ingest_from_live_stream(client):
    from dataset_generator import generate_normal_window, generate_pothole_window

    np.random.seed(3)
    lat, lng = 21.2222, 79.1111
    stream = [generate_normal_window(speed_kmh=40.0), generate_pothole_window(speed_kmh=40.0, severity=1.2)]
    hazard_ids = []
    for window in stream:
        for i in range(50):
            res = client.post("/api/telemetry", json={
                "device_id": "RoadGuard-ESP32-ROAD", "source": "SENSOR", "gps_fix": True,
                "latitude": lat, "longitude": lng, "speed_kmh": 40.0,
                "accel_x": float(window["ax"][i]), "accel_y": float(window["ay"][i]), "accel_z": float(window["az"][i]),
                "gyro_x": float(window["gx"][i]), "gyro_y": float(window["gy"][i]), "gyro_z": float(window["gz"][i]),
            }).json()
            if res["hazard_id"]:
                hazard_ids.append(res["hazard_id"])

    assert len(hazard_ids) == 1, "one pothole in the stream must be stored exactly once"
    stored = client.get(f"/api/hazards?lat={lat}&lng={lng}&radius=30").json()
    assert [h["id"] for h in stored] == hazard_ids
    assert stored[0]["hazard_type"] == "POTHOLE" and stored[0]["source"] == "SENSOR"
    assert "RoadGuard-ESP32-ROAD" in [d["device_identifier"] for d in client.get("/api/devices").json()]


def test_stale_window_is_cleared_for_a_new_stream(client):
    import telemetry as telemetry_module

    np.random.seed(11)
    client.post("/api/test/event", json={"event_type": "POTHOLE", "latitude": 21.33, "longitude": 79.33, "save_to_db": False})
    assert client.get("/api/telemetry/live").json()["classification"]["condition"] == "POTHOLE"

    app_module.telemetry.last_packet_time -= telemetry_module.FRESH_SECONDS + 1  # the stream went quiet
    res = client.post("/api/telemetry", json={"source": "TEST", "gps_fix": True, "latitude": 21.33, "longitude": 79.33,
                                              "accel_z": 9.81, "speed_kmh": 30.0}).json()
    assert res["classification"]["condition"] == "NORMAL"


def test_stats_endpoint(client):
    client.post("/api/test/event", json={"event_type": "POTHOLE", "latitude": 21.40, "longitude": 79.40})
    stats = client.get("/api/stats?days=14").json()
    assert len(stats["daily"]) == 14
    totals = stats["totals"]
    assert totals["hazards"] >= 5
    assert totals["test_records"] >= 1
    assert totals["potholes"] + totals["speed_breakers"] == totals["active"]
    assert sum(d["observations"] for d in stats["daily"]) > 0
    assert set(stats["by_severity"]) == {"LOW", "MEDIUM", "HIGH", "CRITICAL"}
    client.post("/api/test/reset")


def test_resolve_hazard_hides_it_from_map(client):
    hid = client.post("/api/hazards", json={"hazard_type": "POTHOLE", "latitude": 21.50, "longitude": 79.50,
                                            "source": "USER_REPORT"}).json()["hazard_id"]
    res = client.patch(f"/api/hazards/{hid}", json={"is_active": False})
    assert res.status_code == 200
    assert res.json()["hazard"]["resolved_at"] is not None

    visible = [h["id"] for h in client.get("/api/hazards").json()]
    assert hid not in visible
    everything = [h["id"] for h in client.get("/api/hazards?include_inactive=true").json()]
    assert hid in everything

    # A new detection at a repaired spot opens a new hazard instead of reviving the old one
    again = client.post("/api/hazards", json={"hazard_type": "POTHOLE", "latitude": 21.50, "longitude": 79.50}).json()
    assert again["hazard_id"] != hid and again["created"] is True

    assert client.patch(f"/api/hazards/{hid}", json={"verification_status": "BOGUS"}).status_code == 400
    assert client.patch("/api/hazards/does-not-exist", json={"is_active": False}).status_code == 404


def test_admin_token_protects_moderation(client, monkeypatch):
    monkeypatch.setattr(app_module, "settings", dataclasses.replace(app_module.settings, admin_token="s3cret"))
    hid = client.get("/api/hazards").json()[0]["id"]
    assert client.patch(f"/api/hazards/{hid}", json={"severity": "HIGH"}).status_code == 401
    assert client.post("/api/test/reset").status_code == 401
    ok = client.patch(f"/api/hazards/{hid}", json={"severity": "HIGH"}, headers={"X-Admin-Token": "s3cret"})
    assert ok.status_code == 200


def test_test_api_can_be_disabled(client, monkeypatch):
    monkeypatch.setattr(app_module, "settings", dataclasses.replace(app_module.settings, enable_test_api=False))
    assert client.post("/api/test/event", json={"event_type": "NORMAL"}).status_code == 403


def test_websocket_hello_and_hazard_push(client):
    with client.websocket_connect("/ws/live") as ws:
        hello = ws.receive_json()
        assert hello["type"] == "hello"
        assert hello["data"]["database_backend"] == "SQLite"
        assert "classification" in hello["data"]["telemetry"]

        created = client.post("/api/hazards", json={"hazard_type": "SPEED_BREAKER", "latitude": 21.60,
                                                    "longitude": 79.60, "source": "TEST"}).json()
        for _ in range(20):
            message = ws.receive_json()
            if message["type"] == "hazard":
                assert message["event"] == "created"
                assert message["data"]["id"] == created["hazard_id"]
                break
        else:
            pytest.fail("hazard event was not pushed over the WebSocket")

        ws.send_text("ping")
        for _ in range(20):
            if ws.receive_json()["type"] == "pong":
                break
        else:
            pytest.fail("no pong")
    client.post("/api/test/reset")


def test_info_and_trips(client):
    info = client.get("/api/info").json()
    assert info["deployment"]["platform"] == "pytest"
    assert info["database_backend"] == "SQLite"
    assert info["ml"]["model_configured"] is True

    trip = client.post("/api/trips", json={"device_id": "RoadGuard-ESP32", "distance_meters": 1200.0}).json()
    trips = client.get("/api/trips").json()
    assert trip["trip_id"] in [t["id"] for t in trips]
