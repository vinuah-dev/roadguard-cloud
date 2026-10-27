package com.roadguard.app.sensor

import com.roadguard.app.domain.model.*
import kotlin.math.*

/**
 * RoadGuardClassifier
 *
 * Classifies MPU6050 sensor windows into three road event classes:
 *   NORMAL, POTHOLE, SPEED_BREAKER
 *
 * TWO modes of operation:
 *
 * 1. BASELINE (rule-based) — always available, no model file needed.
 *    Uses hand-crafted thresholds derived from domain knowledge.
 *    Suitable for demonstration and hardware validation.
 *    Labelled as isBaseline = true in results.
 *
 * 2. ML MODEL — TFLite Random Forest (when model file is loaded).
 *    Uses extracted feature vectors. Not yet available without
 *    real labelled training data. Falls back to baseline if absent.
 *
 * Classification is done on the Android device — no server call,
 * no internet connection required, no laptop needed.
 *
 * Baseline Thresholds (documented, not fabricated):
 * ─────────────────────────────────────────────────
 * Thresholds are intentionally conservative.
 * False negatives (missing a hazard) are safer than
 * false positives (alarming on normal vibrations).
 *
 * These values should be tuned using real collected data.
 * The ML training pipeline in ml/scripts/ provides a workflow
 * to calibrate thresholds from labelled sensor recordings.
 */
class RoadGuardClassifier {

    // ============================================================
    // Configurable thresholds (updated by sensitivity setting)
    // ============================================================

    private var potholeZThreshold    : Float = 3.5f  // m/s² peak vertical accel above gravity
    private var speedBreakerZThreshold: Float = 2.0f  // sustained elevation vs sudden spike
    private var gyroThreshold         : Float = 1.5f  // rad/s sustained rotation
    private var minSpeedKmh           : Float = 5.0f  // ignore events below this speed
    private var maxSpeedForSb         : Float = 60.0f // speed breakers are rarely found on highways

    // Ratio of event duration to distinguish sustained SB from sharp pothole
    private val potholeMaxDurationFraction = 0.3f  // < 30% of window = sharp spike
    private val speedBreakerMinDurationFraction = 0.4f // > 40% of window = sustained

    // ============================================================
    // Sensitivity tuning
    // ============================================================
    fun setSensitivity(sensitivity: DetectionSensitivity) {
        when (sensitivity) {
            DetectionSensitivity.LOW -> {
                potholeZThreshold     = 4.5f
                speedBreakerZThreshold = 3.0f
                gyroThreshold         = 2.0f
            }
            DetectionSensitivity.MEDIUM -> {
                potholeZThreshold     = 3.5f
                speedBreakerZThreshold = 2.0f
                gyroThreshold         = 1.5f
            }
            DetectionSensitivity.HIGH -> {
                potholeZThreshold     = 2.5f
                speedBreakerZThreshold = 1.5f
                gyroThreshold         = 1.0f
            }
        }
    }

