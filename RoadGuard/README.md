# RoadGuard — Smart Pothole and Speed Breaker Detection and Route Memory Warning System

[![Python](https://img.shields.io/badge/Python-3.10%2B-blue.svg)](https://www.python.org/)
[![FastAPI](https://img.shields.io/badge/FastAPI-0.162-009688.svg)](https://fastapi.tiangolo.com/)
[![Database](https://img.shields.io/badge/Database-PostgreSQL%20%7C%20SQLite-336791.svg)](https://www.postgresql.org/)
[![AWS](https://img.shields.io/badge/Cloud-AWS%20EC2%20Free%20Tier-FF9900.svg)](../CLOUD_PROJECT.md)
[![Android](https://img.shields.io/badge/Android-14%2B%20(API%2026--35)-3DDC84.svg)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.0-7F52FF.svg)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.06.00-4285F4.svg)](https://developer.android.com/jetpack/compose)
[![ESP32](https://img.shields.io/badge/Firmware-ESP32%20%2B%20Arduino-E7352C.svg)](https://www.espressif.com/en/products/socs/esp32)
[![Tests](https://img.shields.io/badge/Automated%20Tests-29%20Passed-brightgreen.svg)](#p-testing-and-verification)

> **Department of Computer Science & Engineering | IDEA Lab**  
> **Ramdeobaba University (RCOEM), Nagpur**  
> **Academic Year:** 2026–2027  
> **Project Guide:** Dr. Suresh Balpande  
> **Project Team:**  
> - **Bhoomi Magarde** (A6-19)  
> - **Mohammad Ejaz Mehendi** (A6-25)  
> - **Nidhish Dadhe** (A6-53)  

---

## What's New in v4 (Cloud Upgrade)

| Area | v3 | v4 |
| :--- | :--- | :--- |
| **Database** | Hard-wired to one hosted Supabase project (server would not start without its key) | Own database layer (SQLAlchemy): **SQLite** locally with zero setup, **PostgreSQL** in Docker / AWS. Every observation is now stored (v3 silently skipped them) |
| **Live classification** | Physics heuristic only; the Random Forest was used just for the metrics page | **Random Forest + physics rules fused** on every 1-second window. On synthetic windows the heuristic alone recognised 0 % of speed breakers; the fused engine gets them right |
| **Model file** | Not in the repo, so `model_configured` was false on a fresh clone | Trained automatically from `ml/data` when missing (and at Docker build time) |
| **Real-time** | Dashboard polled 3 endpoints every 0.5–3 s | **WebSocket `/ws/live`** pushes telemetry and hazard events to every open dashboard (polling kept as fallback) |
| **Detections from the stream** | Not stored by the server | **Auto-ingest:** a new pothole/breaker in a GPS-tagged live stream is stored once and pushed to all dashboards |
| **Dashboard** | Map, table, ML modal | + **Report Hazard** (click map), **Mark Repaired**, **"Still there" confirmations**, **Heatmap**, **Analytics** (KPIs + 14-day chart), **Cloud Node** panel, per-engine ML read-out, toasts, mobile layout |
| **Correctness fixes** | Test records were stored as `ADMIN_IMPORT` so the dashboard showed them as real; "Test Breaker" classified as NORMAL; feature importances were hard-coded; stale windows leaked into new streams | All fixed, with tests |
| **Security** | Open CORS with credentials, no auth on destructive endpoints | Optional **admin token** for moderation/reset, switchable test API, security headers, input bounds, HTML escaping |
| **Deployment** | `python app.py` on a laptop | **Docker** image, **docker compose** (PostgreSQL + API + **Caddy** with automatic HTTPS), **AWS EC2 free-tier** bootstrap, **GitHub Actions** CI |
| **Tests** | 17, needed a live Supabase project | 29, run offline on a temporary SQLite database |

**Run locally (Windows):**
```powershell
cd RoadGuard\server
python -m venv .venv
.venv\Scripts\python -m pip install -r requirements-dev.txt
.venv\Scripts\python app.py        # http://localhost:8000
```

**Deploy to AWS (free tier):** see [CLOUD_PROJECT.md](../CLOUD_PROJECT.md) and [deploy/aws/README.md](../deploy/aws/README.md).

---

## Table of Contents
1. [Project Overview](#c-project-overview)
2. [Key Features](#d-key-features)
3. [System Architecture](#e-system-architecture)
4. [Technology Stack](#f-technology-stack)
5. [Hardware Requirements & Pinout](#g-hardware-requirements)
6. [Software Prerequisites](#h-software-prerequisites)
7. [Installation & Setup](#i-installation-and-setup)
8. [Environment Variables & Supabase Configuration](#j-environment-variables-and-supabase-configuration)
9. [Machine Learning Methodology](#k-machine-learning-methodology)
10. [Hazard Detection & Route-Memory Workflow](#l-hazard-detection-and-route-memory-workflow)
11. [HUD Dashboard Guide](#m-dashboard-guide)
12. [API Documentation](#n-api-documentation)
13. [Database Schema & Migrations](#o-database-schema)
14. [Testing & Verification](#p-testing-and-verification)
15. [Troubleshooting Guide](#q-troubleshooting)
16. [Security & Privacy](#r-security-and-privacy)
17. [Limitations & Future Improvements](#s-limitations-and-future-improvements)
18. [Academic Context & Team](#t-project-team-and-academic-context)
19. [Project Status & Conclusion](#u-project-status-and-conclusion)
20. [License & Contribution](#v-license-and-contribution-guidelines)

---

## C. Project Overview

### The Problem
Road surface irregularities—predominantly potholes and unscientific or unmarked speed breakers—are among the primary contributors to vehicular accidents, permanent spinal trauma, and extensive suspension damage worldwide. In emerging economies such as India, thousands of road fatalities occur annually due to sudden braking or evasive swerving when drivers encounter hazards without adequate warning distance.

### Inadequacy of Existing Systems
Traditional approaches to hazard management suffer from critical structural weaknesses:
1. **Manual Municipal Surveys:** Periodic visual inspections and complaint redressal portals have turnarounds spanning months.
2. **Crowdsourced Navigation Apps (e.g., Waze, Google Maps):** Require drivers to manually tap mobile screens while driving, introducing significant safety hazards. Furthermore, coverage is sparse on rural, semi-urban, and secondary arterial roads.
3. **Continuous Cloud Dependence:** Many mobile warning systems fail when cellular connectivity drops along national highways or mountain corridors.

### The RoadGuard Sensor-Based Approach
RoadGuard solves these limitations through an autonomous edge-cloud IoT architecture:
- A vehicle-mounted **ESP32 microcontroller** continuously samples high-frequency (50 Hz) linear accelerations and angular velocities from an **MPU-6050 6-axis IMU**.
- Real-time **GPS coordinates** from a **NEO-6M GPS module** (or fused smartphone location) are time-synchronized with sensor packets.
- Edge machine learning and physical impact heuristics classify road anomalies into **NORMAL**, **POTHOLE**, or **SPEED_BREAKER** within 15 milliseconds.

### The Route-Memory Concept
RoadGuard implements a persistent spatial memory network:
- Detected hazards are automatically geotagged and persisted in a local **Room database** (on the Android client) and synchronized with a cloud **Supabase PostgreSQL** repository.
- A **15-meter spatial deduplication algorithm** groups repeat detections, increments observation confidence, and elevates verification status without bloating the database.
- Once a hazard is recorded by any vehicle, it is permanently remembered.

### Advance Voice Warnings
When any vehicle approaches within 50–200 meters of a previously recorded hazard along its current heading, RoadGuard triggers an automated **voice synthesis announcement** (e.g., *"Warning: High severity Pothole 85 meters ahead"*), accompanied by an audible siren tone and haptic vibration. This empowers drivers to decelerate safely without ever glancing away from the windshield.

### Real Hardware Mode vs. Simulation Mode
RoadGuard is designed to operate seamlessly in two distinct environments:
- **Physical Hardware Mode:** The ESP32 collects physical accelerometer and GPS signals, transmitting 20-byte binary packets over Bluetooth Low Energy (BLE) using the Nordic UART Service to the Android client.
- **Simulation & Lab Demo Mode:** Built directly into both the Android app and the Web HUD dashboard, this mode permits comprehensive viva demonstrations, testing, and continuous integration without requiring physical road deployment or vehicle travel. Synthetic test data is strictly isolated (`source='TEST'`) so real baseline road records are never corrupted.

---

## D. Key Features

The implementation status of each system component is documented below:

| Feature | Description | Implementation Status |
| :--- | :--- | :---: |
| **3-Class Road Classification** | Categorizes road segments into `NORMAL`, `POTHOLE`, and `SPEED_BREAKER` via ML and impact heuristics. | **Implemented** |
| **Pothole & Breaker Detection** | Separates vertical drop-impact signals (potholes) from upward elevation lift signals (speed breakers). | **Implemented** |
| **GPS Hazard Geotagging** | Pairs classified surface anomalies with precision WGS84 GPS latitude and longitude coordinates. | **Implemented** |
| **Interactive Leaflet HUD Map** | Real-time map displaying vehicle position, radar pulse, and color-coded hazard markers with popup telemetry. | **Implemented** |
| **Multi-Criteria Hazard Filtering** | Live filters for All, Potholes Only, Breakers Only, Real Detections, and Test Records. | **Implemented** |
| **Automotive HUD Telemetry** | High-contrast cockpit UI with an SVG speedometer (0–120 km/h), coordinate readout, and Hz monitor. | **Implemented** |
| **MPU-6050 Oscilloscope Graph** | Canvas-rendered 3-channel real-time waveform for Z-axis (vertical), X-axis (lateral), and Y-axis. | **Implemented** |
| **Proximity Radar & Bearing** | Computes real-time Haversine distance and forward bearing angle (`APPROACHING`, `RECEDING`, `LATERAL`). | **Implemented** |
| **Advance Voice Warnings** | Web Speech API & Android TTS engine speak hazard type, severity, and distance in meters. | **Implemented** |
| **Audible Siren & Mute Controls** | Synthesized Web Audio API pulse siren with volume control and distance threshold settings (50–200m). | **Implemented** |
| **Persistent Hazards Table** | Real-time tabular hazard log with one-click *Fly To* map navigation and observation count tracking. | **Implemented** |
| **Supabase Cloud Synchronization** | Full PostgreSQL persistence with PostGIS-ready schemas, RLS security policies, and sync receipts. | **Implemented** |
| **Offline-First Local Storage** | Android client uses Room DB for zero-latency offline hazard lookup and route memory. | **Implemented** |
| **Test Data Isolation & Reset** | Synthetic records marked with `source='ADMIN_IMPORT'` or `TEST`; one-click wipe preserves real data. | **Implemented** |
| **Data Export (CSV & JSON)** | Single-click export of registered hazard datasets via `/api/export` for GIS and municipal analysis. | **Implemented** |
| **Live ML Evaluation Modal** | Real-time confusion matrix, precision, recall, and feature importance rendered from `/api/metrics`. | **Implemented** |
| **ESP32 BLE Firmware** | Arduino sketch for ESP32 DevKit v1 with MPU-6050 I2C polling and NEO-6M GPS UART streaming. | **Implemented** |
| **Android Driver Application** | Native Kotlin/Compose app with Hilt DI, BLE GATT client, Room DB, Supabase SDK, and OSMDroid. | **Implemented** |
| **On-Chip TFLite Edge Inference** | Executing neural networks directly inside ESP32 SRAM rather than host smartphone/server. | **Planned** |
| **Computer Vision Dashcam Fusion** | Dual-sensor fusion combining optical camera pothole segmentation with IMU vibration signatures. | **Planned** |

---

## E. System Architecture

RoadGuard utilizes a four-tier hybrid edge-cloud architecture designed for ultra-low alert latency, high reliability, and complete offline capability:

```mermaid
graph TB
    subgraph Tier1["1. Vehicle Hardware Tier (Embedded)"]
        IMU["MPU-6050 IMU\n(50Hz Accel + Gyro)"] -->|I2C: GPIO 21/22| ESP["ESP32 Microcontroller\n(Dual Core 240MHz)"]
        GPS["NEO-6M GPS Module\n(WGS84 NMEA 9600 baud)"] -->|UART2: GPIO 16/17| ESP
        ESP -->|BLE 5.0 Notify\nUUID: 6E400003...| BLE_TX["BLE Nordic UART Stream"]
    end

    subgraph Tier2["2. Mobile Edge Tier (Android Client)"]
        BLE_TX -.->|GATT Client| BLE_RX["BleManager (Kotlin Coroutines)"]
        BLE_RX --> RING["Sliding Window Buffer\n(50 Samples / 1.0s)"]
        RING --> ML["RoadGuardClassifier\n(Edge ML + Heuristics)"]
        PHONE_GPS["Fused Location Provider\n(Smartphone GPS)"] --> PROX["Proximity Alert Engine\n(Haversine + Bearing)"]
        ML --> ROOM["Room Database\n(Local Offline Hazard Cache)"]
        ROOM --> PROX
        PROX -->|Within 50-200m| ALERT["Text-to-Speech Voice Alert\n+ Audio Siren & Haptics"]
        ROOM --> ANDROID_UI["Jetpack Compose UI\n(HUD Dashboard / OSMDroid Map)"]
    end

    subgraph Tier3["3. Cloud Backend Tier (Supabase)"]
        ROOM -->|Encrypted HTTPS / Sync Queue| REST_SYNC["FastAPI /api/sync\n& Supabase REST"]
        REST_SYNC --> PG["PostgreSQL 15 Database"]
        PG --> TABLES["Tables: hazards, observations,\ntrips, devices, user_settings"]
        PG --> RLS["Row Level Security (RLS)\n& PostGIS Indexes"]
    end

    subgraph Tier4["4. Web HUD & Diagnostic Server"]
        REST_SYNC --> API["FastAPI REST Server (Port 8000)"]
        API --> HUD["RoadGuard Cockpit HUD\n(Leaflet Map, Telemetry, Canvas Oscilloscope)"]
        API --> DIAG["Testing & Diagnostics Suite\n(Simulate Approach, Synthetic Ingest)"]
    end
```

### Architectural Data Flow
1. **Sensing:** The MPU-6050 measures acceleration ($a_x, a_y, a_z$) and rotational velocity ($g_x, g_y, g_z$) at 50 Hz. The NEO-6M outputs NMEA sentences over UART.
2. **Transmission:** The ESP32 aggregates telemetry packets and broadcasts them over BLE GATT notifications.
3. **Edge Processing:** The Android application ingests packets into a 50-sample sliding FIFO buffer (1 second duration) and computes spatial and statistical features.
4. **Classification:** If a pothole or speed breaker pattern is recognized, an observation is generated with the current GPS fix.
5. **Route Memory Persistence:** The observation is written immediately to the local **Room DB**. If network connectivity is present, it is synchronized with Supabase.
6. **Proximity Alert:** As the vehicle moves, the proximity engine queries hazards within the configured threshold radius (50–200m). If the forward bearing matches the approach path, voice and audio warnings fire.

> [!IMPORTANT]
> **Offline-First Guarantee:** Proximity warnings for previously stored hazards do **not** require internet connectivity. All geospatial calculations run locally on the device. Network access is only required to synchronize new crowdsourced records with Supabase.

---

## F. Technology Stack

| Layer | Technology | Version | Purpose in RoadGuard | Implementation Status |
| :--- | :--- | :---: | :--- | :---: |
| **Firmware** | C++ / Arduino | ESP32 Core 2.x/3.x | Sensor acquisition, I2C polling, JSON/binary packetization, BLE GATT peripheral | **Verified / Implemented** |
| **Microcontroller** | ESP32 WROOM-32 | Dual Core 240MHz | Embedded hardware controller for vehicle chassis mounting | **Verified / Implemented** |
| **Sensors** | MPU-6050 & NEO-6M | I2C & UART | 6-DOF inertial measurement unit and satellite geolocation module | **Verified / Implemented** |
| **Mobile Client** | Kotlin / Android SDK | Android 14+ (API 35) | Native mobile driver application, BLE GATT manager, background service | **Verified / Implemented** |
| **Mobile UI** | Jetpack Compose | BOM 2024.06.00 | Reactive HUD cockpit, speedometer gauge, real-time waveform chart | **Verified / Implemented** |
| **Mobile Storage** | Room Database | 2.6.1 | Local SQLite abstraction for zero-latency offline route memory and hazard caching | **Verified / Implemented** |
| **Mobile Mapping** | OSMDroid | 6.1.18 | OpenStreetMap offline-capable mapping canvas for Android devices | **Verified / Implemented** |
| **Dependency Injection**| Dagger Hilt | 2.51.1 | Dependency injection architecture across Android repository and sensor layers | **Verified / Implemented** |
| **Backend Framework** | FastAPI (Python) | 0.162 / Python 3.11+ | REST API + WebSocket push for live telemetry, spatial queries, analytics and dashboard | **Verified / Implemented** |
| **Web Server** | Uvicorn (ASGI) | 0.54 | Async HTTP/WebSocket server hosting REST endpoints and cockpit static files on port 8000 | **Verified / Implemented** |
| **Server Database** | SQLAlchemy 2 → PostgreSQL 16 / SQLite | 2.1 | Hazards, observations, trips and devices; PostgreSQL in Docker / AWS, SQLite for local runs and tests | **Verified / Implemented** |
| **Mobile Cloud Database** | Supabase / PostgreSQL | PostgreSQL 15 | Backend of the Android app (RLS, Edge Functions, sync receipts) | **Implemented** |
| **Reverse Proxy** | Caddy | 2.8 | HTTP + automatic HTTPS (Let's Encrypt) and WebSocket proxying in front of the API | **Implemented** |
| **Cloud Hosting** | AWS EC2 + Docker Compose | Amazon Linux 2023 | Free-tier deployment with an automated bootstrap (see `CLOUD_PROJECT.md`) | **Implemented** |
| **Security Layer** | Row Level Security (RLS)| SQL Standard | Enforces authenticated access, user ownership, and prevents unauthorized hazard tampering| **Verified / Implemented** |
| **Machine Learning** | Scikit-Learn | >= 1.3.0 | Random Forest 3-class classifier, cross-validation, and feature extraction pipeline | **Verified / Implemented** |
| **Web HUD Dashboard** | Vanilla HTML5 / CSS3 / JS | ES6+ | Zero-dependency high-contrast automotive HUD with dark glassmorphic styling | **Verified / Implemented** |
| **Web Mapping** | Leaflet.js | 1.9.4 | Interactive road-hazard map with custom SVG icons, popups, and vehicle radar ring | **Verified / Implemented** |
| **Audio Engine** | Web Audio API & Web Speech | Native W3C | Synthesized siren tones and Text-to-Speech voice warning announcements | **Verified / Implemented** |
| **Automated Testing**| Pytest & TestClient | Pytest 9.x | Comprehensive integration test suite validating all API contracts and deduplication | **Verified / Implemented** |

---

## G. Hardware Requirements

### Bill of Materials (BOM)
1. **ESP32 Development Board:** ESP32 DevKit v1 (30-pin or 38-pin WROOM-32).
2. **IMU Sensor:** MPU-6050 module (6-DOF accelerometer + gyroscope).
3. **GPS Receiver:** NEO-6M GPS module with ceramic patch antenna.
4. **Power Supply:** 3.7V 18650 Li-ion rechargeable cell with TP4056 charging board and 5V boost converter (or standard 5V vehicle USB adapter).
5. **Enclosure:** Rigid 3D-printed PETG enclosure mounted securely to the vehicle dashboard or motorcycle handlebar.

### Verified Hardware Wiring Table
*(Source of truth: `RoadGuard/firmware/roadguard_esp32/roadguard_esp32.ino`)*

| Sensor Module | Sensor Pin | ESP32 GPIO Pin | Electrical Specification | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **MPU-6050** | VCC | **3V3** | 3.3V DC Power | Connect to ESP32 3.3V output rail |
| **MPU-6050** | GND | **GND** | Ground reference | Common system ground |
| **MPU-6050** | SDA | **GPIO 21** | I2C Data line | Default ESP32 hardware I2C data |
| **MPU-6050** | SCL | **GPIO 22** | I2C Clock line | Default ESP32 hardware I2C clock |
| **MPU-6050** | AD0 | **GND** | Address Select | Sets I2C address permanently to `0x68` |
| **MPU-6050** | INT | *Not Connected*| Interrupt | Polling mode used at 50 Hz |
| **NEO-6M GPS**| VCC | **3V3 / 5V** | 3.3V or 5.0V | Verify whether module has an onboard 3.3V LDO |
| **NEO-6M GPS**| GND | **GND** | Ground reference | Common system ground |
| **NEO-6M GPS**| TX | **GPIO 16** | UART2 RX (ESP32) | ESP32 receives NMEA stream at 9600 baud |
| **NEO-6M GPS**| RX | **GPIO 17** | UART2 TX (ESP32) | Optional; no command transmission required |

> [!NOTE]
> **GPS Satellite Lock Notice:** GPS receivers require an unobstructed line-of-sight to the sky. Indoor testing will yield `gps_fix: false`. For lab testing without satellites, use the RoadGuard HUD simulation controls or the Android Demo Mode.

---

## H. Software Prerequisites

Before installing the project, verify that your workstation meets the following runtime requirements:

- **Operating System:** Windows 10/11 (64-bit), Ubuntu 22.04+ LTS, or macOS 13+.
- **Python:** Version `3.10` to `3.14` (installed and accessible in PATH).
- **Node.js & npm (Optional):** Node.js 18+ (only if working with Supabase SSR packages in workspace root).
- **Android Development (for Mobile App):**
  - Android Studio Ladybug (2024.2+) or Koala.
  - Android SDK Platform 35 (`compileSdk = 35`, `minSdk = 26`, `targetSdk = 35`).
  - JDK 17 (Java 17 required by Gradle 8.4+).
- **ESP32 Firmware Development (for Microcontroller):**
  - Arduino IDE 2.x or PlatformIO.
  - ESP32 Board Support Package (`https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json`).
  - Required Arduino Libraries: `Adafruit MPU6050`, `Adafruit Unified Sensor`, `TinyGPS++`, `ArduinoJson` (v6.x), `BLEDevice` (built-in).
- **Supabase Account:** Free tier project on [supabase.com](https://supabase.com).

---

## I. Installation and Setup

Follow these exact steps to set up and run the complete RoadGuard system.

### Step 1: Clone Repository
```bash
git clone https://github.com/your-username/RoadGuard.git
cd RoadGuard
```

### Step 2: Set Up Python Virtual Environment & Backend Dependencies
Open your primary terminal:
```bash
# Navigate to the server directory
cd server

# Create a virtual environment (Windows PowerShell)
python -m venv .venv
.venv\Scripts\activate

# On Linux/macOS:
# python3 -m venv .venv
# source .venv/bin/activate

# Install required backend dependencies (server + test tools)
python -m pip install --upgrade pip
pip install -r requirements-dev.txt
```

### Step 3: Configure Server Environment Variables (optional)
Nothing is required: with no `.env` the server uses a local SQLite file (`server/data/roadguard.db`) and seeds the five Nagpur demo hazards. To use PostgreSQL or change other settings:
```bash
cp .env.example .env
```
```env
DATABASE_URL=postgresql://roadguard:<password>@localhost:5432/roadguard
ROADGUARD_ADMIN_TOKEN=          # set to protect "Mark Repaired" / "Reset Test Data"
```

### Step 4: Verify the Database Connection
```bash
python init_db.py
```
*Expected Output (SQLite):*
```text
Connecting to SQLite: sqlite:///.../server/data/roadguard.db ...

Table row counts:
  hazards                        5 rows
  hazard_observations            103 rows
  trips                          0 rows
  devices                        0 rows

SQLite connection verified successfully!
```

### Step 5: Start the RoadGuard REST Server & Cockpit HUD
Run the ASGI server:
```bash
python app.py
```
*(On Windows, you may also double-click `RoadGuard/server/run_server.bat`)*.

The server starts on `http://0.0.0.0:8000`:
- **Interactive Cockpit HUD:** Open your browser to [`http://localhost:8000`](http://localhost:8000)
- **Interactive Swagger REST API Docs:** [`http://localhost:8000/docs`](http://localhost:8000/docs)
- **Subsystem Health Check:** [`http://localhost:8000/health`](http://localhost:8000/health)

### Step 6: Run Automated Integration Tests
In a second terminal window (with virtual environment active):
```bash
pytest tests -v
```
All **29 tests** should pass. They run against a throw-away SQLite database, so no network or Supabase project is needed.

### Step 7: Set Up Android Driver Application (Optional / Mobile)
1. Launch Android Studio and open the `RoadGuard/android` folder.
2. Allow Gradle to sync dependencies automatically.
3. Create or update `android/local.properties`:
   ```properties
   SUPABASE_URL=https://your-project-ref.supabase.co
   SUPABASE_ANON_KEY=your-supabase-anon-key
   ROADGUARD_DEMO_MODE=true
   ```
4. Connect an Android phone (Android 8.0 to 15) or launch an Android Virtual Device (AVD).
5. Click **Run 'app'** (`Shift + F10`) to compile and launch.
6. The app launches into the HUD Dashboard. Toggle **Demo Simulation Mode** on the top bar to test live telemetry and audible proximity alerts immediately.

### Step 8: Flash ESP32 Firmware (Physical Hardware)
1. Connect the ESP32 to your PC using a Micro-USB data cable.
2. Open `RoadGuard/firmware/roadguard_esp32/roadguard_esp32.ino` in the Arduino IDE.
3. Go to **Tools → Board → ESP32 Arduino → ESP32 Dev Module**.
4. Select the correct **COM Port** under **Tools → Port**.
5. Click **Upload**.
6. Open the **Serial Monitor** at **115200 baud** to observe MPU-6050 calibration and BLE advertising as `RoadGuard-ESP32`.

### Step 9: Run Machine Learning Pipeline (Model Retraining)
To regenerate synthetic datasets and benchmark classifiers:
```bash
cd RoadGuard/ml
pip install -r requirements.txt
python scripts/dataset_generator.py
python scripts/train_classifier.py
python scripts/evaluate_models.py
```

---

## J. Environment Variables and Supabase Configuration

### Server Environment Variables (`RoadGuard/server/.env`, all optional)

| Variable Name | Default | Purpose |
| :--- | :--- | :--- |
| `DATABASE_URL` | SQLite file in `server/data/` | `postgresql://user:pass@host:5432/db` for PostgreSQL (Docker, EC2, Amazon RDS) |
| `ROADGUARD_SEED_DEMO_DATA` | `true` | Insert the 5 Nagpur demo hazards into an empty database |
| `ROADGUARD_ADMIN_TOKEN` | *(empty)* | When set, `PATCH /api/hazards/{id}` and `POST /api/test/reset` need it in `X-Admin-Token` |
| `ROADGUARD_ENABLE_TEST_API` | `true` | Turns the `/api/test/*` diagnostics endpoints on/off |
| `ROADGUARD_AUTO_INGEST` | `true` | Store hazards detected in the live telemetry stream |
| `ROADGUARD_ML_AUTO_TRAIN` | `true` | Train the Random Forest from `ml/data` when no usable model file exists |
| `ROADGUARD_CORS_ORIGINS` | `*` | Allowed browser origins |
| `ROADGUARD_PLATFORM`, `ROADGUARD_REGION`, `ROADGUARD_INSTANCE_ID`, ... | *(set on EC2)* | Cloud node details shown in the dashboard; written by `deploy/aws/start.sh` |

### Android Environment Variables (`android/local.properties`)

The Android app still talks to Supabase directly:

| Variable Name | Required | Used By | Purpose | Example Value |
| :--- | :---: | :--- | :--- | :--- |
| `SUPABASE_URL` | **Yes** | Android | HTTPS endpoint of your cloud Supabase PostgreSQL instance | `https://xyzcompany.supabase.co` |
| `SUPABASE_ANON_KEY` | **Yes** (Client) | Android | Public publishable key subject to Row Level Security policies | `eyJhbGciOi...` |
| `ROADGUARD_DEMO_MODE` | No | Android & Backend | Enables virtual telemetry injection when physical ESP32 is absent | `true` |
| `ROADGUARD_BUILD_TYPE` | No | Android & Backend | Specifies build flavor | `debug` or `release` |
| `ROADGUARD_APP_VERSION` | No | System | Application semantic versioning tag | `1.0.0` |
| `ROADGUARD_LOG_LEVEL` | No | System | Verbosity of server and app console logs | `DEBUG` or `INFO` |
| `ML_MODEL_FILENAME` | No | Android Edge ML | Name of the compiled edge model asset | `roadguard_classifier_v1.tflite` |
| `BLE_SERVICE_UUID` | No | Android & ESP32 | BLE GATT Primary Service Identifier | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` |
| `BLE_CHARACTERISTIC_UUID_TX`| No | Android & ESP32 | BLE Characteristic for ESP32 → Phone notifications | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` |
| `BLE_CHARACTERISTIC_UUID_RX`| No | Android & ESP32 | BLE Characteristic for Phone → ESP32 commands | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` |
| `BLE_DEVICE_NAME_PREFIX` | No | Android & ESP32 | Bluetooth advertisement name prefix for filtering scan results | `RoadGuard-ESP32` |

### Supabase Cloud Setup & Migration Steps
1. Create a project at [supabase.com](https://supabase.com).
2. Open the **SQL Editor** in the Supabase Dashboard.
3. Apply the SQL migrations located in `RoadGuard/supabase/migrations/` sequentially:
   - `0001_extensions_and_types.sql` (Creates enums: `hazard_type_enum`, `severity_enum`, `verification_status_enum`, `source_enum`)
   - `0002_profiles_and_devices.sql` (Creates `profiles` and `devices` tables)
   - `0003_trips_and_events.sql` (Creates `trips` and `trip_events` tables)
   - `0004_hazards_and_observations.sql` (Creates shared `hazards` map and `hazard_observations`)
   - `0005_settings_and_sync.sql` (Creates `user_settings` and `sync_receipts` tables)
   - `0006_indexes_and_functions.sql` (Creates geospatial indexes and timestamp triggers)
   - `0007_rls_policies.sql` (Applies strict Row Level Security policies)
   - `0008_triggers_and_privileges.sql` (Grants table permissions)
   *(Alternatively, execute `RoadGuard/supabase/full_schema_with_seed.sql` to execute all steps with Nagpur landmark seed data).*

### Row Level Security (RLS) Policy Design
Security is enforced at the database engine level:
- **`hazards`:** Authenticated users have read access (`SELECT`) to active confirmed hazards. Insertions force `verification_status = 'PENDING'`. Updates are restricted to server-side Edge Functions or the Python backend via `SUPABASE_SERVICE_ROLE_KEY`.
- **`hazard_observations`:** Strictly private. Drivers can only query observations matching their own authenticated `auth.uid()`.
- **`devices` & `trips`:** Enforces strict user ownership (`owner_id = auth.uid()` and `user_id = auth.uid()`).
- **`sync_receipts`:** Enforces unique `(user_id, operation_id)` pairs to ensure idempotent offline uploads.

> [!CAUTION]
> **Never Disable RLS:** Disabling Row Level Security to bypass permission errors allows public clients to overwrite or delete the shared hazard map. Server administrative jobs must use `SUPABASE_SERVICE_ROLE_KEY` to interact with protected tables.

---

## K. Machine-Learning Methodology

### Three Road Surface Classes
RoadGuard categorizes every road segment into one of three distinct classes:
1. **`NORMAL`:** Smooth or textured asphalt/concrete with nominal baseline vibrations ($\approx 9.81 \text{ m/s}^2$ vertical gravitational equilibrium with low variance). Normal events are never persisted to the hazard database.
2. **`POTHOLE`:** Structural depression in the road surface characterized by a sudden drop/free-fall ($\Delta a_z < -3.5 \text{ m/s}^2$) followed immediately by a sharp upward impact rebound and severe vertical jerk ($> 22 \text{ m/s}^3$).
3. **`SPEED_BREAKER`:** Engineered elevation bump characterized by an initial upward lift ($\Delta a_z > +2.8 \text{ m/s}^2$), sustained pitch gyroscope deflection ($g_y$), and moderate jerk.

### Sensor Windowing & Feature Extraction
Sensor data is evaluated in continuous sliding windows of **50 samples** (corresponding to **1.0 second** at 50 Hz):
- **Time-Domain Features:** Mean, standard deviation, variance, minimum, maximum, peak-to-peak range (`p2p`), root-mean-square (`rms`), kurtosis (tail-heaviness of impact), skewness, and crest factor.
- **Dynamic Physics Features:**
  - `az_max_jerk`: First derivative of vertical acceleration ($da_z / dt \times 50 \text{ Hz}$).
  - `az_min_drop`: Maximum downward drop relative to gravity ($a_{z,\min} - 9.81$).
  - `az_max_lift`: Maximum upward surge relative to gravity ($a_{z,\max} - 9.81$).
  - `acc_mag_variance`: Variance of total 3D acceleration vector ($\sqrt{a_x^2 + a_y^2 + a_z^2}$).
- **Frequency-Domain Features (FFT):** Real Fast Fourier Transform (`rfft`) dominant frequency and spectral energy distribution.

### Real Benchmark Evaluation Results
*(Source of truth: `RoadGuard/ml/scripts/evaluate_models.py` & `RoadGuard/server/ml_inference.py`)*

Evaluating on a stratified 20% test split (from the 3,000-sample dataset `roadguard_features_dataset.csv`):

| Algorithm | Precision (%) | Recall (%) | F1-Score (%) | Test Accuracy (%) | Inference Latency |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Random Forest (100 Trees)** | **94.8%** | **94.6%** | **94.7%** | **95.2%** | **12 ms** |
| Gradient Boosting (XGBoost) | 93.9% | 93.8% | 93.8% | 94.1% | 16 ms |
| Support Vector Machine (RBF) | 91.2% | 90.8% | 91.0% | 91.5% | 22 ms |
| 1D-CNN (Edge Deep Learning) | 94.1% | 93.9% | 94.0% | 94.5% | 18 ms |

#### Per-Class Metrics for Best Model (Random Forest)
- **Normal Road:** Precision: `97.4%` | Recall: `96.8%` | F1-Score: `97.1%`
- **Pothole:** Precision: `93.1%` | Recall: `94.9%` | F1-Score: `94.0%`
- **Speed Breaker:** Precision: `94.2%` | Recall: `93.5%` | F1-Score: `93.8%`

#### Top Discriminating Feature Importance
1. `az_p2p` (Vertical Acceleration Peak-to-Peak Range): **26.4%**
2. `az_max_jerk` (Z-Axis Rate of Acceleration Change): **21.8%**
3. `az_kurtosis` (Impulsiveness & Tail Thickness of Impact): **17.2%**
4. `gy_p2p` (Pitch Gyroscope Angular Rate Range): **12.6%**
5. `acc_mag_variance` (Total 3D Acceleration Variance): **9.5%**

### Dual Inference Pipeline
To ensure fail-safe operation:
- **Trained Model Inference:** When features are precomputed, Scikit-learn Random Forest outputs predictions and class probabilities.
- **Real-Time Physics Heuristic Engine (`ml_inference.py`):** Operates on raw 50-sample sliding windows with sub-2ms latency. If physical thresholds are violated (e.g., $a_z \text{ drop} < -3.5 \text{ m/s}^2$ with jerk $> 22 \text{ m/s}^3$), an anomaly event triggers immediately without waiting for full FFT computation.

---

## L. Hazard Detection and Route-Memory Workflow

The end-to-end operational pipeline progresses through 11 sequential phases:

```
[1. Sensor Sampling @ 50Hz] ──> [2. 50-Sample FIFO Buffer] ──> [3. Edge Classification (NORMAL/POTHOLE/BREAKER)]
                                                                               │
[7. Leaflet HUD Map Marker] <── [6. Local Room & Supabase Storage] <── [5. 15m Spatial Deduplication] <── [4. Valid GPS Lock]
           │
[8. Spatial Proximity Scan (50-200m)] ──> [9. Forward Bearing Calculation] ──> [10. Voice & Siren Alert] ──> [11. Offline Sync Queue]
```

1. **Sensor Acquisition:** ESP32 reads calibrated 6-axis data from MPU-6050 over I2C every 20ms (50 Hz).
2. **Buffer Sliding:** The stream fills a circular FIFO buffer maintaining the most recent 50 samples (1.0 second).
3. **Condition Classification:** The inference engine classifies the window. If categorized as `NORMAL`, no hazard record is created.
4. **GPS Fix Verification:** If classified as `POTHOLE` or `SPEED_BREAKER`, the system checks satellite coordinates. If `gps_fix: false`, the event is logged as an unmapped sensor anomaly.
5. **15-Meter Spatial Deduplication:** The backend calculates the Haversine distance against all existing active hazards within a bounding box. If a match is found within **15.0 meters**:
   - `observation_count` increments by 1.
   - `confidence` is updated via weighted running average.
   - If `observation_count >= 3`, status automatically transitions from `PENDING` to `VERIFIED`.
   - If no hazard exists within 15 meters, a new UUID hazard record is inserted.
6. **Persistence:** The observation is written to the local Room database and Supabase `hazards` / `hazard_observations` tables.
7. **Map Rendering:** The hazard marker renders dynamically on the Leaflet / OSMDroid map with distinct icons and popups.
8. **Proximity Surveillance:** Every 1.0 second, the client queries nearby hazards within the user-configured radius (default: 100 meters).
9. **Approach Angle Calculation:** The system compares the vehicle's heading with the forward bearing to the hazard:
   - Difference $\le 65^\circ$: `APPROACHING` (Hazard is in the direct forward path).
   - Difference $> 90^\circ$: `RECEDING` (Vehicle has passed the hazard).
   - Other: `LATERAL` (Hazard is on an adjacent parallel street).
10. **Audible & Voice Warning:** If `APPROACHING` and within the distance threshold, the Web Speech API / Android TTS engine speaks: *"Warning: High severity Pothole 85 meters ahead"*. An alert cooldown timer (default: 12 seconds per hazard ID) prevents repetitive notification spam.
11. **Idempotent Offline Sync:** If disconnected, operations queue in Android Room. Once online, `/api/sync` processes batches using `operation_id` receipts to guarantee zero duplicate records.

---

## M. Dashboard Guide

The RoadGuard Web HUD is an automotive dark cockpit interface accessible at [`http://localhost:8000`](http://localhost:8000).

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│  [RG] ROADGUARD HUD — Department of CSE | IDEA Lab (RCOEM)      API ●  DB ●  AUDIO ●  │
├────────────────────────────────────────┬───────────────────────────────────────────────┤
│ 🛡️ PROXIMITY SURVEILLANCE RADAR ACTIVE │ 🗺️ LIVE ROAD HAZARDS MAP (RCOEM / NAGPUR)    │
│ Status: Standby • 100m Threshold       │ [All] [Potholes] [Breakers] [Real] [Test]     │
├────────────────────────────────────────┤                                               │
│ CLASSIFICATION ENGINE                  │                                               │
│ Surface: NORMAL ROAD (98.4%)           │                                               │
├────────────────────────────────────────┤                                               │
│ VEHICLE TELEMETRY                      │                                               │
│ Speedometer: [ 38 KM/H ]               │                                               │
│ Real Potholes: 3  | Breakers: 2        │                                               │
│ Sampling Rate: 50.0 Hz                 │                                               │
├────────────────────────────────────────┤                                               │
│ MPU-6050 3-AXIS ACCELEROMETER          │                                               │
│ [Canvas Oscilloscope: Z / X / Y Waves] │                                               │
├────────────────────────────────────────┤                                               │
│ PROXIMITY SPATIAL RADAR                │                                               │
│ Closest: 85m Ahead (Heading 45°) 🧭   ├───────────────────────────────────────────────┤
├────────────────────────────────────────┤ 📋 REGISTERED HAZARDS MANAGEMENT TABLE        │
│ 🔊 AUDIBLE SIREN & VOICE CONTROLS      │ Type | Location | Severity | Count | Action   │
│ [🚨 Test Siren]  [🗣️ Test Voice]       │ POTHOLE  21.177, 79.061  HIGH   3x  [Fly To]  │
├────────────────────────────────────────┤                                               │
│ 🛠️ TESTING & DIAGNOSTICS SUITE         │                                               │
│ [💥 Test Pothole]  [⚠️ Test Breaker]   │                                               │
│ [🟢 Test Normal]   [🛰️ Test GPS Loss]  │                                               │
│ [🚗 Simulate Approaching Hazard Demo]  │                                               │
│ [🗑️ Reset Test Data (Preserve Real)]   │                                               │
└────────────────────────────────────────┴───────────────────────────────────────────────┘
```

### Dashboard Panels & Diagnostic Controls

1. **Top Status Bar:** Displays live indicators for REST API health, Supabase cloud connectivity (`SUPABASE CONNECTED`), hardware streaming status, audio engine readiness, and real-time clock.
2. **Proximity Alert Banner:** Color-coded warning head-up display that flashes RED for Potholes and AMBER for Speed Breakers when hazards are detected within the threshold.
3. **Classification Engine:** Real-time display showing current road surface (`NORMAL ROAD`, `POTHOLE`, `SPEED_BREAKER`), model confidence percentage, and active ML model tag.
4. **Automotive Speedometer:** Circular SVG speedometer gauge displaying vehicle speed in km/h, counts of real potholes and speed breakers, and measured IMU sampling rate.
5. **MPU-6050 Accelerometer Oscilloscope:** High-speed HTML5 Canvas rendering three waveform traces: Cyan for Z-axis (vertical impact), Amber for X-axis (lateral), and Purple for Y-axis.
6. **Proximity Radar:** Displays straight-line distance to nearest hazard, hazard description, and rotating compass icon showing relative bearing.
7. **Audible Siren & Voice Controls:**
   - Volume slider (0% to 100%).
   - Warning distance threshold selector: `50m`, `100m (Default)`, `150m`, `200m`.
   - `🚨 TEST SIREN`: Triggers dual-tone synthesized alarm sound.
   - `🗣️ TEST VOICE ALERT`: Speaks test message through browser speech synthesizer.
   - `🔊 VOICE ALERTS: ON / OFF`: Toggles speech engine.
   - `🛑 STOP ALERT`: Immediately silences active alerts.
8. **Testing & Diagnostics Suite:**
   - `💥 Test Pothole`: Injects synthetic vertical impact ($a_z = 22.4 \text{ m/s}^2$) and registers a test pothole.
   - `⚠️ Test Breaker`: Injects synthetic upward lift ($a_z = 14.8 \text{ m/s}^2$) and registers a test speed breaker.
   - `🟢 Test Normal Road`: Injects smooth asphalt profile ($a_z = 9.81 \text{ m/s}^2$); verifies no hazard record is created.
   - `🛰️ Test GPS Loss`: Sets `gps_fix: false` and coordinates to `(0,0)` to verify graceful GPS degradation.
   - `🚗 Simulate Approaching Hazard (Advance Warning Demo)`: Animates vehicle driving from 200m towards a hazard, demonstrating warning trigger at 100m.
   - `🗑️ Reset Test Data (Preserve Real Records)`: Deletes synthetic records (`source='ADMIN_IMPORT'`) while preserving baseline road hazards.
9. **Interactive Map & Table:**
   - Leaflet dark-mode map centered on Nagpur / RCOEM with pulsing vehicle location ring.
   - Filter buttons: *All*, *Potholes Only*, *Breakers Only*, *Real Detections*, *Test Data*.
   - `📍 Recenter`: Centers map on current vehicle fix.
   - `🧠 ML Metrics`: Opens modal with real evaluation metrics from `/api/metrics`.
   - `📊 CSV` / `💾 JSON`: Downloads hazard dataset.

---

## N. API Documentation

The backend REST API is built with FastAPI and runs on port `8000`.

| Method | Endpoint | Purpose | Auth | Success Response | Error Codes |
| :--- | :--- | :--- | :---: | :--- | :---: |
| `GET` | `/` | Serves cockpit HUD dashboard (`static/index.html`) | Public | HTML Page | 404 |
| `GET` | `/health` | Subsystem health, Supabase status, and row counts | Public | `{"status": "ok", "database": {...}}` | 500 |
| `GET` | `/health/ready` | Readiness probe for container orchestration | Public | `{"status": "ready"}` | 503 |
| `GET` | `/api/devices` | Lists registered ESP32 devices and telemetry status | Public | `[{"device_name": "RoadGuard-ESP32", ...}]` | 500 |
| `POST` | `/api/telemetry` | Ingests 50Hz telemetry packets from ESP32/Android | Public | `{"status": "received", "measured_hz": 50.0}` | 400 |
| `GET` | `/api/telemetry/live` | Retrieves real-time vehicle snapshot & waveforms | Public | `{"speed_kmh": 38.0, "accel": {...}}` | 500 |
| `GET` | `/api/hazards` | Queries hazards with spatial radius and type filters | Public | `[{"id": "...", "hazard_type": "POTHOLE"}]`| 500 |
| `GET` | `/api/hazards/nearby` | Proximity radar query with bearing & approach status | Public | `[{"distance_meters": 85.2, "approach_status": "APPROACHING"}]` | 500 |
| `GET` | `/api/hazards/{id}` | Retrieves hazard details and historical observations | Public | `{"hazard": {...}, "observations": [...]}`| 404, 500 |
| `POST` | `/api/hazards` | Ingests hazard observation with 15m deduplication | Public | `{"status": "success", "hazard_id": "..."}`| 400 |
| `POST` | `/api/sync` | Idempotent offline batch synchronization | Public | `{"status": "success", "synced_count": 5}` | 400 |
| `GET` | `/api/trips` | Retrieves historical vehicle trip sessions | Public | `[{"id": "...", "status": "COMPLETED"}]` | 500 |
| `POST` | `/api/trips` | Creates a new trip monitoring session | Public | `{"status": "success", "trip_id": "..."}` | 400 |
| `GET` | `/api/metrics` | Returns machine learning evaluation report & metrics | Public | `{"overall_accuracy": 95.2, ...}` | 500 |
| `GET` | `/api/export` | Exports hazard data as JSON or CSV file download | Public | File attachment (`.json` or `.csv`) | 500 |
| `POST` | `/api/test/event` | Injects synthetic test anomalies into the pipeline | Public | `{"status": "test_applied", "event": "POTHOLE"}` | 400 |
| `POST` | `/api/test/reset` | Deletes test data while preserving baseline records | Public | `{"status": "success", "hazards_deleted": 2}`| 400 |

### API Request & Response Examples

#### 1. Ingest Observation (`POST /api/hazards`)
**Request Body:**
```json
{
  "hazard_type": "POTHOLE",
  "latitude": 21.1777,
  "longitude": 79.0617,
  "severity": "HIGH",
  "roughness_score": 8.4,
  "speed_kmh": 38.0,
  "az_peak": 22.4,
  "source": "SENSOR"
}
```
**Response (200 OK):**
```json
{
  "status": "success",
  "hazard_id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "hazard_type": "POTHOLE"
}
```

#### 2. Nearby Hazard Proximity Query (`GET /api/hazards/nearby?lat=21.1777&lng=79.0617&radius=150&heading=45`)
**Response (200 OK):**
```json
[
  {
    "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "hazard_type": "POTHOLE",
    "latitude": 21.1782,
    "longitude": 79.0621,
    "severity": "HIGH",
    "observation_count": 4,
    "verification_status": "VERIFIED",
    "source": "SENSOR",
    "distance_meters": 68.4,
    "bearing_degrees": 42.1,
    "approach_status": "APPROACHING"
  }
]
```

---

## O. Database Schema

The database is built on PostgreSQL 15 (hosted on Supabase) and comprises **8 structured tables**:

```mermaid
erDiagram
    profiles ||--o{ devices : "registers"
    profiles ||--o{ trips : "owns"
    profiles ||--o{ hazard_observations : "records"
    profiles ||--o| user_settings : "configures"
    profiles ||--o{ sync_receipts : "executes"
    trips ||--o{ trip_events : "contains"
    trips ||--o{ hazard_observations : "captures"
    hazards ||--o{ hazard_observations : "aggregates"

    profiles {
        uuid id PK
        string display_name
        string role
        timestamptz created_at
    }
    devices {
        uuid id PK
        uuid owner_id FK
        string device_name
        string device_identifier
        string status
        timestamptz last_seen_at
    }
    hazards {
        uuid id PK
        double latitude
        double longitude
        hazard_type_enum hazard_type
        severity_enum severity
        verification_status_enum verification_status
        float confidence
        int observation_count
        source_enum source
        boolean is_active
        timestamptz first_detected_at
        timestamptz last_detected_at
    }
    hazard_observations {
        uuid id PK
        uuid hazard_id FK
        uuid user_id FK
        uuid trip_id FK
        double latitude
        double longitude
        hazard_type_enum hazard_type
        severity_enum severity
        jsonb sensor_features
        timestamptz observed_at
    }
    trips {
        uuid id PK
        uuid user_id FK
        string status
        double distance_meters
        int duration_seconds
        int potholes_count
        int speed_breakers_count
        timestamptz started_at
        timestamptz ended_at
    }
    trip_events {
        uuid id PK
        uuid trip_id FK
        uuid user_id FK
        trip_event_type_enum event_type
        double latitude
        double longitude
        timestamptz occurred_at
    }
    user_settings {
        uuid user_id PK
        boolean voice_alerts_enabled
        boolean vibration_enabled
        int alert_distance_meters
        string sensitivity
    }
    sync_receipts {
        uuid id PK
        uuid user_id FK
        uuid operation_id
        timestamptz processed_at
    }
```

### Table Specifications
1. **`hazards`:** De-duplicated spatial road hazard map. One row per distinct physical hazard location. Indexed by latitude, longitude, and hazard type.
2. **`hazard_observations`:** Individual sensor hits. Stores raw physical metrics (`az_peak`, `roughness_score`, `speed_kmh`) as JSONB for auditability and model retraining.
3. **`trips`:** Vehicle monitoring sessions. Tracks total distance traveled, elapsed time, and hazard counts.
4. **`trip_events`:** Granular trip milestones (`HAZARD_DETECTED`, `WARNING_TRIGGERED`, `SENSOR_CONNECTED`, `GPS_LOST`).
5. **`devices`:** Registered ESP32 hardware peripherals mapped to vehicle owners.
6. **`profiles`:** User accounts linked to Supabase Auth (`auth.users`).
7. **`user_settings`:** Driver preferences synced from the Android app (warning distance 30–1000m, voice toggle).
8. **`sync_receipts`:** Idempotency ledger storing client-generated `operation_id` values to prevent duplicate uploads during offline reconnections.

---

## P. Testing and Verification

### Automated Test Suite Execution
RoadGuard includes automated integration and unit tests covering backend APIs, spatial calculations, and deduplication logic.

Run the test suite using pytest:
```bash
pytest RoadGuard/server/tests/test_api.py -v
```

### Verified Test Results (29/29 Passed)
*(Source of truth: `pytest RoadGuard/server/tests` on October 27, 2026, about 5 s on a laptop; the same suite runs in GitHub Actions on every push.)*

| Group | Tests | What is checked |
| :--- | :---: | :--- |
| Health & info | 4 | `/health`, `/health/ready`, database provider contract, `/api/info` deployment block |
| Telemetry | 4 | 50 Hz ingest + live snapshot, GPS loss and recovery, stale window reset, device registry |
| Hazards | 8 | NORMAL never stored, 15 m de-duplication with every observation kept, auto-verification at 3 observations, nearby/bearing/approach, map filters, export, offline sync, 404/400/422 handling |
| Live ML | 4 | Injected POTHOLE / SPEED_BREAKER / NORMAL windows classified correctly by the fused engine; one pothole in a live GPS stream stored exactly once (auto-ingest) |
| Moderation & security | 4 | Mark repaired hides a hazard (new detection opens a new one), admin token enforced, test API can be disabled, test-data isolation and reset |
| Analytics & ML report | 2 | `/api/stats` totals and 14-day series, `/api/metrics` with real confusion matrix and feature importances |
| Real-time & UI | 3 | WebSocket hello, hazard push and ping/pong; dashboard contains every required control |

The Docker stack (PostgreSQL + API + Caddy) is smoke-tested in CI as well: see `.github/workflows/ci.yml`.

---

## Q. Troubleshooting

### 1. Backend Server Fails to Start (`Port 8000 already in use`)
- **Cause:** Another application or a previous instance of Uvicorn is occupying port 8000.
- **Solution (Windows PowerShell):**
  ```powershell
  Get-Process -Id (Get-NetTCPConnection -LocalPort 8000).OwningProcess | Stop-Process -Force
  python app.py
  ```
- **Solution (Linux/macOS):**
  ```bash
  kill -9 $(lsof -ti:8000)
  python app.py
  ```

### 2. Database Error / `DB: ... DISCONNECTED`
- **Cause:** `DATABASE_URL` points at a PostgreSQL server that is down or has a different password.
- **Solution:** Run `python init_db.py` in `RoadGuard/server` to see the exact error. Remove `DATABASE_URL` from `server/.env` to fall back to the local SQLite file. On AWS: `sudo docker compose -f /opt/roadguard/docker-compose.yml ps` and `... logs db`.

### 3. Leaflet Map Does Not Display Hazard Markers
- **Cause:** Database contains 0 active records, or active filters exclude all hazards.
- **Solution:** Click the **All** filter button on the map header. If the database is empty, click `💥 Test Pothole` or `⚠️ Test Breaker` in the Diagnostics Suite to inject test landmarks.

### 4. Browser Audio or Voice Warnings Do Not Play
- **Cause:** Modern browsers (Chrome, Edge, Firefox) block audio autoplay until the user interacts with the page.
- **Solution:** Click anywhere on the dashboard. Click `🚨 TEST SIREN` or `🗣️ TEST VOICE ALERT` once to grant audio authorization. Verify that `🔊 VOICE ALERTS: ON` is active.

### 5. GPS Reports `No Fix` or Coordinates Show `(0.0, 0.0)`
- **Cause:** Ceramic patch antenna does not have line-of-sight to GPS satellites indoors.
- **Solution:** Move the hardware near a window or outdoors. For indoor development, use the simulation controls on the dashboard or toggle Demo Mode in the Android app.

### 6. ESP32 Disconnected / BLE Scan Fails
- **Cause:** Bluetooth is disabled on the phone/PC, or location permission is missing on Android.
- **Solution:** On Android, ensure **Bluetooth** and **Location** are turned ON. In Android App Info, verify that **Nearby Devices** permission is granted. Check that the ESP32 is powered and the Serial Monitor shows `BLE advertising as RoadGuard-ESP32`.

### 7. Normal Road Condition Registered as a Hazard
- **Cause:** Incorrect API usage.
- **Solution:** `app.py` and the database explicitly reject `NORMAL` condition via `CHECK (hazard_type != 'NORMAL')`. If test scripts attempt to ingest `NORMAL`, the API returns `{"status": "ignored"}`.

---

## R. Security and Privacy

- **GPS Location Privacy:** Geolocation tracks are anonymized. The shared `hazards` map stores only the physical anomaly coordinates; personal identity (`created_by`) is hidden from public client queries.
- **Key Separation:** The `SUPABASE_SERVICE_ROLE_KEY` has administrative privileges and is restricted strictly to the server backend (`server/.env`). The mobile application and browser clients use only the public `SUPABASE_ANON_KEY`.
- **Row Level Security Enforcement:** Every database table has RLS enabled with explicit policies in `0007_rls_policies.sql`. Users cannot read or delete other drivers' trips or raw sensor logs.
- **Idempotency Safeguards:** Offline sync requests use UUIDv4 `operation_id` receipts recorded in `sync_receipts`, preventing accidental replay attacks or network retry duplications.

---

## S. Limitations and Future Improvements

### Verified Current Limitations
1. **GPS Accuracy Under Bridges/Tunnels:** Standard NEO-6M single-band GPS accuracy degrades to 5–10 meters in urban canyons or covered tunnels, occasionally shifting hazard placement across parallel lanes.
2. **Vehicle Suspension Variances:** A stiff sports suspension produces higher vertical acceleration peaks over minor cracks compared to a soft SUV suspension, necessitating per-vehicle calibration.
3. **Weather Influences:** Flooded potholes filled with water exhibit modified hydrodynamic damping, which can alter accelerometer wave profiles.
4. **Local REST Server Requirement:** The web HUD currently connects to `localhost:8000`; deployment to cloud containers (Docker/Kubernetes) is planned for municipal access.

### Planned Future Improvements
- **On-Device TFLite Micro:** Porting the Random Forest / 1D-CNN classifier into C++ with TensorFlow Lite for Microcontrollers to run inference directly in ESP32 SRAM.
- **Computer Vision Fusion:** Combining smartphone dashcam optical segmentation (YOLOv8-tiny) with inertial IMU vibration signatures to verify pothole dimensions and depth before road impact.
- **Municipal Pothole Management Portal:** Web application for city public works departments with automated repair work-order ticketing, International Roughness Index (IRI) calculation, and repair verification tracking.
- **Dual-Band GNSS Integration:** Upgrading to multi-band RTK/GNSS modules for sub-meter lane-accurate hazard geolocation.

---

## T. Project Team and Academic Context

This project was developed at **Ramdeobaba University, Nagpur**, as an academic engineering endeavor:

- **Institution:** Shri Ramdeobaba College of Engineering and Management (Ramdeobaba University), Nagpur
- **Department:** Department of Computer Science & Engineering | IDEA Lab
- **Degree:** Bachelor of Technology in Computer Science & Engineering
- **Academic Year:** 2026–2027
- **Project Title:** Smart Pothole and Speed Breaker Detection and Route Memory Warning System (RoadGuard)
- **Project Guide:** **Dr. Suresh Balpande**

### Project Team Members
- **Bhoomi Magarde** (Section A6, Roll No. 19)
- **Mohammad Ejaz Mehendi** (Section A6, Roll No. 25)
- **Nidhish Dadhe** (Section A6, Roll No. 53)

---

## U. Project Status and Conclusion

### Current Status
RoadGuard has successfully completed its core engineering development and laboratory verification milestones:
- The **ESP32 firmware** reliably samples MPU-6050 telemetry at 50 Hz and streams BLE GATT packets.
- The **Machine Learning pipeline** demonstrates **95.2% accuracy** in distinguishing Normal Road, Potholes, and Speed Breakers.
- The **FastAPI backend** passes **17 out of 17 automated tests**, validating 15m deduplication, proximity radar calculations, and test isolation.
- The **Cockpit HUD Dashboard** is fully operational with interactive Leaflet mapping, live Canvas oscilloscope, proximity radar, Web Audio sirens, and voice warnings.
- The **Android Native Application** implements offline-first Room caching and background monitoring.

### Conclusion
RoadGuard demonstrates that cost-effective Internet of Things (IoT) hardware, combined with edge machine learning and spatial cloud synchronization, can transform everyday vehicles into autonomous road-quality surveillance nodes. By remembering hazard locations and proactively warning drivers through advance voice alerts, RoadGuard provides a tangible, life-saving solution to hazardous road conditions.

---

## V. License and Contribution Guidelines

### License
The license for this repository has **not yet been specified**. All rights remain reserved by the authors and Ramdeobaba University. Commercial use, public redistribution, or reproduction without written permission is prohibited.

### Contribution Guidelines
Contributions, bug reports, and academic discussions are welcome:
1. Fork the repository.
2. Create a feature branch (`git checkout -b feature/improved-proximity-algorithm`).
3. Commit your modifications with descriptive messages.
4. Ensure all automated tests pass (`pytest RoadGuard/server/tests/test_api.py -v`).
5. Open a Pull Request detailing the changes and relevant experimental data.
