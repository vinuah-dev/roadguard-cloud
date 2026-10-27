package com.roadguard.app.data.remote

import com.roadguard.app.BuildConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

// ============================================================
// Supabase Client Singleton
// ============================================================
object SupabaseClient {

    val client by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
            install(Storage)
        }
    }

    val auth      get() = client.auth
    val db        get() = client.postgrest
}

// ============================================================
// Remote DTOs (match Supabase column names via @SerialName)
// ============================================================

@Serializable
data class HazardDto(
    @SerialName("id")                  val id: String,
    @SerialName("latitude")            val latitude: Double,
    @SerialName("longitude")           val longitude: Double,
    @SerialName("hazard_type")         val hazardType: String,
    @SerialName("severity")            val severity: String,
    @SerialName("verification_status") val verificationStatus: String,
    @SerialName("confidence")          val confidence: Float? = null,
    @SerialName("observation_count")   val observationCount: Int = 1,
    @SerialName("first_detected_at")   val firstDetectedAt: String = "",
    @SerialName("last_detected_at")    val lastDetectedAt: String = "",
    @SerialName("source")              val source: String = "SENSOR",
    @SerialName("is_active")           val isActive: Boolean = true
)

@Serializable
data class ProfileDto(
    @SerialName("id")           val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_url")   val avatarUrl: String? = null,
    @SerialName("updated_at")   val updatedAt: String
)

@Serializable
data class TripDto(
    @SerialName("id")              val id: String,
    @SerialName("user_id")         val userId: String,
    @SerialName("device_id")       val deviceId: String? = null,
    @SerialName("status")          val status: String,
    @SerialName("started_at")      val startedAt: String,
    @SerialName("ended_at")        val endedAt: String? = null,
    @SerialName("start_latitude")  val startLatitude: Double? = null,
    @SerialName("start_longitude") val startLongitude: Double? = null,
    @SerialName("end_latitude")    val endLatitude: Double? = null,
    @SerialName("end_longitude")   val endLongitude: Double? = null,
    @SerialName("distance_meters") val distanceMeters: Double? = null,
    @SerialName("created_at")      val createdAt: String
)

@Serializable
data class UserSettingsDto(
    @SerialName("user_id")                      val userId: String,
    @SerialName("voice_alerts_enabled")         val voiceAlertsEnabled: Boolean = true,
    @SerialName("vibration_enabled")            val vibrationEnabled: Boolean = true,
    @SerialName("pothole_alerts_enabled")       val potholeAlertsEnabled: Boolean = true,
    @SerialName("speed_breaker_alerts_enabled") val speedBreakerAlertsEnabled: Boolean = true,
    @SerialName("alert_distance_meters")        val alertDistanceMeters: Int = 150,
    @SerialName("distance_unit")               val distanceUnit: String = "METERS",
    @SerialName("sensitivity")                 val sensitivity: String = "MEDIUM"
)

// ============================================================
// Supabase Repository
// ============================================================

class SupabaseRepository {

    // ---- Auth ----

    suspend fun signUp(email: String, password: String): Result<Unit> = runCatching {
        SupabaseClient.auth.signUpWith(Email) {
            this.email    = email
            this.password = password
        }
    }

    suspend fun signIn(email: String, password: String): Result<Unit> = runCatching {
        SupabaseClient.auth.signInWith(Email) {
            this.email    = email
            this.password = password
        }
    }

    suspend fun signOut(): Result<Unit> = runCatching {
        SupabaseClient.auth.signOut()
    }

    suspend fun sendPasswordResetEmail(email: String): Result<Unit> = runCatching {
        SupabaseClient.auth.resetPasswordForEmail(email)
    }

    fun currentUserId(): String? = SupabaseClient.auth.currentUserOrNull()?.id

    // ---- Profile ----

    suspend fun getProfile(): Result<ProfileDto> = runCatching {
        val userId = requireNotNull(currentUserId()) { "Not authenticated" }
        SupabaseClient.db["profiles"]
            .select(Columns.list("id", "display_name", "avatar_url", "updated_at")) {
                filter { eq("id", userId) }
            }
            .decodeSingle<ProfileDto>()
    }

