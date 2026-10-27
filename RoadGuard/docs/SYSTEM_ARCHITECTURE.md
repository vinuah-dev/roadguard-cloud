# RoadGuard System Architecture & Engineering Design

**Department of Computer Science & Engineering | IDEA Lab**  
**Ramdeobaba University, Academic Year 2026–27**  
**Project Guide:** Dr. Suresh Balpande  
**Team:** Bhoomi Magarde (A6-19), Mohammad Ejaz Mehendi (A6-25), Nidhish Dadhe (A6-53)

---

## 1. High-Level Architecture Overview

RoadGuard employs a four-tier hybrid edge-cloud architecture designed for high availability, low alert latency, and offline resilience:

```mermaid
graph TD
    subgraph Vehicle["Vehicle Hardware Tier"]
        IMU["MPU-6050 6-Axis IMU (50Hz)"] --> ESP["ESP32 Microcontroller"]
        ESP --> BLE_TX["BLE 5.0 Radio (Nordic UART)"]
    end

    subgraph Phone["Mobile Edge Tier (Android Client)"]
        BLE_TX -.-> BLE_RX["BleManager (Kotlin Coroutines)"]
        BLE_RX --> RING["Ring Buffer (50 Samples / 1.0s Window)"]
        RING --> ML["RoadGuardClassifier (Edge ML Engine)"]
        GPS["Fused Location Provider (GPS)"] --> PROX["Proximity Alert Engine"]
        ML --> LOCAL_DB["Room Database (Local Offline Cache)"]
        PROX --> ALERT["TTS Voice & Haptic Warning (50-200m ahead)"]
        LOCAL_DB --> UI["Jetpack Compose UI (Dashboard HUD / Map / Trips)"]
    end

    subgraph Cloud["Supabase Cloud Tier"]
        LOCAL_DB -->|Encrypted HTTPS / REST| EDGE_FN["Supabase Edge Functions"]
        EDGE_FN --> PG["PostgreSQL 15 + PostGIS"]
        PG --> SPATIAL["Spatial Clustering & RLS Policies"]
    end
```

---

## 2. Sequence Diagram: Real-Time Detection & Proximity Alerting

```mermaid
sequenceDiagram
    autonumber
    actor Driver
    participant ESP as ESP32 + MPU6050
    participant Android as Android Service
    participant Classifier as RoadGuardClassifier
    participant DB as Room DB (Local)
    participant Cloud as Supabase PostGIS

    Driver->>Android: Start Trip
    Android->>ESP: Connect BLE GATT
    loop Every 20ms (50Hz)
        ESP->>Android: Stream Packet (ax, ay, az, gx, gy, gz)
        Android->>Classifier: Sliding Window (1.0 sec)
    end

    Note over Classifier: Peak-to-Peak Z & Jerk Evaluation
    Classifier-->>Android: Pothole Detected (Conf: 94%, Sev: HIGH)
    Android->>DB: Save Observation (Lat, Lng, Severity)
    Android->>Cloud: Asynchronous Sync (ingest-hazard)

    Note over Android: Driver approaches previously registered hazard
    Android->>DB: Query Hazards within Radius (100m)
    DB-->>Android: Hazard Detected Ahead (Distance: 85m)
    Android->>Driver: Audible Voice Alert + Vibration + HUD Flash
```

---

## 3. Edge Computing vs. Cloud Partitioning

| Subsystem Task | Execution Tier | Latency | Rationale |
| :--- | :--- | :--- | :--- |
| **Sensor Sampling (50Hz)** | ESP32 Embedded | < 1 ms | Hardware I2C timer interrupt avoids OS scheduling jitter. |
| **Sliding Window Feature Extraction** | Android Edge | 8 ms | Zero network dependency; immediate classification. |
| **Proximity Collision Warning** | Android Edge | < 15 ms | Drivers require immediate audio cues before road impact. |
| **Spatial Clustering & Deduplication**| Supabase Cloud | 200–500 ms | Combines crowdsourced reports from thousands of vehicles using PostGIS `ST_DWithin` & DBSCAN. |
| **Municipal Roughness Heatmaps** | Supabase Cloud | Periodic / Batch | High compute requirements for city-wide IRI analysis. |
