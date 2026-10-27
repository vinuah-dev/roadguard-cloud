package com.roadguard.app.presentation.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roadguard.app.data.repository.HazardRepository
import com.roadguard.app.domain.model.HazardType
import com.roadguard.app.domain.model.Severity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CommunityReport(
    val id: String,
    val author: String,
    val hazardType: HazardType,
    val severity: Severity,
    val locationDescription: String,
    val upvotes: Int,
    val isUpvoted: Boolean = false,
    val timeAgo: String
)

data class CommunityUiState(
    val reports: List<CommunityReport> = emptyList(),
    val isSubmitting: Boolean = false,
    val showReportDialog: Boolean = false
)

@HiltViewModel
class CommunityViewModel @Inject constructor(
    private val hazardRepository: HazardRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CommunityUiState())
    val uiState: StateFlow<CommunityUiState> = _uiState.asStateFlow()

    init {
        loadCommunityReports()
    }

    private fun loadCommunityReports() {
        val demoReports = listOf(
            CommunityReport(
                id = "rep-1",
                author = "Nidhish D.",
                hazardType = HazardType.POTHOLE,
                severity = Severity.HIGH,
                locationDescription = "Near RCOEM Main Gate, Katol Road",
                upvotes = 24,
                timeAgo = "15m ago"
            ),
            CommunityReport(
                id = "rep-2",
                author = "Bhoomi M.",
                hazardType = HazardType.SPEED_BREAKER,
                severity = Severity.MEDIUM,
                locationDescription = "Opposite Ramdeobaba Temple curve",
                upvotes = 18,
                timeAgo = "1h ago"
            ),
            CommunityReport(
                id = "rep-3",
                author = "Mohammad E.",
                hazardType = HazardType.POTHOLE,
                severity = Severity.CRITICAL,
                locationDescription = "Near Gittikhadan Square outer lane",
                upvotes = 42,
                timeAgo = "3h ago"
            )
        )
        _uiState.update { it.copy(reports = demoReports) }
    }

    fun toggleUpvote(reportId: String) {
        _uiState.update { current ->
            val updated = current.reports.map { report ->
                if (report.id == reportId) {
                    val newUpvoted = !report.isUpvoted
                    val newCount = if (newUpvoted) report.upvotes + 1 else report.upvotes - 1
                    report.copy(isUpvoted = newUpvoted, upvotes = newCount)
                } else report
            }
            current.copy(reports = updated)
        }
    }

    fun submitManualReport(type: HazardType, severity: Severity, description: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true) }
            val newReport = CommunityReport(
                id = "rep-${System.currentTimeMillis()}",
                author = "You",
                hazardType = type,
                severity = severity,
                locationDescription = description.ifBlank { "Katol Road, Nagpur" },
                upvotes = 1,
                isUpvoted = true,
                timeAgo = "Just now"
            )
            _uiState.update {
                it.copy(
                    reports = listOf(newReport) + it.reports,
                    isSubmitting = false,
                    showReportDialog = false
                )
            }
        }
    }

    fun setReportDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(showReportDialog = visible) }
    }
}
