package com.roadguard.app.presentation.dashboard

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roadguard.app.data.repository.HazardRepository
import com.roadguard.app.domain.model.*
import com.roadguard.app.sensor.BleManager
import com.roadguard.app.service.MonitoringService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

data class DashboardUiState(
    val roadCondition: RoadCondition = RoadCondition.NORMAL,
    val confidence: Float = 0.95f,
    val speedKmh: Float = 42f,
    val samples: List<Float> = listOf(9.8f, 9.8f, 9.9f, 9.7f, 9.8f, 10.1f, 9.8f),
    val activeAlert: HazardAlert? = null,
    val bleState: BleConnectionState = BleConnectionState.CONNECTED,
    val isDemoMode: Boolean = true,
    val isMonitoring: Boolean = false,
    val potholesCount: Int = 3,
    val speedBreakersCount: Int = 2,
    val tripDistanceMeters: Double = 3450.0
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hazardRepository: HazardRepository,
    private val bleManager: BleManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        // Collect real BLE connection state
        viewModelScope.launch {
            bleManager.connectionState.collect { state ->
                _uiState.update { it.copy(bleState = state) }
            }
        }

        // Live simulation loop for waveform and speed variation
        viewModelScope.launch {
            while (true) {
                delay(150)
                _uiState.update { current ->
                    val jitter = (Random.nextFloat() - 0.5f) * 1.5f
                    val newSample = when (current.roadCondition) {
                        RoadCondition.POTHOLE -> 9.8f + (Random.nextFloat() - 0.5f) * 18.0f
                        RoadCondition.SPEED_BREAKER -> 9.8f + (Random.nextFloat() - 0.5f) * 10.0f
                        RoadCondition.NORMAL -> 9.8f + (Random.nextFloat() - 0.5f) * 1.0f
                    }
                    val newSamples = (current.samples + newSample).takeLast(30)
                    val speedJitter = (current.speedKmh + jitter * 0.2f).coerceIn(20f, 80f)
                    current.copy(samples = newSamples, speedKmh = speedJitter)
                }
            }
        }
    }

    fun startMonitoring() {
        _uiState.update { it.copy(isMonitoring = true) }
        val intent = Intent(context, MonitoringService::class.java).apply {
            action = MonitoringService.ACTION_START_MONITORING
            putExtra(MonitoringService.EXTRA_DEMO_MODE, _uiState.value.isDemoMode)
        }
        context.startForegroundService(intent)
    }

    fun stopMonitoring() {
        _uiState.update { it.copy(isMonitoring = false) }
        val intent = Intent(context, MonitoringService::class.java).apply {
            action = MonitoringService.ACTION_STOP_MONITORING
        }
        context.startService(intent)
    }

    fun toggleDemoMode(enabled: Boolean) {
        _uiState.update { it.copy(isDemoMode = enabled) }
        val intent = Intent(context, MonitoringService::class.java).apply {
            action = MonitoringService.ACTION_TOGGLE_DEMO_MODE
            putExtra(MonitoringService.EXTRA_DEMO_MODE, enabled)
        }
        context.startService(intent)
    }

    fun triggerTestHazard(type: HazardType) {
        viewModelScope.launch {
            val alert = HazardAlert(
                hazardType = type,
                distanceMeters = 85.0,
                severity = Severity.HIGH,
                latitude = 21.1777,
                longitude = 79.0617,
                voiceWarning = if (type == HazardType.POTHOLE)
                    "Caution! Pothole detected ahead. Slow down."
                else
                    "Attention! Speed breaker ahead. Reduce speed."
            )

            val condition = if (type == HazardType.POTHOLE) RoadCondition.POTHOLE else RoadCondition.SPEED_BREAKER
            _uiState.update { current ->
                current.copy(
                    activeAlert = alert,
                    roadCondition = condition,
                    confidence = 0.94f,
                    potholesCount = if (type == HazardType.POTHOLE) current.potholesCount + 1 else current.potholesCount,
                    speedBreakersCount = if (type == HazardType.SPEED_BREAKER) current.speedBreakersCount + 1 else current.speedBreakersCount
                )
            }

            // Auto-revert back to NORMAL condition after 4 seconds
            delay(4000)
            _uiState.update { it.copy(roadCondition = RoadCondition.NORMAL, confidence = 0.98f) }
        }
    }

    fun dismissAlert() {
        _uiState.update { it.copy(activeAlert = null) }
    }
}
