package com.roadguard.app.presentation.community

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roadguard.app.domain.model.HazardType
import com.roadguard.app.domain.model.Severity
import com.roadguard.app.presentation.theme.*

@Composable
fun CommunityScreen(
    viewModel: CommunityViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "COMMUNITY FEED",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        ),
                        color = Color.White
                    )
                    Text(
                        text = "Crowdsourced Road Intelligence",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanAccent
                    )
                }

                Button(
                    onClick = { viewModel.setReportDialogVisible(true) },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = DarkBackground),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.AddLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Report", fontWeight = FontWeight.Bold)
                }
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(state.reports) { report ->
                    CommunityReportCard(
                        report = report,
                        onUpvote = { viewModel.toggleUpvote(report.id) }
                    )
                }
            }
        }

        if (state.showReportDialog) {
            ReportHazardDialog(
                onDismiss = { viewModel.setReportDialogVisible(false) },
                onSubmit = { type, severity, desc ->
                    viewModel.submitManualReport(type, severity, desc)
                }
            )
        }
    }
}

@Composable
private fun CommunityReportCard(
    report: CommunityReport,
    onUpvote: () -> Unit
) {
    val isPothole = report.hazardType == HazardType.POTHOLE
    val accentColor = if (isPothole) PotholeRed else SpeedBreakerAmber

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCardElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPothole) Icons.Default.Dangerous else Icons.Default.Warning,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = report.author,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Text(
                            text = report.timeAgo,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }

                Surface(
                    color = accentColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = report.severity.name,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = accentColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = report.locationDescription,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${report.hazardType.name.replace('_', ' ')} confirmed",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )

                IconButton(onClick = onUpvote) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ThumbUp,
                            contentDescription = "Upvote",
                            tint = if (report.isUpvoted) CyanAccent else TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${report.upvotes}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (report.isUpvoted) CyanAccent else TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportHazardDialog(
    onDismiss: () -> Unit,
    onSubmit: (HazardType, Severity, String) -> Unit
) {
    var selectedType by remember { mutableStateOf(HazardType.POTHOLE) }
    var selectedSeverity by remember { mutableStateOf(Severity.HIGH) }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report Road Hazard", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Select Hazard Type:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedType == HazardType.POTHOLE,
                        onClick = { selectedType = HazardType.POTHOLE },
                        label = { Text("Pothole") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = PotholeRed)
                    )
                    FilterChip(
                        selected = selectedType == HazardType.SPEED_BREAKER,
                        onClick = { selectedType = HazardType.SPEED_BREAKER },
                        label = { Text("Speed Breaker") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = SpeedBreakerAmber)
                    )
                }

                Text("Location Landmark / Description:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    placeholder = { Text("e.g. Near RCOEM Gate 2, deep pothole") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(selectedType, selectedSeverity, description) },
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = DarkBackground)
            ) {
                Text("Submit Report", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
        containerColor = SurfaceCardElevated
    )
}
