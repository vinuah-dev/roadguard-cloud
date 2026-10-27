"""
RoadGuard Machine Learning Pipeline - Feature Extraction Module
Department of Computer Science & Engineering, IDEA Lab
Ramdeobaba University, Academic Year 2026-27

Extracts time-domain, frequency-domain, and statistical features from 50Hz 3-axis
accelerometer and gyroscope sensor sliding windows.
"""

import numpy as np
from scipy import stats
from scipy.fft import rfft, rfftfreq


def extract_axis_features(signal: np.ndarray, prefix: str) -> dict:
    """
    Extracts comprehensive features for a single 1D sensor axis array.
    """
    signal = np.asarray(signal, dtype=float)
    n = len(signal)
    if n == 0:
        return {}

    mean = float(np.mean(signal))
    std = float(np.std(signal))
    var = float(np.var(signal))
    min_val = float(np.min(signal))
    max_val = float(np.max(signal))
    peak_to_peak = max_val - min_val
    rms = float(np.sqrt(np.mean(signal**2)))
    kurtosis = float(stats.kurtosis(signal))
    skewness = float(stats.skew(signal))
    crest_factor = float(np.max(np.abs(signal)) / (rms + 1e-6))
    energy = float(np.sum(signal**2) / n)

    # Zero-crossing rate around mean
    centered = signal - mean
    zero_crossings = float(np.sum(np.diff(np.sign(centered)) != 0) / (n - 1)) if n > 1 else 0.0

    # Frequency domain features via real FFT
    fft_vals = np.abs(rfft(centered))
    fft_freqs = rfftfreq(n, d=1.0 / 50.0)  # 50 Hz sampling rate
    dominant_freq = float(fft_freqs[np.argmax(fft_vals)]) if len(fft_vals) > 0 else 0.0
    spectral_energy = float(np.sum(fft_vals**2) / (len(fft_vals) + 1e-6))

    return {
        f"{prefix}_mean": mean,
        f"{prefix}_std": std,
        f"{prefix}_var": var,
        f"{prefix}_min": min_val,
        f"{prefix}_max": max_val,
        f"{prefix}_p2p": peak_to_peak,
        f"{prefix}_rms": rms,
        f"{prefix}_kurtosis": kurtosis,
        f"{prefix}_skewness": skewness,
        f"{prefix}_crest_factor": crest_factor,
        f"{prefix}_energy": energy,
        f"{prefix}_zcr": zero_crossings,
        f"{prefix}_dominant_freq": dominant_freq,
        f"{prefix}_spectral_energy": spectral_energy,
    }


def extract_window_features(window_df) -> dict:
    """
    Given a dataframe representing a window of 50 samples (1.0s at 50Hz) with
    ax, ay, az, gx, gy, gz, computes full multimodal feature vector.
    """
    features = {}

    for axis in ["ax", "ay", "az", "gx", "gy", "gz"]:
        if axis in window_df:
            axis_feat = extract_axis_features(window_df[axis].values, prefix=axis)
            features.update(axis_feat)

    # Combined magnitude features
    if all(k in window_df for k in ["ax", "ay", "az"]):
        acc_mag = np.sqrt(window_df["ax"]**2 + window_df["ay"]**2 + window_df["az"]**2)
        mag_feat = extract_axis_features(acc_mag.values, prefix="acc_mag")
        features.update(mag_feat)

    # Vertical Z-axis dynamic range (primary differentiator for pothole vs speed breaker)
    if "az" in window_df:
        az_vals = window_df["az"].values
        diff_az = np.diff(az_vals)
        features["az_max_jerk"] = float(np.max(np.abs(diff_az)) * 50.0) if len(diff_az) > 0 else 0.0
        features["az_min_drop"] = float(np.min(az_vals) - 9.80665)
        features["az_max_lift"] = float(np.max(az_vals) - 9.80665)

    return features
