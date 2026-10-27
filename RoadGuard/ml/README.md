# RoadGuard Machine Learning Pipeline

**Department of Computer Science & Engineering | IDEA Lab**  
**Ramdeobaba University, Academic Year 2026–27**  
**Project Guide:** Dr. Suresh Balpande  
**Team:** Bhoomi Magarde (A6-19), Mohammad Ejaz Mehendi (A6-25), Nidhish Dadhe (A6-53)

---

## 1. Overview
The RoadGuard ML pipeline classifies road surface conditions into **three target classes**:
1. `NORMAL` — Smooth asphalt / standard pavement.
2. `POTHOLE` — Depressions, surface fractures, sharp drops followed by rim impact shocks.
3. `SPEED_BREAKER` — Raised traffic calming humps/bumps characterized by upward heave and smooth descent.

Sensor telemetry is captured at **50 Hz** using an MPU-6050 6-axis IMU (3-axis accelerometer $\pm 8g$ and 3-axis gyroscope $\pm 500^\circ/\text{s}$) over BLE to the Android smartphone.

## 2. Directory Structure
```
ml/
├── data/                      # Generated CSV datasets & real-world logs
├── models/                    # Serialized .joblib models & feature manifests
├── scripts/
│   ├── dataset_generator.py   # Synthetic physics-grounded dataset builder
│   ├── feature_extraction.py  # Time, frequency, & statistical feature extractors
│   ├── train_classifier.py    # Multi-model training, 5-fold CV, & exporter
│   └── evaluate_models.py     # Academic thesis table and confusion matrix exporter
└── requirements.txt           # Python dependencies
```

## 3. Quickstart & Execution

### Step 1: Install Dependencies
```bash
python -m pip install -r requirements.txt
```

### Step 2: Generate Training Dataset
```bash
python scripts/dataset_generator.py --samples 1000
```

### Step 3: Train and Benchmark Models
```bash
python scripts/train_classifier.py
```

### Step 4: Generate Academic Thesis Summary
```bash
python scripts/evaluate_models.py
```
