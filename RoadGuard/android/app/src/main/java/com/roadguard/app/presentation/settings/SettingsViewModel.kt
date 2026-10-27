package com.roadguard.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roadguard.app.domain.model.BleConnectionState
import com.roadguard.app.domain.model.UserPreferences
import com.roadguard.app.sensor.BleManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val preferences: UserPreferences = UserPreferences(),
    val bleState: BleConnectionState = BleConnectionState.DISCONNECTED,
    val connectedDeviceName: String = "RoadGuard-ESP32-A1"
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val bleManager: BleManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            bleManager.connectionState.collect { state ->
                _uiState.update { it.copy(bleState = state) }
            }
        }
    }

    fun setAlertDistance(meters: Float) {
        _uiState.update {
            it.copy(preferences = it.preferences.copy(alertDistanceMeters = meters.toDouble()))
        }
    }

    fun setVoiceAlerts(enabled: Boolean) {
        _uiState.update {
            it.copy(preferences = it.preferences.copy(voiceAlertsEnabled = enabled))
        }
    }

    fun setVibration(enabled: Boolean) {
        _uiState.update {
            it.copy(preferences = it.preferences.copy(vibrationEnabled = enabled))
        }
    }

    fun toggleBleConnection() {
        if (_uiState.value.bleState == BleConnectionState.CONNECTED) {
            bleManager.disconnect()
        } else {
            bleManager.startScan()
        }
    }
}
