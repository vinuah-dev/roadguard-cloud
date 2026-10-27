# PROJECT SYNOPSIS

## Smart Pothole & Speed Breaker Detection and Warning System

### Department of Computer Science & Engineering | IDEA Lab
**Shri Ramdeobaba College of Engineering and Management (Ramdeobaba University), Nagpur**  
**Academic Year:** 2026–2027

---

### Project Metadata
- **Project Guide:** Dr. Suresh Balpande
- **Student Team:**
  - Bhoomi Magarde (Roll No: A6-19)
  - Mohammad Ejaz Mehendi (Roll No: A6-25)
  - Nidhish Dadhe (Roll No: A6-53)

---

### 1. Abstract
Road surface anomalies such as potholes and unstandardized speed breakers cause thousands of fatal traffic accidents, severe spinal injuries, and billions in vehicular suspension damage annually in developing economies. Conventional road inspection techniques depend on manual municipal surveys or costly dedicated LiDAR laser profiling vehicles, resulting in sparse, outdated datasets.

**RoadGuard** introduces an end-to-end, edge-computing, crowdsourced telemetry system. A vehicle-mounted IoT module combining an ESP32 microcontroller and an MPU-6050 6-axis Inertial Measurement Unit (IMU) samples vertical and lateral vehicle dynamics at 50 Hz. The telemetry is streamed over Bluetooth Low Energy (BLE) to an Android application. 

A high-efficiency classification model evaluates sliding temporal windows to categorize road surfaces into three distinct classes:
1. **Normal Road**
2. **Pothole**
3. **Speed Breaker**

Geo-tagged detections are synchronized with a high-performance Supabase PostgreSQL database leveraging PostGIS spatial indexing. Incoming drivers receive proactive real-time multimodal alerts (visual HUD banner, audible Text-to-Speech warnings, and haptic pulses) 50–200 meters in advance of approaching hazards, mitigating collision severity and promoting urban road safety.

---

### 2. Motivation and Problem Statement
- **High Accident Fatality:** Road anomalies contribute significantly to two-wheeler skids and sudden vehicular deceleration accidents.
- **Delayed Road Maintenance:** Municipal authorities lack continuous, quantitative road roughness indices (IRI) to prioritize repair budgets.
- **Latency of Cloud Only Solutions:** Pure cloud processing introduces network latency; drivers require sub-50ms local hazard detection and immediate proximity alerts regardless of cellular connectivity.

---

### 3. Objectives
1. Design and fabricate a compact, cost-effective IoT hardware module utilizing ESP32 and MPU-6050 for high-frequency (50Hz) vibration telemetry.
2. Develop a low-latency edge classification engine capable of distinguishing Potholes, Speed Breakers, and Normal Roads with greater than 90% accuracy.
3. Construct a responsive Android HUD application with live speedometer, real-time waveform visualization, OSMDroid interactive map, and automated proximity alerts.
4. Establish a scalable Supabase cloud backend with PostGIS spatial clustering, spatial queries, and Row-Level Security (RLS) for crowdsourced hazard verification.
5. Provide open data analytics and road roughness heatmaps for urban municipal road infrastructure management.

---

### 4. Technical Stack
- **Embedded Hardware:** ESP32 Microcontroller, MPU-6050 IMU, BLE 4.2/5.0, C++/Arduino.
- **Mobile Client:** Kotlin, Jetpack Compose, Coroutines/Flow, Dagger Hilt, Room Local Database, OSMDroid (OpenStreetMap), Android Foreground Services.
- **Machine Learning:** Python, Scikit-learn, Random Forest Classifier, 1D-CNN, TFLite Edge Runtime.
- **Backend / Cloud:** Supabase, PostgreSQL 15, PostGIS extension, Deno Edge Functions, Row-Level Security (RLS).
