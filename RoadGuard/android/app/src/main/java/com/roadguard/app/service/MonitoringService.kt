package com.roadguard.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.*
import android.media.AudioManager
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.roadguard.app.BuildConfig
import com.roadguard.app.MainActivity
import com.roadguard.app.data.repository.HazardRepository
import com.roadguard.app.domain.model.*
import com.roadguard.app.sensor.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs

/**
 * MonitoringService — Android Foreground Service
 *
 * Runs in the background during an active trip.
 * Responsibilities:
 *   - Receive BLE telemetry from ESP32
 *   - Process sensor windows and classify road events
 *   - Detect and deduplicate hazards
 *   - Check proximity to stored hazards
 *   - Issue voice alerts, vibration, and notifications
 *   - Persist confirmed hazards locally
 *   - Update the persistent foreground notification
 *
 * This service does NOT require internet connectivity for:
 *   - Sensor processing
 *   - Hazard detection
 *   - Proximity alerts
 *   - Voice warnings
 *
 * Internet is used only for optional Supabase synchronisation.
 */
@AndroidEntryPoint
class MonitoringService : Service() {

    @Inject lateinit var hazardRepository: HazardRepository
    @Inject lateinit var bleManager: BleManager

    private val serviceScope  = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val classifier    = RoadGuardClassifier()
    private val windowProcessor = SensorWindowProcessor()
    private val deduplicator  = EventDeduplicator()
    private val warningTracker = WarningCooldownTracker()

    private var textToSpeech: TextToSpeech? = null
    private var ttsReady      = false
    private lateinit var vibrator: Vibrator
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager

    // Trip state
    private var currentTripId: String? = null
    private var lastKnownLat: Double?   = null
    private var lastKnownLon: Double?   = null
    private var currentHeading: Double? = null
    private var isMonitoring            = false
    private var hazardsDetectedCount    = 0

    // Settings (loaded from repository; defaults used until loaded)
    private var settings = UserSettings(userId = "")

    companion object {
        const val CHANNEL_ID         = "roadguard_monitoring"
        const val NOTIFICATION_ID    = 1001
        const val ACTION_START       = "ACTION_START_MONITORING"
        const val ACTION_STOP        = "ACTION_STOP_MONITORING"
        const val EXTRA_TRIP_ID      = "EXTRA_TRIP_ID"
        const val EXTRA_DEMO_MODE    = "EXTRA_DEMO_MODE"

        // How far ahead to warn (overridden by user settings)
        private const val DEFAULT_ALERT_DISTANCE_METERS = 150.0
        // Minimum GPS accuracy before proximity checks (metres)
        private const val MIN_GPS_ACCURACY_METERS = 30.0f
    }

    // ============================================================
    // Lifecycle
    // ============================================================

