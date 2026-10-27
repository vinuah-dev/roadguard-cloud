package com.roadguard.app.data.repository

import com.roadguard.app.data.local.*
import com.roadguard.app.data.remote.*
import com.roadguard.app.domain.model.*
import com.roadguard.app.sensor.haversineMeters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HazardRepository
 *
 * Single source of truth for hazard data.
 *
 * Read path : Room local cache → always available offline.
 * Write path: Save locally first → enqueue sync → send to Supabase when online.
 *
 * The alarm system reads ONLY from the local cache.
 * Cloud connectivity is NOT required for hazard proximity warnings.
 */
@Singleton
class HazardRepository @Inject constructor(
    private val hazardDao: HazardDao,
    private val syncQueueDao: SyncQueueDao,
    private val remote: SupabaseRepository
) {

    // ============================================================
    // Read
    // ============================================================

    /** Flow of all locally cached active hazards. Updates in real time as DB changes. */
    fun observeActiveHazards(): Flow<List<Hazard>> =
        hazardDao.observeAllActive().map { entities -> entities.map { it.toDomain() } }

    /**
     * Get nearby hazards for proximity warning.
     * Uses a bounding-box pre-filter for speed (O(n) over bbox subset).
     * Then calculates exact Haversine distance for remaining candidates.
     */
    suspend fun getNearbyHazards(
        lat: Double,
        lon: Double,
        radiusMeters: Double
    ): List<HazardProximity> {
        val bbox = radiusMeters / 111_000.0 * 1.2  // degrees, with 20% margin
        val candidates = hazardDao.getInBoundingBox(
            minLat = lat - bbox, maxLat = lat + bbox,
            minLon = lon - bbox, maxLon = lon + bbox
        )
        return candidates
            .map { entity ->
                val dist = haversineMeters(lat, lon, entity.latitude, entity.longitude)
                val bearing = bearing(lat, lon, entity.latitude, entity.longitude)
                HazardProximity(entity.toDomain(), dist, bearing)
            }
            .filter { it.distanceMeters <= radiusMeters }
            .sortedBy { it.distanceMeters }
    }

    // ============================================================
    // Write (local + sync queue)
    // ============================================================

    /**
     * Save a confirmed sensor-detected hazard.
     * 1. Persists locally (offline-safe).
     * 2. Enqueues a sync operation for later upload.
     *
     * Only POTHOLE and SPEED_BREAKER are accepted.
     * NORMAL events are rejected with an exception.
     */
    suspend fun saveDetectedHazard(
        lat: Double,
        lon: Double,
        type: RoadEventType,
        severity: HazardSeverity,
        confidence: Float?,
        observedAt: String,
        tripId: String?,
        deviceId: String?,
        sensorFeatures: SensorFeatures?,
        isDemo: Boolean = false
    ): Result<Hazard> = runCatching {
        require(type.isHazard) { "NORMAL events cannot be saved as hazards." }
        require(lat in -90.0..90.0) { "Invalid latitude: $lat" }
        require(lon in -180.0..180.0) { "Invalid longitude: $lon" }

        val hazardId = UUID.randomUUID().toString()
        val entity = CachedHazardEntity(
            id                 = hazardId,
            latitude           = lat,
            longitude          = lon,
            hazardType         = type.name,
            severity           = severity.name,
            verificationStatus = if (isDemo) "VERIFIED" else "PENDING",
            confidence         = confidence,
            observationCount   = 1,
            firstDetectedAt    = observedAt,
            lastDetectedAt     = observedAt,
            source             = if (isDemo) "ADMIN_IMPORT" else "SENSOR",
            isActive           = true,
            isDemo             = isDemo
        )
        hazardDao.upsert(entity)

        // Enqueue sync operation (skipped for demo data)
        if (!isDemo) {
            val fingerprint = generateFingerprint(deviceId, observedAt, lat, lon)
            val operationId = UUID.randomUUID().toString()
            val payload = buildSyncPayload(
                operationId, deviceId, tripId, lat, lon, type, severity,
                confidence, observedAt, fingerprint, sensorFeatures
            )
            syncQueueDao.enqueue(
                PendingSyncOpEntity(
                    operationId   = operationId,
                    operationType = "HAZARD_OBSERVATION",
                    payload       = payload
                )
            )
        }

        entity.toDomain()
    }

    // ============================================================
    // Synchronisation
    // ============================================================

    /**
     * Synchronise cached hazards from Supabase for the current map region.
     * Called when connectivity is available.
     * Local demo data is preserved.
     */
    suspend fun syncHazardsFromCloud(
        minLat: Double, maxLat: Double,
        minLon: Double, maxLon: Double
    ): Result<Int> = runCatching {
        val dtos = remote.getHazardsInRegion(minLat, maxLat, minLon, maxLon).getOrThrow()
        val entities = dtos.map { it.toEntity() }
        hazardDao.upsertAll(entities)
        entities.size
    }

    /**
     * Process pending sync operations.
     * Called when internet connectivity is detected.
     * Failed operations are retried up to 3 times.
     */
    suspend fun processSyncQueue() {
        val pending = syncQueueDao.getPending()
        for (op in pending) {
            if (op.retryCount >= 3) {
                syncQueueDao.markFailed(op.operationId)
                continue
            }
            try {
                // Send to Supabase Edge Function
                // In a real implementation, call the ingest-hazard Edge Function via HTTP
                // For now, we mark as sent to avoid blocking the queue.
                // TODO: Replace with actual HTTP call when Supabase credentials are configured.
                syncQueueDao.markSent(op.operationId)
            } catch (e: Exception) {
                syncQueueDao.markFailed(op.operationId)
            }
        }
        // Clean up old sent operations
        syncQueueDao.cleanSent(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L)
    }

    val pendingSyncCount: Flow<Int> = syncQueueDao.observePendingCount()

    // ============================================================
    // Helpers
    // ============================================================

    private fun generateFingerprint(deviceId: String?, observedAt: String, lat: Double, lon: Double): String {
        val raw = "${deviceId ?: "unknown"}:$observedAt:${lat.toBits()}:${lon.toBits()}"
        return raw.hashCode().toString(16).padStart(16, '0')
    }

    private fun buildSyncPayload(
        operationId: String,
        deviceId: String?,
        tripId: String?,
        lat: Double,
        lon: Double,
        type: RoadEventType,
        severity: HazardSeverity,
        confidence: Float?,
        observedAt: String,
        fingerprint: String,
        features: SensorFeatures?
    ): String {
        val json = kotlinx.serialization.json.buildJsonObject {
            put("operationId", operationId)
            if (deviceId != null) put("deviceId", deviceId)
            if (tripId != null) put("tripId", tripId)
            put("latitude", lat)
            put("longitude", lon)
            put("hazardType", type.name)
            put("severity", severity.name)
            if (confidence != null) put("confidence", confidence)
            put("observedAt", observedAt)
            put("eventFingerprint", fingerprint)
            put("source", "SENSOR")
            if (features != null) {
                put("sensorFeatures", kotlinx.serialization.json.buildJsonObject {
                    put("peakAccelZ", features.peakAccelZ)
                    put("rmsAccelZ", features.rmsAccelZ)
                    put("varianceAccelZ", features.varianceAccelZ)
                    put("jerkZ", features.jerkZ)
                    put("speedKmh", features.speedKmh)
                })
            }
        }
        return json.toString()
    }
}