    suspend fun updateProfile(displayName: String): Result<Unit> = runCatching {
        val userId = requireNotNull(currentUserId()) { "Not authenticated" }
        SupabaseClient.db["profiles"].update(
            mapOf("display_name" to displayName, "updated_at" to "now()")
        ) {
            filter { eq("id", userId) }
        }
    }

    // ---- Hazards ----

    /**
     * Fetch active hazards within a geographic bounding box.
     * Results are paginated — max 200 per request.
     * Private columns (created_by) are excluded.
     */
    suspend fun getHazardsInRegion(
        minLat: Double, maxLat: Double,
        minLon: Double, maxLon: Double,
        limit: Int = 200,
        offset: Int = 0
    ): Result<List<HazardDto>> = runCatching {
        SupabaseClient.db["public_hazard_summary"]
            .select(
                Columns.list(
                    "id", "latitude", "longitude", "hazard_type", "severity",
                    "verification_status", "confidence", "observation_count",
                    "first_detected_at", "last_detected_at", "is_active"
                )
            ) {
                filter {
                    gte("latitude",  minLat)
                    lte("latitude",  maxLat)
                    gte("longitude", minLon)
                    lte("longitude", maxLon)
                    eq("is_active", true)
                    neq("verification_status", "REJECTED")
                }
                order("last_detected_at", Order.DESCENDING)
                range(offset.toLong(), (offset + limit - 1).toLong())
            }
            .decodeList<HazardDto>()
    }

    // ---- Trips ----

    suspend fun createTrip(tripId: String, deviceId: String?, startLat: Double?, startLon: Double?): Result<TripDto> = runCatching {
        val userId = requireNotNull(currentUserId()) { "Not authenticated" }
        SupabaseClient.db["trips"].insert(
            buildJsonObject {
                put("id", tripId)
                put("user_id", userId)
                if (deviceId != null) put("device_id", deviceId) else put("device_id", JsonNull)
                put("status", "ACTIVE")
                put("started_at", "now()")
                if (startLat != null) put("start_latitude", startLat)
                if (startLon != null) put("start_longitude", startLon)
            }
        ).decodeSingle<TripDto>()
    }

    suspend fun completeTrip(
        tripId: String,
        endLat: Double?,
        endLon: Double?,
        distanceMeters: Double?,
        aborted: Boolean = false
    ): Result<Unit> = runCatching {
        SupabaseClient.db["trips"].update(
            buildJsonObject {
                put("status", if (aborted) "ABORTED" else "COMPLETED")
                put("ended_at", "now()")
                if (endLat != null) put("end_latitude", endLat)
                if (endLon != null) put("end_longitude", endLon)
                if (distanceMeters != null) put("distance_meters", distanceMeters)
                    else put("distance_meters", JsonNull)
            }
        ) {
            filter { eq("id", tripId) }
        }
    }

    suspend fun getTripHistory(limit: Int = 20, offset: Int = 0): Result<List<TripDto>> = runCatching {
        val userId = requireNotNull(currentUserId()) { "Not authenticated" }
        SupabaseClient.db["trips"]
            .select {
                filter { eq("user_id", userId) }
                order("started_at", Order.DESCENDING)
                range(offset.toLong(), (offset + limit - 1).toLong())
            }
            .decodeList<TripDto>()
    }

    // ---- Settings ----

    suspend fun getSettings(): Result<UserSettingsDto> = runCatching {
        val userId = requireNotNull(currentUserId()) { "Not authenticated" }
        SupabaseClient.db["user_settings"]
            .select { filter { eq("user_id", userId) } }
            .decodeSingle<UserSettingsDto>()
    }

    suspend fun upsertSettings(settings: UserSettingsDto): Result<Unit> = runCatching {
        val userId = requireNotNull(currentUserId()) { "Not authenticated" }
        SupabaseClient.db["user_settings"].upsert(
            settings.copy(userId = userId)
        )
    }
}
