package com.roadguard.app.presentation.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roadguard.app.data.local.HazardEntity
import com.roadguard.app.data.repository.HazardRepository
import com.roadguard.app.domain.model.HazardType
import com.roadguard.app.domain.model.Severity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class HazardFilter { ALL, POTHOLES, SPEED_BREAKERS }

data class MapUiState(
    val hazards: List<HazardEntity> = emptyList(),
    val filteredHazards: List<HazardEntity> = emptyList(),
    val selectedHazard: HazardEntity? = null,
    val activeFilter: HazardFilter = HazardFilter.ALL,
    val userLat: Double = 21.1777,
    val userLng: Double = 79.0617,
    val isSyncing: Boolean = false
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val hazardRepository: HazardRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    init {
        loadHazards()
    }

    fun loadHazards() {
        viewModelScope.launch {
            hazardRepository.observeLocalHazards().collect { list ->
                val hazardsToDisplay = if (list.isNotEmpty()) list else getDemoHazards()
                _uiState.update { current ->
                    current.copy(
                        hazards = hazardsToDisplay,
                        filteredHazards = filterList(hazardsToDisplay, current.activeFilter)
                    )
                }
            }
        }
    }

    fun setFilter(filter: HazardFilter) {
        _uiState.update { current ->
            current.copy(
                activeFilter = filter,
                filteredHazards = filterList(current.hazards, filter)
            )
        }
    }

    fun selectHazard(hazard: HazardEntity?) {
        _uiState.update { it.copy(selectedHazard = hazard) }
    }

    fun syncWithCloud() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            hazardRepository.syncPendingObservations()
            _uiState.update { it.copy(isSyncing = false) }
        }
    }

    private fun filterList(list: List<HazardEntity>, filter: HazardFilter): List<HazardEntity> {
        return when (filter) {
            HazardFilter.ALL -> list
            HazardFilter.POTHOLES -> list.filter { it.hazardType == HazardType.POTHOLE.name }
            HazardFilter.SPEED_BREAKERS -> list.filter { it.hazardType == HazardType.SPEED_BREAKER.name }
        }
    }

    private fun getDemoHazards(): List<HazardEntity> {
        return listOf(
            HazardEntity(
                id = "demo-1",
                hazardType = "POTHOLE",
                latitude = 21.1782,
                longitude = 79.0625,
                severity = "HIGH",
                roughnessScore = 7.8,
                confirmationsCount = 14,
                createdAt = System.currentTimeMillis() - 86400000L
            ),
            HazardEntity(
                id = "demo-2",
                hazardType = "SPEED_BREAKER",
                latitude = 21.1765,
                longitude = 79.0608,
                severity = "MEDIUM",
                roughnessScore = 5.2,
                confirmationsCount = 28,
                createdAt = System.currentTimeMillis() - 172800000L
            ),
            HazardEntity(
                id = "demo-3",
                hazardType = "POTHOLE",
                latitude = 21.1795,
                longitude = 79.0640,
                severity = "CRITICAL",
                roughnessScore = 9.1,
                confirmationsCount = 35,
                createdAt = System.currentTimeMillis() - 43200000L
            ),
            HazardEntity(
                id = "demo-4",
                hazardType = "SPEED_BREAKER",
                latitude = 21.1750,
                longitude = 79.0590,
                severity = "LOW",
                roughnessScore = 3.8,
                confirmationsCount = 8,
                createdAt = System.currentTimeMillis() - 250000000L
            )
        )
    }
}
