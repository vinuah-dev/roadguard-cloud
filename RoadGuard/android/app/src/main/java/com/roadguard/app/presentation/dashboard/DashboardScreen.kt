package com.roadguard.app.presentation.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roadguard.app.domain.model.HazardType
import com.roadguard.app.presentation.components.*
import com.roadguard.app.presentation.theme.*

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "ROADGUARD HUD",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    ),
                    color = Color.White
                )
                Text(
                    text = "Real-time Edge Classification",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanAccent
                )
            }

            BleStatusBadge(
                state = state.bleState,
                isDemoMode = state.isDemoMode
            )
        }

        // Warning Alert Banner (Proximity alert)
        AlertBanner(
            alert = state.activeAlert,
            onDismiss = { viewModel.dismissAlert() }
        )

        // Road Condition HUD Card
        RoadConditionCard(
            condition = state.roadCondition,
            confidence = state.confidence
        )

        // Center Speedometer & Quick stats
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpeedometerGauge(
                speedKmh = state.speedKmh,
                modifier = Modifier.weight(1.1f)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatCard(
                    title = "POTHOLES",
                    value = "${state.potholesCount}",
                    accentColor = PotholeRed
                )
                StatCard(
                    title = "SPEED BREAKERS",
                    value = "${state.speedBreakersCount}",
                    accentColor = SpeedBreakerAmber
                )
                StatCard(
                    title = "TRIP DISTANCE",
                    value = String.format("%.2f km", state.tripDistanceMeters / 1000.0),
                    accentColor = CyanAccent
                )
            }
        }

        // Realtime Roughness Graph
        RoughnessGraph(samples = state.samples)

        // Controls Card
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Trip Monitoring Engine",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )

                    Button(
                        onClick = {
                            if (state.isMonitoring) viewModel.stopMonitoring()
                            else viewModel.startMonitoring()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.isMonitoring) PotholeRed else CyanAccent,
                            contentColor = DarkBackground
                        )
                    ) {
                        Icon(
                            imageVector = if (state.isMonitoring) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (state.isMonitoring) "STOP" else "START TRIP",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Demo Simulation Mode",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Switch(
                        checked = state.isDemoMode,
                        onCheckedChange = { viewModel.toggleDemoMode(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CyanAccent,
                            checkedTrackColor = SurfaceCardElevated
                        )
                    )
                }

                Divider(color = Color(0xFF263554), thickness = 1.dp)

                Text(
                    text = "Interactive Test Simulators (Academic Presentation)",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.triggerTestHazard(HazardType.POTHOLE) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PotholeRed),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Dangerous, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Test Pothole", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = { viewModel.triggerTestHazard(HazardType.SPEED_BREAKER) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SpeedBreakerAmber),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Test Breaker", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    accentColor: Color
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceCardElevated)
            .padding(vertical = 8.dp, horizontal = 12.dp)
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = TextSecondary
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = accentColor
            )
        }
    }
}