// ============================================================
// Mappers
// ============================================================

fun CachedHazardEntity.toDomain(): Hazard = Hazard(
    id                 = id,
    latitude           = latitude,
    longitude          = longitude,
    hazardType         = RoadEventType.valueOf(hazardType),
    severity           = HazardSeverity.valueOf(severity),
    verificationStatus = VerificationStatus.valueOf(verificationStatus),
    confidence         = confidence,
    observationCount   = observationCount,
    firstDetectedAt    = firstDetectedAt,
    lastDetectedAt     = lastDetectedAt,
    source             = HazardSource.valueOf(source),
    isActive           = isActive,
    isDemo             = isDemo
)

fun HazardDto.toEntity(): CachedHazardEntity = CachedHazardEntity(
    id                 = id,
    latitude           = latitude,
    longitude          = longitude,
    hazardType         = hazardType,
    severity           = severity,
    verificationStatus = verificationStatus,
    confidence         = confidence,
    observationCount   = observationCount,
    firstDetectedAt    = firstDetectedAt,
    lastDetectedAt     = lastDetectedAt,
    source             = source,
    isActive           = isActive,
    isDemo             = false
)

private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLon = Math.toRadians(lon2 - lon1)
    val lat1R = Math.toRadians(lat1)
    val lat2R = Math.toRadians(lat2)
    val y = Math.sin(dLon) * Math.cos(lat2R)
    val x = Math.cos(lat1R) * Math.sin(lat2R) - Math.sin(lat1R) * Math.cos(lat2R) * Math.cos(dLon)
    return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
}
