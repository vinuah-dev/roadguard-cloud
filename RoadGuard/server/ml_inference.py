"""
RoadGuard Machine Learning Inference & Evaluation Engine
Ramdeobaba University - IDEA Lab | Department of CSE

Two engines classify every 1-second (50-sample) sensor window:
  * a physics heuristic (drop, lift, peak-to-peak, jerk) that needs no model, and
  * the trained Random Forest over the full feature vector built by
    ml/scripts/feature_extraction.py (same code that produced the training set).

The trained model file is not committed to git. If it is missing, or was pickled by
an incompatible scikit-learn version, it is retrained from ml/data on first start
(a few seconds) and saved next to feature_names.json.
"""

import json
import logging
import os
import sys
import threading
import time
import warnings
from typing import Any, Dict, List, Optional

import joblib
import numpy as np
import pandas as pd
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix
from sklearn.model_selection import train_test_split

log = logging.getLogger("roadguard.ml")

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ML_DIR = os.path.join(BASE_DIR, "ml")
MODEL_PATH = os.getenv("ROADGUARD_MODEL_PATH") or os.path.join(ML_DIR, "models", "roadguard_classifier.joblib")
FEATURE_NAMES_PATH = os.path.join(ML_DIR, "models", "feature_names.json")
DATASET_PATH = os.path.join(ML_DIR, "data", "roadguard_features_dataset.csv")

sys.path.insert(0, os.path.join(ML_DIR, "scripts"))
from feature_extraction import extract_window_features  # noqa: E402

CLASS_NAMES = ["NORMAL", "POTHOLE", "SPEED_BREAKER"]
GRAVITY = 9.81

AXIS_NAMES = {
    "ax": "lateral acceleration (X)",
    "ay": "longitudinal acceleration (Y)",
    "az": "vertical acceleration (Z)",
    "gx": "roll rate (gyro X)",
    "gy": "pitch rate (gyro Y)",
    "gz": "yaw rate (gyro Z)",
    "acc_mag": "3-D acceleration magnitude",
}
STAT_NAMES = {
    "mean": "mean", "std": "standard deviation", "var": "variance", "min": "minimum",
    "max": "maximum", "p2p": "peak-to-peak range", "rms": "RMS level", "kurtosis": "kurtosis (impulsiveness)",
    "skewness": "skewness", "crest_factor": "crest factor (peak / RMS)", "energy": "signal energy",
    "zcr": "zero-crossing rate", "dominant_freq": "dominant frequency", "spectral_energy": "spectral energy",
}
SPECIAL_FEATURES = {
    "az_max_jerk": "Maximum jerk (rate of change) of vertical acceleration",
    "az_min_drop": "Deepest free-fall drop below 1 g",
    "az_max_lift": "Highest upward lift above 1 g",
    "speed_kmh": "Vehicle speed",
}


def describe_feature(name: str) -> str:
    if name in SPECIAL_FEATURES:
        return SPECIAL_FEATURES[name]
    for axis in sorted(AXIS_NAMES, key=len, reverse=True):
        if name.startswith(axis + "_"):
            stat = name[len(axis) + 1:]
            return f"{STAT_NAMES.get(stat, stat).capitalize()} of {AXIS_NAMES[axis]}"
    return name


def severity_from_peak(peak: float, condition: str) -> str:
    if condition == "POTHOLE":
        return "CRITICAL" if peak > 14.0 else "HIGH" if peak > 8.0 else "MEDIUM"
    if condition == "SPEED_BREAKER":
        return "HIGH" if peak > 6.0 else "MEDIUM" if peak > 4.0 else "LOW"
    return "LOW"