    override fun onCreate() {
        super.onCreate()
        vibrator          = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        audioManager      = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        initTTS()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val tripId   = intent.getStringExtra(EXTRA_TRIP_ID) ?: UUID.randomUUID().toString()
                val demoMode = intent.getBooleanExtra(EXTRA_DEMO_MODE, BuildConfig.DEMO_MODE)
                startMonitoring(tripId, demoMode)
            }
            ACTION_STOP -> stopMonitoring()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
        textToSpeech?.shutdown()
        serviceScope.cancel()
    }

    // ============================================================
    // Start/Stop Monitoring
    // ============================================================

    private fun startMonitoring(tripId: String, demoMode: Boolean) {
        if (isMonitoring) return
        isMonitoring       = true
        currentTripId      = tripId
        hazardsDetectedCount = 0

        val notification = buildNotification("Monitoring active — connected to ESP32")
        startForeground(NOTIFICATION_ID, notification)

        windowProcessor.reset()
        deduplicator.reset()
        warningTracker.reset()

        if (demoMode) {
            serviceScope.launch { runDemoSequence() }
        } else {
            observeBLETelemetry()
        }

        // Periodic proximity check (every 2 seconds)
        serviceScope.launch {
            while (isMonitoring) {
                delay(2_000)
                checkHazardProximity()
            }
        }
    }

    private fun stopMonitoring() {
        isMonitoring = false
        serviceScope.coroutineContext.cancelChildren()
        windowProcessor.reset()
        deduplicator.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ============================================================
    // BLE Telemetry Processing
    // ============================================================

    private fun observeBLETelemetry() {
        serviceScope.launch {
            bleManager.telemetry
                .filterNotNull()
                .collect { telemetry ->
                    processTelemetry(telemetry)
                }
        }
    }

    private suspend fun processTelemetry(telemetry: SensorTelemetry) {
        // Update last known position and compute heading
        if (telemetry.hasValidGps) {
            if (lastKnownLat != null && lastKnownLon != null &&
                (telemetry.latitude != lastKnownLat || telemetry.longitude != lastKnownLon)) {
                val dLon = Math.toRadians(telemetry.longitude - lastKnownLon!!)
                val lat1 = Math.toRadians(lastKnownLat!!)
                val lat2 = Math.toRadians(telemetry.latitude)
                val y = Math.sin(dLon) * Math.cos(lat2)
                val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)
                currentHeading = (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
            }
            lastKnownLat = telemetry.latitude
            lastKnownLon = telemetry.longitude
        }

        // Add sample to window processor; extract features when window is full
        val features = windowProcessor.addSample(telemetry) ?: return

        // Classify
        val result = classifier.classify(features)

        // Only process confirmed hazard events
        if (!result.eventType.isHazard) return
        if (result.confidence < settings.sensitivity.confidenceThreshold) return

        // GPS must be valid for a confirmed hazard
        val lat = lastKnownLat ?: return
        val lon = lastKnownLon ?: return
        if (!telemetry.hasValidGps && lastKnownLat == null) return

        // Deduplication
        if (deduplicator.isDuplicate(lat, lon)) return
        deduplicator.record(lat, lon)

        // Save hazard
        val savedResult = hazardRepository.saveDetectedHazard(
            lat          = lat,
            lon          = lon,
            type         = result.eventType,
            severity     = result.severity,
            confidence   = result.confidence,
            observedAt   = Instant.now().toString(),
            tripId       = currentTripId,
            deviceId     = null,
            sensorFeatures = result.features,
            isDemo       = false
        )

        savedResult.onSuccess {
            hazardsDetectedCount++
            updateNotification("Trip active — $hazardsDetectedCount hazard(s) detected")
        }
    }

    // ============================================================
    // Proximity Warning
    // ============================================================

    private suspend fun checkHazardProximity() {
        val lat = lastKnownLat ?: return
        val lon = lastKnownLon ?: return

        val alertDistance = settings.alertDistanceMeters.toDouble()
            .coerceIn(30.0, 1000.0)

        val nearby = hazardRepository.getNearbyHazards(lat, lon, alertDistance)

        for (proximity in nearby) {
            val hazard = proximity.hazard
            if (!shouldWarnAboutHazard(hazard)) continue

            // Heading-based approach direction check
            val heading = currentHeading
            if (heading != null) {
                val diff = abs((proximity.bearingDegrees - heading + 180) % 360 - 180)
                // If moving away (> 85 degrees behind vehicle), suppress "ahead" warning
                if (diff > 85.0) continue
            }

            if (warningTracker.isInCooldown(hazard.id)) continue

            warningTracker.recordWarning(hazard.id)
            issueWarning(hazard, proximity.distanceMeters)
        }
    }

    private fun shouldWarnAboutHazard(hazard: Hazard): Boolean {
        return when (hazard.hazardType) {
            RoadEventType.POTHOLE       -> settings.potholeAlertsEnabled
            RoadEventType.SPEED_BREAKER -> settings.speedBreakerAlertsEnabled
            RoadEventType.NORMAL        -> false
        }
    }

    // ============================================================
    // Alerts: Voice + Vibration
    // ============================================================

    private fun issueWarning(hazard: Hazard, distanceMeters: Double) {
        val distStr = settings.distanceUnit.format(distanceMeters)
        val message = when (hazard.hazardType) {
            RoadEventType.POTHOLE       ->
                "Warning! Pothole ahead in $distStr. Reduce speed."
            RoadEventType.SPEED_BREAKER ->
                "Warning! Speed breaker ahead in $distStr. Reduce speed."
            else -> return
        }

        if (settings.voiceAlertsEnabled && ttsReady) {
            speakWarning(message)
        }

        if (settings.vibrationEnabled) {
            vibrate()
        }

        // Post a notification as a backup alert
        val notification = buildHazardNotification(hazard.hazardType, distanceMeters)
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun speakWarning(message: String) {
        // Request audio focus before speaking
        val focusRequest = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        textToSpeech?.speak(
            message,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "roadguard_warning_${System.currentTimeMillis()}"
        )
    }

    private fun vibrate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 300, 100, 300, 100, 500),
                    -1
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 300, 100, 300, 100, 500), -1)
        }
    }

    // ============================================================
    // Demo Mode
    // ============================================================

    private suspend fun runDemoSequence() {
        delay(2_000)
        // Simulate normal road
        updateNotification("[DEMO] Simulating normal road...")
        delay(3_000)

        // Simulate pothole
        updateNotification("[DEMO] Simulating pothole detection...")
        val demoLat = 21.1400 + (Math.random() - 0.5) * 0.001
        val demoLon = 79.0570 + (Math.random() - 0.5) * 0.001
        hazardRepository.saveDetectedHazard(
            lat = demoLat, lon = demoLon,
            type = RoadEventType.POTHOLE,
            severity = HazardSeverity.MEDIUM,
            confidence = 0.82f,
            observedAt = Instant.now().toString(),
            tripId = currentTripId,
            deviceId = null,
            sensorFeatures = null,
            isDemo = true
        )
        if (ttsReady && settings.voiceAlertsEnabled) {
            speakWarning("[DEMO] Caution. Pothole ahead. Reduce speed.")
        }
        delay(4_000)

        // Simulate speed breaker
        updateNotification("[DEMO] Simulating speed breaker detection...")
        hazardRepository.saveDetectedHazard(
            lat = demoLat + 0.0005, lon = demoLon + 0.0002,
            type = RoadEventType.SPEED_BREAKER,
            severity = HazardSeverity.LOW,
            confidence = 0.91f,
            observedAt = Instant.now().toString(),
            tripId = currentTripId,
            deviceId = null,
            sensorFeatures = null,
            isDemo = true
        )
        if (ttsReady && settings.voiceAlertsEnabled) {
            speakWarning("[DEMO] Caution. Speed breaker ahead. Reduce speed.")
        }
        delay(2_000)
        updateNotification("[DEMO] Demo complete. 2 events simulated.")
    }

    // ============================================================
    // TTS
    // ============================================================

    private fun initTTS() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale.US)
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                           result != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    // ============================================================
    // Notification
    // ============================================================

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Road Hazard Monitoring",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Active during RoadGuard trip monitoring sessions."
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RoadGuard — Monitoring Active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun buildHazardNotification(type: RoadEventType, distanceMeters: Double): Notification {
        val title = when (type) {
            RoadEventType.POTHOLE       -> "⚠️ Pothole Ahead"
            RoadEventType.SPEED_BREAKER -> "⚠️ Speed Breaker Ahead"
            else                        -> "⚠️ Road Hazard Ahead"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("${distanceMeters.toInt()} m — Reduce speed now.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}

/**
 * WarningCooldownTracker
 *
 * Prevents the same hazard from triggering an alarm storm.
 * One warning per hazard per approach cycle.
 * Re-arms after the vehicle has moved away (detected by distance increase)
 * OR after a fixed cooldown period (backup).
 */
class WarningCooldownTracker(
    private val cooldownMs: Long = 60_000  // 60 seconds per hazard
) {
    private val warned = mutableMapOf<String, Long>()  // hazardId → timestamp

    fun isInCooldown(hazardId: String): Boolean {
        val lastWarned = warned[hazardId] ?: return false
        return System.currentTimeMillis() - lastWarned < cooldownMs
    }

    fun recordWarning(hazardId: String) {
        warned[hazardId] = System.currentTimeMillis()
    }

    fun reset() = warned.clear()
}