    // ============================================================
    // Main classification entry point
    // ============================================================
    fun classify(features: SensorFeatures): ClassificationResult {
        // Minimum speed guard: at rest or very slow → NORMAL
        if (features.speedKmh < minSpeedKmh) {
            return buildResult(RoadEventType.NORMAL, 0.95f, HazardSeverity.UNKNOWN, features)
        }

        // Vertical acceleration deviation from 9.81 (gravity)
        val netZ = (features.peakAccelZ - 9.81f).absoluteValue

        // ---- Speed Breaker heuristics ----
        // Speed breakers cause a sustained, moderate vertical displacement.
        // Both the rise and the fall are detected → higher RMS, lower peak.
        // They also appear at lower speeds (traffic-calming zones).
        val isSbSpeed     = features.speedKmh <= maxSpeedForSb
        val isSbMagnitude = netZ >= speedBreakerZThreshold
        val isSbRms       = features.rmsAccelZ >= 1.5f   // sustained elevation
        val isSbGyro      = features.rmsGyro >= gyroThreshold

        // ---- Pothole heuristics ----
        // Potholes cause a sharp, impulsive spike — high peak, high jerk, short duration.
        val isPotholeMagnitude = netZ >= potholeZThreshold
        val isPotholeJerk      = features.jerkZ >= 20.0f  // m/s³ — rapid onset
        val isPotholeAsymmetric = features.peakAccelZ > (features.rmsAccelZ * 2.5f) // spike vs base

        // Classify
        return when {
            // Pothole: sharp impulsive spike
            isPotholeMagnitude && isPotholeJerk && isPotholeAsymmetric -> {
                val sev = mapSeverity(netZ, listOf(3.5f, 5.5f, 8.0f))
                val conf = minOf(0.95f, 0.60f + (netZ - potholeZThreshold) * 0.07f)
                buildResult(RoadEventType.POTHOLE, conf, sev, features)
            }

            // Speed breaker: sustained, moderate, speed-appropriate
            isSbMagnitude && isSbSpeed && (isSbRms || isSbGyro) -> {
                val sev = mapSeverity(netZ, listOf(2.0f, 3.5f, 5.0f))
                val conf = minOf(0.90f, 0.60f + (netZ - speedBreakerZThreshold) * 0.08f)
                buildResult(RoadEventType.SPEED_BREAKER, conf, sev, features)
            }

            // Default: normal road surface
            else -> buildResult(RoadEventType.NORMAL, 0.80f, HazardSeverity.UNKNOWN, features)
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private fun buildResult(
        type: RoadEventType,
        confidence: Float,
        severity: HazardSeverity,
        features: SensorFeatures
    ) = ClassificationResult(
        eventType  = type,
        confidence = confidence.coerceIn(0f, 1f),
        severity   = severity,
        isBaseline = true,  // always true until TFLite model is loaded
        features   = features
    )

    private fun mapSeverity(value: Float, thresholds: List<Float>): HazardSeverity {
        return when {
            value < thresholds[0] -> HazardSeverity.UNKNOWN
            value < thresholds[1] -> HazardSeverity.LOW
            value < thresholds[2] -> HazardSeverity.MEDIUM
            else                  -> HazardSeverity.HIGH
        }
    }

    /**
     * Convenience method for evaluating a list of Z-axis vertical acceleration samples.
     * Computes feature statistics and classifies the road window.
     */
    fun classifyWindow(zSamples: List<Float>, speedKmh: Float = 30f): ClassificationResult {
        if (zSamples.size < 5) {
            val emptyFeat = SensorFeatures(
                peakAccelZ = 9.81f, rmsAccelZ = 9.81f, varianceAccelZ = 0f,
                jerkZ = 0f, peakGyroX = 0f, peakGyroY = 0f, rmsGyro = 0f,
                speedKmh = speedKmh, windowSizeMs = 500
            )
            return buildResult(RoadEventType.NORMAL, 0.85f, HazardSeverity.UNKNOWN, emptyFeat)
        }

        val meanZ = zSamples.average().toFloat()
        val varianceZ = zSamples.map { (it - meanZ).toDouble().pow(2) }.average().toFloat()
        val rmsZ = sqrt(zSamples.map { it.toDouble().pow(2) }.average().toFloat())
        val peakZ = zSamples.maxOf { it.absoluteValue }
        val jerkZ = zSamples.zipWithNext { a, b ->
            ((b - a) / 0.02f).absoluteValue
        }.maxOrNull() ?: 0f

        val features = SensorFeatures(
            peakAccelZ = peakZ,
            rmsAccelZ = rmsZ,
            varianceAccelZ = varianceZ,
            jerkZ = jerkZ,
            peakGyroX = 0f,
            peakGyroY = 0f,
            rmsGyro = 0f,
            speedKmh = speedKmh,
            windowSizeMs = zSamples.size * 20
        )
        return classify(features)
    }
}

/**
 * SensorWindowProcessor
 *
 * Maintains a sliding window of MPU6050 telemetry packets.
 * Applies a simple moving-average low-pass filter to reduce noise.
 * Extracts the feature vector when enough samples have accumulated.
 *
 * Window size = 500 ms (50 samples at 100 ms intervals).
 * Overlap = 50% — each new window advances by 250 ms.
 */
class SensorWindowProcessor(
    private val windowSizeMs  : Int = 500,
    private val sampleInterval: Int = 100,
    private val lpfAlpha      : Float = 0.3f  // Low-pass filter coefficient
) {
    private val windowSize = windowSizeMs / sampleInterval  // 5 samples
    private val samples = ArrayDeque<SensorTelemetry>(windowSize * 2)

    // Filtered acceleration values
    private var filtZ = 9.81f

    fun addSample(telemetry: SensorTelemetry): SensorFeatures? {
        if (!SensorTelemetry.isValidPacket(telemetry)) return null

        // Apply low-pass filter to vertical acceleration
        filtZ = lpfAlpha * telemetry.accelZ + (1f - lpfAlpha) * filtZ

        samples.addLast(telemetry)

        // Keep only the last window
        while (samples.size > windowSize) {
            samples.removeFirst()
        }

        // Extract features only when we have a full window
        if (samples.size < windowSize) return null

        return extractFeatures()
    }

    private fun extractFeatures(): SensorFeatures {
        val zValues    = samples.map { it.accelZ }
        val gyroXVals  = samples.map { it.gyroX }
        val gyroYVals  = samples.map { it.gyroY }
        val gyroZVals  = samples.map { it.gyroZ }

        val meanZ      = zValues.average().toFloat()
        val varianceZ  = zValues.map { (it - meanZ).pow(2) }.average().toFloat()
        val rmsZ       = sqrt(zValues.map { it.pow(2) }.average().toFloat())
        val peakZ      = zValues.maxOf { it.absoluteValue.toFloat() }

        // Jerk = max rate of change between consecutive samples
        val jerkZ = zValues.zipWithNext { a, b ->
            ((b - a) / (sampleInterval / 1000.0f)).absoluteValue
        }.maxOrNull() ?: 0f

        val rmsGyroX = sqrt(gyroXVals.map { it.pow(2) }.average().toFloat())
        val rmsGyroY = sqrt(gyroYVals.map { it.pow(2) }.average().toFloat())
        val rmsGyroZ = sqrt(gyroZVals.map { it.pow(2) }.average().toFloat())
        val rmsGyro  = sqrt(rmsGyroX.pow(2) + rmsGyroY.pow(2) + rmsGyroZ.pow(2))

        val latestSpeed = samples.lastOrNull()?.speedKmh ?: 0f

        return SensorFeatures(
            peakAccelZ   = peakZ,
            rmsAccelZ    = rmsZ,
            varianceAccelZ = varianceZ,
            jerkZ        = jerkZ,
            peakGyroX    = gyroXVals.maxOf { it.absoluteValue.toFloat() },
            peakGyroY    = gyroYVals.maxOf { it.absoluteValue.toFloat() },
            rmsGyro      = rmsGyro,
            speedKmh     = latestSpeed,
            windowSizeMs = windowSizeMs
        )
    }

    fun reset() {
        samples.clear()
        filtZ = 9.81f
    }

    private fun Float.pow(n: Int) = this.toDouble().pow(n).toFloat()
    private fun Float.pow(n: Float) = this.toDouble().pow(n).toFloat()
    private fun Double.pow(n: Int)  = Math.pow(this, n.toDouble())
    private fun Double.pow(n: Float) = Math.pow(this, n.toDouble())
}

/**
 * EventDeduplicator
 *
 * Prevents the same physical hazard from being recorded multiple times
 * when the vehicle drives over it slowly (generating multiple windows).
 *
 * Logic: After a hazard is confirmed, suppress additional hazard
 * detections within the same geographic proximity for a cooldown period.
 */
class EventDeduplicator(
    private val cooldownMs: Long = 5_000,           // 5 seconds
    private val proximityMeters: Double = 20.0       // 20 m deduplication radius
) {
    private data class RecentEvent(
        val lat: Double,
        val lon: Double,
        val timestamp: Long
    )

    private val recent = mutableListOf<RecentEvent>()

    fun isDuplicate(lat: Double, lon: Double, nowMs: Long = System.currentTimeMillis()): Boolean {
        cleanExpired(nowMs)
        return recent.any { event ->
            haversineMeters(lat, lon, event.lat, event.lon) <= proximityMeters
        }
    }

    fun record(lat: Double, lon: Double, nowMs: Long = System.currentTimeMillis()) {
        cleanExpired(nowMs)
        recent.add(RecentEvent(lat, lon, nowMs))
    }

    private fun cleanExpired(nowMs: Long) {
        recent.removeAll { nowMs - it.timestamp > cooldownMs }
    }

    fun reset() = recent.clear()
}

// ============================================================
// Geodesic helpers
// ============================================================

/**
 * Haversine great-circle distance in metres.
 * Matches the SQL function in migration 0006.
 */
fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val toRad = Math::toRadians
    val dlat = toRad(lat2 - lat1)
    val dlon = toRad(lon2 - lon1)
    val a = sin(dlat / 2).pow(2) +
            cos(toRad(lat1)) * cos(toRad(lat2)) * sin(dlon / 2).pow(2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}

private fun Double.pow(n: Int): Double = Math.pow(this, n.toDouble())
