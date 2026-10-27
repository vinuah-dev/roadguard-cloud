package com.roadguard.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ============================================================
// Room Database for offline hazard caching and sync queuing
// ============================================================

// ---- Entity: Cached Hazard ----
@Entity(tableName = "cached_hazards")
data class CachedHazardEntity(
    @PrimaryKey val id: String,
    val latitude: Double,
    val longitude: Double,
    val hazardType: String,           // "POTHOLE" | "SPEED_BREAKER"
    val severity: String,
    val verificationStatus: String,
    val confidence: Float?,
    val observationCount: Int,
    val firstDetectedAt: String,
    val lastDetectedAt: String,
    val source: String,
    val isActive: Boolean,
    val isDemo: Boolean,
    val syncedAt: Long = System.currentTimeMillis()  // when this record was cached locally
)

// ---- Entity: Pending Sync Operation ----
@Entity(
    tableName = "pending_sync_ops",
    indices = [Index(value = ["operationId"], unique = true)]
)
data class PendingSyncOpEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val operationId: String,          // UUID v4 — stable idempotency key
    val operationType: String,        // "HAZARD_OBSERVATION" | "TRIP_CREATE" | etc.
    val payload: String,              // JSON payload
    val createdAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val lastAttemptAt: Long = 0,
    val status: String = "PENDING"    // "PENDING" | "FAILED" | "SENT"
)

// ---- Entity: Local Trip ----
@Entity(tableName = "local_trips")
data class LocalTripEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val deviceId: String?,
    val status: String,
    val startedAt: Long,
    val endedAt: Long?,
    val startLatitude: Double?,
    val startLongitude: Double?,
    val endLatitude: Double?,
    val endLongitude: Double?,
    val distanceMeters: Double?,
    val hazardCount: Int,
    val createdAt: Long = System.currentTimeMillis()
)

// ============================================================
// DAOs
// ============================================================

@Dao
interface HazardDao {

    @Query("SELECT * FROM cached_hazards WHERE isActive = 1 AND verificationStatus != 'REJECTED'")
    fun observeAllActive(): Flow<List<CachedHazardEntity>>

    @Query("SELECT * FROM cached_hazards WHERE isActive = 1 AND verificationStatus != 'REJECTED'")
    suspend fun getAllActive(): List<CachedHazardEntity>

    /**
     * Return hazards within a bounding box for proximity checking.
     * Fast O(n) scan — adequate for <10,000 cached records.
     */
    @Query("""
        SELECT * FROM cached_hazards
        WHERE isActive = 1
          AND verificationStatus != 'REJECTED'
          AND latitude  BETWEEN :minLat AND :maxLat
          AND longitude BETWEEN :minLon AND :maxLon
    """)
    suspend fun getInBoundingBox(
        minLat: Double, maxLat: Double,
        minLon: Double, maxLon: Double
    ): List<CachedHazardEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(hazards: List<CachedHazardEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(hazard: CachedHazardEntity)

    @Query("DELETE FROM cached_hazards WHERE isDemo = 0")
    suspend fun clearSyncedHazards()

    @Query("SELECT COUNT(*) FROM cached_hazards WHERE isActive = 1 AND verificationStatus != 'REJECTED'")
    suspend fun countActive(): Int

    @Query("SELECT * FROM cached_hazards WHERE id = :id")
    suspend fun getById(id: String): CachedHazardEntity?
}

@Dao
interface SyncQueueDao {

    @Query("SELECT * FROM pending_sync_ops WHERE status = 'PENDING' ORDER BY createdAt ASC LIMIT 50")
    suspend fun getPending(): List<PendingSyncOpEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(op: PendingSyncOpEntity): Long

    @Query("UPDATE pending_sync_ops SET status = 'SENT' WHERE operationId = :operationId")
    suspend fun markSent(operationId: String)

    @Query("UPDATE pending_sync_ops SET status = 'FAILED', retryCount = retryCount + 1, lastAttemptAt = :now WHERE operationId = :operationId")
    suspend fun markFailed(operationId: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM pending_sync_ops WHERE status = 'SENT' AND createdAt < :cutoff")
    suspend fun cleanSent(cutoff: Long)

    @Query("SELECT COUNT(*) FROM pending_sync_ops WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>
}

@Dao
interface TripDao {
    @Query("SELECT * FROM local_trips ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<LocalTripEntity>>

    @Query("SELECT * FROM local_trips WHERE status = 'ACTIVE' LIMIT 1")
    suspend fun getActiveTrip(): LocalTripEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(trip: LocalTripEntity)

    @Query("UPDATE local_trips SET status = :status, endedAt = :endedAt, distanceMeters = :dist WHERE id = :id")
    suspend fun updateTripCompletion(id: String, status: String, endedAt: Long, dist: Double?)

    @Query("UPDATE local_trips SET hazardCount = hazardCount + 1 WHERE id = :id")
    suspend fun incrementHazardCount(id: String)
}

// ============================================================
// Room Database
// ============================================================

@Database(
    entities = [CachedHazardEntity::class, PendingSyncOpEntity::class, LocalTripEntity::class],
    version  = 1,
    exportSchema = true
)
abstract class RoadGuardDatabase : RoomDatabase() {
    abstract fun hazardDao(): HazardDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun tripDao(): TripDao

    companion object {
        const val DATABASE_NAME = "roadguard_local.db"
    }
}