class RoadGuardMLEngine:
    def __init__(self, auto_train: bool = True):
        self.model = None
        self.feature_names: List[str] = []
        self.model_origin = "none"
        self.cached_metrics: Optional[Dict[str, Any]] = None
        self.last_inference_ms = 0.0
        self._lock = threading.Lock()
        self.load_artifacts(auto_train=auto_train)

    # ------------------------------------------------------------- artifacts
    def load_artifacts(self, auto_train: bool = True) -> None:
        if os.path.exists(FEATURE_NAMES_PATH):
            try:
                with open(FEATURE_NAMES_PATH, "r", encoding="utf-8") as f:
                    self.feature_names = json.load(f)
            except (OSError, ValueError) as e:
                log.warning("Could not read feature names: %s", e)

        if os.path.exists(MODEL_PATH):
            try:
                with warnings.catch_warnings():
                    # A model pickled by another scikit-learn release is unreliable: retrain instead.
                    warnings.simplefilter("error")
                    self.model = joblib.load(MODEL_PATH)
                self.model_origin = "loaded from disk"
            except Exception as e:  # noqa: BLE001 - any unpickling problem means "retrain"
                log.warning("Saved model unusable (%s); retraining", e)
                self.model = None

        if self.model is None and auto_train and os.path.exists(DATASET_PATH):
            self.train_and_save()

    def _load_dataset(self):
        df = pd.read_csv(DATASET_PATH)
        feature_cols = [c for c in df.columns if c not in ("label", "class_name")]
        return df, feature_cols

    def train_and_save(self) -> None:
        """Train the Random Forest on the same 80 % split train_classifier.py uses."""
        started = time.perf_counter()
        df, feature_cols = self._load_dataset()
        X_train, _, y_train, _ = train_test_split(
            df[feature_cols], df["label"].values, test_size=0.20, random_state=42, stratify=df["label"].values
        )
        model = RandomForestClassifier(n_estimators=100, max_depth=12, random_state=42, n_jobs=1)
        model.fit(X_train, y_train)
        self.model, self.feature_names, self.model_origin = model, feature_cols, "trained at startup"
        self.cached_metrics = None
        log.info("Trained Random Forest on %d windows in %.1fs", len(X_train), time.perf_counter() - started)
        try:
            os.makedirs(os.path.dirname(MODEL_PATH), exist_ok=True)
            joblib.dump(model, MODEL_PATH)
            with open(FEATURE_NAMES_PATH, "w", encoding="utf-8") as f:
                json.dump(feature_cols, f, indent=2)
        except OSError as e:
            log.warning("Could not save trained model: %s", e)

    def get_model_status(self) -> Dict[str, Any]:
        params = self.model.get_params() if self.model is not None else {}
        return {
            "model_configured": self.model is not None,
            "model_type": type(self.model).__name__ if self.model is not None else "NONE",
            "model_origin": self.model_origin,
            "n_estimators": params.get("n_estimators"),
            "features_count": len(self.feature_names),
            "classes": CLASS_NAMES,
            "inference": "random_forest + heuristic" if self.model is not None else "heuristic only",
            "last_inference_ms": round(self.last_inference_ms, 2),
            "model_file": MODEL_PATH if os.path.exists(MODEL_PATH) else None,
        }

    # ------------------------------------------------------------- inference
    def classify_window_heuristic(self, z_samples: List[float], speed_kmh: float = 30.0) -> Dict[str, Any]:
        """
        Physics rules on the vertical axis of a 1-second window.
        Pothole: free-fall drop then a hard impact (high jerk).
        Speed breaker: smooth upward lift with moderate jerk.
        """
        if not z_samples or len(z_samples) < 5:
            return {"condition": "NORMAL", "confidence": 85.0, "severity": "LOW"}

        arr = np.asarray(z_samples, dtype=float)
        peak_z = float(np.max(np.abs(arr - GRAVITY)))
        min_drop = float(np.min(arr) - GRAVITY)
        max_lift = float(np.max(arr) - GRAVITY)
        p2p = float(np.max(arr) - np.min(arr))
        jerk = float(np.max(np.abs(np.diff(arr))) * 50.0)

        if (min_drop < -3.5 and peak_z > 4.5 and jerk > 22.0) or (p2p > 12.0 and jerk > 28.0):
            conf = min(0.98, 0.70 + (peak_z - 4.5) * 0.04)
            return {"condition": "POTHOLE", "confidence": round(conf * 100, 1),
                    "severity": severity_from_peak(peak_z, "POTHOLE")}

        if (max_lift > 2.8 and jerk <= 22.0 and p2p > 4.0) or (peak_z > 2.8 and speed_kmh <= 60.0 and jerk < 20.0):
            conf = min(0.96, 0.68 + (peak_z - 2.8) * 0.05)
            return {"condition": "SPEED_BREAKER", "confidence": round(conf * 100, 1),
                    "severity": severity_from_peak(peak_z, "SPEED_BREAKER")}

        return {"condition": "NORMAL", "confidence": 97.5, "severity": "LOW"}

    def predict_window(self, window: Dict[str, List[float]], speed_kmh: float) -> Optional[Dict[str, Any]]:
        """Random Forest prediction for one window of ax, ay, az, gx, gy, gz samples."""
        if self.model is None:
            return None
        started = time.perf_counter()
        feats = extract_window_features(pd.DataFrame(window))
        feats["speed_kmh"] = float(speed_kmh)
        names = self.feature_names or list(feats)
        row = pd.DataFrame([[feats.get(n, 0.0) for n in names]], columns=names)
        X = row if hasattr(self.model, "feature_names_in_") else row.values
        with self._lock:
            proba = self.model.predict_proba(X)[0]
        self.last_inference_ms = (time.perf_counter() - started) * 1000
        best = int(np.argmax(proba))
        label = CLASS_NAMES[int(self.model.classes_[best])]
        peak = float(np.max(np.abs(np.asarray(window["az"], dtype=float) - GRAVITY)))
        return {
            "condition": label,
            "confidence": round(float(proba[best]) * 100, 1),
            "severity": severity_from_peak(peak, label),
            "probabilities": {CLASS_NAMES[int(c)]: round(float(p) * 100, 1) for c, p in zip(self.model.classes_, proba)},
        }

    @staticmethod
    def fuse(heuristic: Dict[str, Any], model: Optional[Dict[str, Any]]) -> Dict[str, Any]:
        """
        Combine the two engines. An anomaly that both engines agree on wins outright.
        Otherwise the model decides when it is confident (>= 70 %); a heuristic-only
        anomaly is kept, because the model was trained on noisy road windows and can
        miss a single isolated spike that the physics rules catch.
        """
        if model is None:
            return {**heuristic, "engine": "heuristic"}
        h_cond, m_cond = heuristic["condition"], model["condition"]
        if h_cond == m_cond:
            conf = max(heuristic["confidence"], model["confidence"])
            return {"condition": h_cond, "confidence": conf,
                    "severity": model["severity"] if h_cond != "NORMAL" else "LOW", "engine": "fused"}
        if m_cond != "NORMAL" and model["confidence"] >= 70.0:
            return {**{k: model[k] for k in ("condition", "confidence", "severity")}, "engine": "random_forest"}
        if h_cond != "NORMAL":
            return {**heuristic, "engine": "heuristic"}
        return {**{k: model[k] for k in ("condition", "confidence", "severity")}, "engine": "random_forest"}

    # ------------------------------------------------------------ evaluation
    def compute_evaluation_metrics(self) -> Dict[str, Any]:
        """Real metrics on the held-out 20 % split of the bundled dataset."""
        if self.cached_metrics:
            return self.cached_metrics
        if not os.path.exists(DATASET_PATH):
            return {"status": "NOT_EVALUATED",
                    "message": "Dataset not found. Run ml/scripts/dataset_generator.py to build it."}
        if self.model is None:
            return {"status": "MODEL_NOT_CONFIGURED", "message": "Trained model not found and could not be trained."}

        try:
            df, feature_cols = self._load_dataset()
            names = self.feature_names or feature_cols
            _, X_test, _, y_test = train_test_split(
                df[names], df["label"].values, test_size=0.20, random_state=42, stratify=df["label"].values
            )
            X = X_test if hasattr(self.model, "feature_names_in_") else X_test.values
            started = time.perf_counter()
            y_pred = self.model.predict(X)
            per_window_ms = (time.perf_counter() - started) * 1000 / max(1, len(X_test))

            report = classification_report(y_test, y_pred, target_names=CLASS_NAMES, output_dict=True, digits=4)
            per_class = {
                cls: {
                    "precision": round(report[cls]["precision"] * 100, 2),
                    "recall": round(report[cls]["recall"] * 100, 2),
                    "f1_score": round(report[cls]["f1-score"] * 100, 2),
                    "support": int(report[cls]["support"]),
                }
                for cls in CLASS_NAMES if cls in report
            }

            top_features = []
            importances = getattr(self.model, "feature_importances_", None)
            if importances is not None:
                ranked = sorted(zip(names, importances), key=lambda p: p[1], reverse=True)[:8]
                top_features = [
                    {"feature": n, "importance_pct": round(float(v) * 100, 1), "description": describe_feature(n)}
                    for n, v in ranked
                ]

            params = self.model.get_params()
            algorithm = type(self.model).__name__
            if "n_estimators" in params:
                algorithm += f" ({params['n_estimators']} trees, max depth {params.get('max_depth')})"

            self.cached_metrics = {
                "status": "EVALUATED",
                "algorithm": algorithm,
                "model_origin": self.model_origin,
                "total_samples": int(len(df)),
                "test_samples": int(len(y_test)),
                "features_count": len(names),
                "overall_accuracy": round(float(accuracy_score(y_test, y_pred)) * 100, 2),
                "macro_f1": round(report["macro avg"]["f1-score"] * 100, 2),
                "avg_inference_ms_per_window": round(per_window_ms, 3),
                "per_class_metrics": per_class,
                "confusion_matrix": {"labels": CLASS_NAMES, "matrix": confusion_matrix(y_test, y_pred).tolist()},
                "top_features": top_features,
            }
            return self.cached_metrics
        except Exception as e:  # noqa: BLE001 - surfaced to the dashboard as an error state
            return {"status": "ERROR", "message": f"Failed to compute evaluation metrics: {e}"}


ml_engine = RoadGuardMLEngine(auto_train=os.getenv("ROADGUARD_ML_AUTO_TRAIN", "true").lower() != "false")
