package com.roadguard.app.presentation.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roadguard.app.data.local.TripEntity
import com.roadguard.app.data.repository.HazardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryUiState(
    val trips: List<TripEntity> = emptyList(),
    val totalDistanceKm: Double = 42.8,
    val totalPotholes: Int = 18,
    val totalSpeedBreakers: Int = 11,
    val isLoading: Boolean = false
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val hazardRepository: HazardRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        loadTrips()
    }

    private fun loadTrips() {
        val demoTrips = listOf(
            TripEntity(
                id = "trip-001",
                startedAt = System.currentTimeMillis() - 3600000L * 2,
                endedAt = System.currentTimeMillis() - 3600000L,
                distanceMeters = 8400.0,
                durationSeconds = 1840,
                potholesCount = 4,
                speedBreakersCount = 3,
                avgRoughness = 4.2,
                synced = true
            ),
            TripEntity(
                id = "trip-002",
                startedAt = System.currentTimeMillis() - 86400000L,
                endedAt = System.currentTimeMillis() - 86400000L + 2400000L,
                distanceMeters = 14200.0,
                durationSeconds = 2400,
                potholesCount = 7,
                speedBreakersCount = 5,
                avgRoughness = 5.8,
                synced = true
            ),
            TripEntity(
                id = "trip-003",
                startedAt = System.currentTimeMillis() - 172800000L,
                endedAt = System.currentTimeMillis() - 172800000L + 3100000L,
                distanceMeters = 20200.0,
                durationSeconds = 3100,
                potholesCount = 7,
                speedBreakersCount = 3,
                avgRoughness = 6.1,
                synced = false
            )
        )

        _uiState.update {
            it.copy(
                trips = demoTrips,
                totalDistanceKm = demoTrips.sumOf { t -> t.distanceMeters } / 1000.0,
                totalPotholes = demoTrips.sumOf { t -> t.potholesCount },
                totalSpeedBreakers = demoTrips.sumOf { t -> t.speedBreakersCount }
            )
        }
    }
}
