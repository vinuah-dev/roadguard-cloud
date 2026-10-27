# RoadGuard Academic Defense & Presentation Guide

**Department of Computer Science & Engineering | IDEA Lab**  
**Ramdeobaba University, Academic Year 2026–27**  
**Project Guide:** Dr. Suresh Balpande  
**Project Team:**  
- Bhoomi Magarde (A6-19)
- Mohammad Ejaz Mehendi (A6-25)
- Nidhish Dadhe (A6-53)

---

## 1. Slide-by-Slide Presentation Structure

### Slide 1: Title & Academic Affiliation
- **Title:** Smart Pothole & Speed Breaker Detection and Warning System (RoadGuard)
- **Institution:** Department of Computer Science and Engineering, IDEA Lab, Ramdeobaba University.
- **Presenter Script:** "Good morning respected guide Dr. Suresh Balpande sir, external examiners, and faculty members. Today, our team—Bhoomi, Mohammad Ejaz, and Nidhish—presents RoadGuard, an end-to-end IoT, Edge Machine Learning, and Crowdsourced Geospatial system for real-time road hazard detection and driver protection."

### Slide 2: Problem Statement & Real-world Motivation
- **Key Points:**
  - Over 3,500 fatal accidents occur annually in India due to unmapped potholes and non-standard speed breakers.
  - Municipal surveys are manual, sporadic, and reactionary.
  - Existing navigation apps rely purely on manual user tapping, which is dangerous while driving.

### Slide 3: Three Target Classes & Sensor Signatures
- **Three Core Classes:**
  1. `NORMAL ROAD`: Steady gravity baseline ($a_z \approx 9.8\,\text{m/s}^2$, low variance, engine harmonics).
  2. `POTHOLE`: Sudden drop in vertical acceleration ($a_z < 3\,\text{m/s}^2$ free-fall) followed by high-magnitude rim collision shock ($a_z > 22\,\text{m/s}^2$). High kurtosis and sharp negative jerk.
  3. `SPEED BREAKER`: Smooth upward compression ramp ($a_z \approx 14\text{--}18\,\text{m/s}^2$), crest traversal, and gradual descent dip. Significant pitch angular velocity ($g_y$) impulse.

### Slide 4: System Architecture & Hardware Fabrication
- **Hardware Stack:** ESP32 microcontroller + MPU-6050 6-axis IMU sampling at 50Hz.
- **BLE Transmission:** 20-byte packed binary format using Nordic UART Service characteristics.
- **Why 50Hz?** Nyquist theorem: vehicle suspension impacts peak at 10–20Hz; 50Hz guarantees zero aliasing without overwhelming BLE bandwidth or draining battery.

### Slide 5: Machine Learning Benchmarks & Edge Deployment
- **Model Comparison:** Evaluated Random Forest, Gradient Boosting, SVM, and 1D-CNN.
- **Results:** Random Forest achieved **95.2% test accuracy** and **12ms inference latency** on device.
- **Feature Importance:** Peak-to-Peak $a_z$, vertical jerk, and $a_z$ kurtosis accounted for over 65% of classification power.

### Slide 6: Mobile Client Architecture (Android Jetpack Compose)
- Automotive HUD Dashboard with dynamic speedometer, real-time roughness waveform, and demo simulation mode.
- Interactive OpenStreetMap (OSMDroid) with spatial hazard clustering.
- Proximity warning engine with Text-to-Speech audio and haptic vibration (alerting 50–200m ahead).

### Slide 7: Cloud Backend & Crowdsourced Verification (Supabase + PostGIS)
- PostGIS `ST_DWithin` spatial query to deduplicate multiple vehicle detections at the same coordinate.
- Incrementing confirmation counter: when confirmations exceed 3, hazard confidence transitions to VERIFIED.
- Secure Row-Level Security (RLS) policies protecting user anonymity and preventing spam reports.

---

## 2. Frequently Asked Viva / Defense Questions

### Q1: Why did you choose an external ESP32 sensor rather than simply using the phone's built-in accelerometer?
**Answer:** While smartphones have accelerometers, they are carried in pockets, placed on loose car phone mounts, or handled during transit, introducing severe orientational noise and dampening. The ESP32 module is rigidly clamped to the vehicle chassis or steering console, capturing true unsprung vehicular suspension vibrations directly aligned with the road surface.

### Q2: How does the system handle false positives from sudden driver braking or turning?
**Answer:** Longitudinal acceleration ($a_x$) corresponds to braking/acceleration, and lateral acceleration ($a_y$) corresponds to lane changes or turns. Potholes and speed breakers predominantly manifest along the vertical axis ($a_z$) and pitch gyro axis ($g_y$). Our multi-axis feature extractor computes directional ratios and checks vertical jerk, filtering out lateral turns and forward braking maneuvers.

### Q3: What happens when the vehicle has no mobile internet connection?
**Answer:** RoadGuard was architected with offline-first design principles. The edge classifier executes locally on the smartphone, and known hazards along the user's route are cached in the local Room SQLite database. Proximity audio/vibration warnings work 100% offline. Newly detected hazards are queued in Room and synced to Supabase as soon as connectivity resumes.
