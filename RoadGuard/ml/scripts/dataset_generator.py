"""
RoadGuard Telemetry Dataset Generator
Department of Computer Science & Engineering, IDEA Lab
Ramdeobaba University, Academic Year 2026-27

Generates physically grounded synthetic vehicle IMU telemetry at 50Hz,
simulating suspension dynamics over:
  0: Normal Road
  1: Pothole
  2: Speed Breaker
"""

import os
import argparse
import numpy as np
import pandas as pd
from feature_extraction import extract_window_features


def generate_normal_window(length: int = 50, speed_kmh: float = 40.0) -> dict:
    """Simulates smooth road with vehicle engine harmonics and ambient road texture."""
    t = np.linspace(0, length / 50.0, length)
    engine_freq = (speed_kmh / 3.6) * 4.0  # ~45Hz engine vibration
    ambient_noise = np.random.normal(0, 0.25, length)
    engine_vibe = 0.15 * np.sin(2 * np.pi * engine_freq * t)

    az = 9.80665 + engine_vibe + ambient_noise
    ax = np.random.normal(0, 0.2, length)
    ay = np.random.normal(0, 0.15, length)
    gx = np.random.normal(0, 0.05, length)
    gy = np.random.normal(0, 0.05, length)
    gz = np.random.normal(0, 0.03, length)

    return {"ax": ax, "ay": ay, "az": az, "gx": gx, "gy": gy, "gz": gz}


def generate_pothole_window(length: int = 50, speed_kmh: float = 40.0, severity: float = 1.0) -> dict:
    """
    Simulates a pothole encounter:
      Phase 1: Free fall drop into depression (az drops sharply below 9.8, reaching 0-3 m/s²)
      Phase 2: Violent upward impact at bottom/rim (az spikes to 20-35 m/s²)
      Phase 3: Damped rebound oscillations as suspension settles.
    """
    base = generate_normal_window(length, speed_kmh)
    mid = np.random.randint(18, 25)

    # Free fall drop
    drop_depth = -7.5 * severity
    base["az"][mid] += drop_depth
    base["az"][mid + 1] += drop_depth * 0.8

    # Bottom impact spike
    impact_height = (14.0 + np.random.uniform(5.0, 15.0)) * severity
    base["az"][mid + 2] += impact_height
    base["az"][mid + 3] += impact_height * 0.45

    # Damped oscillation
    base["az"][mid + 4] -= impact_height * 0.25
    base["az"][mid + 5] += impact_height * 0.10

    # Pitch & roll rotational impulse
    base["gy"][mid + 2] += 1.8 * severity
    base["gx"][mid + 2] += (np.random.choice([-1, 1])) * 0.9 * severity

    return base


def generate_speed_breaker_window(length: int = 50, speed_kmh: float = 30.0, severity: float = 1.0) -> dict:
    """
    Simulates a speed breaker bump:
      Phase 1: Smooth upward compression heave (az rises smoothly to 14-19 m/s²)
      Phase 2: Crest passage
      Phase 3: Downward descent dip (az drops to 5-7 m/s²)
      Phase 4: Gentle settling.
    """
    base = generate_normal_window(length, speed_kmh)
    mid = np.random.randint(15, 22)

    # Upward crest compression over 4-6 samples
    crest_rise = (5.5 + np.random.uniform(2.0, 5.0)) * severity
    base["az"][mid] += crest_rise * 0.5
    base["az"][mid + 1] += crest_rise * 0.9
    base["az"][mid + 2] += crest_rise
    base["az"][mid + 3] += crest_rise * 0.8

    # Descent dip
    base["az"][mid + 4] -= crest_rise * 0.45
    base["az"][mid + 5] -= crest_rise * 0.35
    base["az"][mid + 6] += crest_rise * 0.1

    # Pitch rotation
    base["gy"][mid + 1] += 1.2 * severity
    base["gy"][mid + 4] -= 1.0 * severity

    return base


def build_synthetic_dataset(num_samples_per_class: int = 800, output_dir: str = "../data"):
    os.makedirs(output_dir, exist_ok=True)
    rows = []

    print(f"Generating {num_samples_per_class} windows per class (Total: {num_samples_per_class * 3})...")

    # 0: Normal Road
    for _ in range(num_samples_per_class):
        spd = np.random.uniform(20.0, 80.0)
        win = generate_normal_window(speed_kmh=spd)
        feat = extract_window_features(pd.DataFrame(win))
        feat["speed_kmh"] = spd
        feat["label"] = 0
        feat["class_name"] = "NORMAL"
        rows.append(feat)

    # 1: Pothole
    for _ in range(num_samples_per_class):
        spd = np.random.uniform(20.0, 70.0)
        sev = np.random.uniform(0.6, 1.4)
        win = generate_pothole_window(speed_kmh=spd, severity=sev)
        feat = extract_window_features(pd.DataFrame(win))
        feat["speed_kmh"] = spd
        feat["label"] = 1
        feat["class_name"] = "POTHOLE"
        rows.append(feat)

    # 2: Speed Breaker
    for _ in range(num_samples_per_class):
        spd = np.random.uniform(15.0, 50.0)
        sev = np.random.uniform(0.6, 1.4)
        win = generate_speed_breaker_window(speed_kmh=spd, severity=sev)
        feat = extract_window_features(pd.DataFrame(win))
        feat["speed_kmh"] = spd
        feat["label"] = 2
        feat["class_name"] = "SPEED_BREAKER"
        rows.append(feat)

    df = pd.DataFrame(rows)
    # Shuffle
    df = df.sample(frac=1.0, random_state=42).reset_index(drop=True)

    csv_path = os.path.join(output_dir, "roadguard_features_dataset.csv")
    df.to_csv(csv_path, index=False)
    print(f"Saved dataset with {len(df)} samples and {df.shape[1]} features to {csv_path}")
    return df


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--samples", type=int, default=800, help="Samples per class")
    parser.add_argument("--out", type=str, default="c:/Projects/Smart Potholes & Speed Breaker Detection/RoadGuard/ml/data")
    args = parser.parse_args()
    build_synthetic_dataset(args.samples, args.out)
