package com.roadguard.app.presentation.analytics

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class AnalyticsUiState(
    val normalRoadPct: Float = 78.4f,
    val potholePct: Float = 14.2f,
    val speedBreakerPct: Float = 7.4f,
    val avgRoughnessScore: Float = 4.3f,
    val totalHazardsDetected: Int = 142,
    val severityLow: Int = 38,
    val severityMedium: Int = 64,
    val severityHigh: Int = 32,
    val severityCritical: Int = 8
)

@HiltViewModel
class AnalyticsViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(AnalyticsUiState())
    val uiState: StateFlow<AnalyticsUiState> = _uiState.asStateFlow()
}
