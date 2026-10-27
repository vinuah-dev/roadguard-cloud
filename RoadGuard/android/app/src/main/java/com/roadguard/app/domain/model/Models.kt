package com.roadguard.app.domain.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Road hazard classification labels.
 * NORMAL is included here for in-memory classification results only.
 * NORMAL events are NEVER persisted as confirmed hazard records.
 */
enum class RoadEventType {
    NORMAL,
    POTHOLE,
    SPEED_BREAKER;

    val isHazard: Boolean get() = this != NORMAL

    val displayName: String get() = when (this) {
        NORMAL        -> "Normal Road"
        POTHOLE       -> "Pothole"
        SPEED_BREAKER -> "Speed Breaker"
    }
}

enum class HazardSeverity {
    UNKNOWN,
    LOW,
    MEDIUM,
    HIGH;

    val displayName: String get() = when (this) {
        UNKNOWN -> "Unknown"
        LOW     -> "Low"
        MEDIUM  -> "Medium"
        HIGH    -> "High"
    }

    val colorToken: String get() = when (this) {
        UNKNOWN -> "unknown"
        LOW     -> "low"
        MEDIUM  -> "medium"
        HIGH    -> "high"
    }
}

enum class VerificationStatus {
    PENDING,
    VERIFIED,
    DISPUTED,
    REJECTED;

    val displayName: String get() = when (this) {
        PENDING  -> "Pending Review"
        VERIFIED -> "Verified"
        DISPUTED -> "Disputed"
        REJECTED -> "Rejected (False Positive)"
    }
}

enum class HazardSource {
    SENSOR,
    USER_REPORT,
    ADMIN_IMPORT
}

/**
 * A confirmed road hazard stored in the database.
 * Normal road events are never represented as Hazard objects.
 */
@Serializable
data class Hazard(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val hazardType: RoadEventType,       // POTHOLE or SPEED_BREAKER only
    val severity: HazardSeverity,
    val verificationStatus: VerificationStatus,
    val confidence: Float?,
    val observationCount: Int,
    val firstDetectedAt: String,         // ISO-8601
    val lastDetectedAt: String,          // ISO-8601
    val source: HazardSource,
    val isActive: Boolean,
    val isDemo: Boolean = false          // true when loaded from demo/seed data
) {
    init {
        require(hazardType.isHazard) {
            "NORMAL events cannot be stored as hazards. Use RoadEventType.POTHOLE or SPEED_BREAKER."
        }
        require(latitude in -90.0..90.0) { "Invalid latitude: $latitude" }
        require(longitude in -180.0..180.0) { "Invalid longitude: $longitude" }
        confidence?.let { require(it in 0f..1f) { "Confidence must be 0.0–1.0, got: $it" } }
    }
}

/**
 * Result of classifying one sensor window.
 * Used only in-memory; NORMAL results are not saved.
 */
data class ClassificationResult(
    val eventType: RoadEventType,
    val confidence: Float,              // 0.0–1.0
    val severity: HazardSeverity,
    val isBaseline: Boolean,            // true = rule-based; false = ML model
    val features: SensorFeatures
)

/**
 * Feature vector extracted from one MPU6050 sensor window.
 * These are the inputs to the classifier.
 */
data class SensorFeatures(
    val peakAccelZ: Float,             // m/s² — vertical axis
    val rmsAccelZ: Float,              // root-mean-square of vertical accel
    val varianceAccelZ: Float,         // variance of vertical accel
    val jerkZ: Float,                  // rate of change of vertical accel
    val peakGyroX: Float,              // deg/s — roll
    val peakGyroY: Float,              // deg/s — pitch
    val rmsGyro: Float,
    val speedKmh: Float,               // vehicle speed from GPS at event time
    val windowSizeMs: Int              // duration of the sensor window
)

/**
 * Raw telemetry packet received over BLE from the ESP32.
 * Values are validated before use; invalid packets are discarded.
 */
data class SensorTelemetry(
    val deviceId: String,
    val timestamp: Long,               // millis since ESP32 boot
    val accelX: Float,                 // m/s²
    val accelY: Float,
    val accelZ: Float,
    val gyroX: Float,                  // degrees per second
    val gyroY: Float,
    val gyroZ: Float,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Float,
    val gpsFix: Boolean,
    val sensorOk: Boolean
) {
    /** True only when both sensor and GPS are providing valid data. */
    val isFullyValid: Boolean get() = sensorOk && gpsFix

    /** Valid GPS position available. */
    val hasValidGps: Boolean get() = gpsFix &&
            latitude != 0.0 && longitude != 0.0 &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0

    companion object {
        /** Validate all numeric fields are finite and within physical sensor ranges. */
        fun isValidPacket(t: SensorTelemetry): Boolean {
            if (!t.accelX.isFinite() || !t.accelY.isFinite() || !t.accelZ.isFinite()) return false
            if (!t.gyroX.isFinite()  || !t.gyroY.isFinite()  || !t.gyroZ.isFinite())  return false
            if (t.accelZ !in -50f..50f) return false   // ±5g range check
            if (t.speedKmh < 0f || t.speedKmh > 300f) return false
            return true
        }
    }
}

/**
 * A trip monitoring session.
 */
data class Trip(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val deviceId: String?,
    val status: TripStatus,
    val startedAt: String,             // ISO-8601
    val endedAt: String?,
    val startLatitude: Double?,
    val startLongitude: Double?,
    val endLatitude: Double?,
    val endLongitude: Double?,
    val distanceMeters: Double?,       // null when not reliably computed
    val hazardCount: Int = 0
)

enum class TripStatus {
    ACTIVE,
    COMPLETED,
    ABORTED
}

/**
 * User preference settings.
 */
data class UserSettings(
    val userId: String,
    val voiceAlertsEnabled: Boolean    = true,
    val vibrationEnabled: Boolean      = true,
    val potholeAlertsEnabled: Boolean  = true,
    val speedBreakerAlertsEnabled: Boolean = true,
    val alertDistanceMeters: Int       = 150,
    val distanceUnit: DistanceUnit     = DistanceUnit.METERS,
    val sensitivity: DetectionSensitivity = DetectionSensitivity.MEDIUM
) {
    init {
        require(alertDistanceMeters in 30..1000) {
            "Alert distance must be 30–1000 m, got: $alertDistanceMeters"
        }
    }
}

enum class DistanceUnit {
    METERS,
    FEET;
    fun format(meters: Double): String = when (this) {
        METERS -> "${meters.toInt()} m"
        FEET   -> "${(meters * 3.28084).toInt()} ft"
    }
}

enum class DetectionSensitivity {
    LOW,
    MEDIUM,
    HIGH;

    /** Minimum confidence threshold for an event to be saved. */
    val confidenceThreshold: Float get() = when (this) {
        LOW    -> 0.80f
        MEDIUM -> 0.65f
        HIGH   -> 0.50f
    }
}

/**
 * User profile.
 */
data class UserProfile(
    val id: String,
    val displayName: String,
    val avatarUrl: String?,
    val email: String?
)

/**
 * Distance and bearing to a hazard from the current position.
 */
data class HazardProximity(
    val hazard: Hazard,
    val distanceMeters: Double,
    val bearingDegrees: Double
)
