package com.roadguard.app.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.roadguard.app.domain.model.BleConnectionState
import com.roadguard.app.presentation.theme.*

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
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
        Text(
            text = "SYSTEM SETTINGS",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            ),
            color = Color.White
        )

        // Hardware & BLE Pairing Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "HARDWARE SENSOR MODULE",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanAccent
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "ESP32 + MPU-6050",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Text(
                            text = if (state.bleState == BleConnectionState.CONNECTED) "Paired: ${state.connectedDeviceName}" else "Status: ${state.bleState.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }

                    Button(
                        onClick = { viewModel.toggleBleConnection() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.bleState == BleConnectionState.CONNECTED) PotholeRed else CyanAccent,
                            contentColor = DarkBackground
                        )
                    ) {
                        Text(if (state.bleState == BleConnectionState.CONNECTED) "Disconnect" else "Scan & Connect")
                    }
                }
            }
        }

        // Alert Thresholds & Audio Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "ALERT PREFERENCES",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanAccent
                )

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Warning Proximity Distance", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                        Text("${state.preferences.alertDistanceMeters.toInt()} meters", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = CyanAccent)
                    }
                    Slider(
                        value = state.preferences.alertDistanceMeters.toFloat(),
                        onValueChange = { viewModel.setAlertDistance(it) },
                        valueRange = 50f..200f,
                        steps = 5,
                        colors = SliderDefaults.colors(
                            thumbColor = CyanAccent,
                            activeTrackColor = CyanAccent
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Voice Audio Warning (TTS)", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                        Text("Speaks warning before hazard arrival", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }
                    Switch(
                        checked = state.preferences.voiceAlertsEnabled,
                        onCheckedChange = { viewModel.setVoiceAlerts(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = CyanAccent)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Haptic Vibration", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                        Text("Vibrate phone during critical warnings", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }
                    Switch(
                        checked = state.preferences.vibrationEnabled,
                        onCheckedChange = { viewModel.setVibration(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = CyanAccent)
                    )
                }
            }
        }

        // Academic Credits & Institution Info Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCardElevated),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "ACADEMIC PROJECT CREDITS",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = CyanAccent
                )

                Text(
                    text = "Smart Pothole & Speed Breaker Detection and Warning System",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )

                Text(
                    text = "Shri Ramdeobaba College of Engineering and Management (Ramdeobaba University)\nDepartment of Computer Science & Engineering | IDEA Lab\nAcademic Year: 2026–2027",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                Divider(color = Color(0xFF263554), thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))

                Text(
                    text = "Project Guide: Dr. Suresh Balpande",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = SpeedBreakerAmber
                )

                Text(
                    text = "Development Team:\n• Bhoomi Magarde (A6-19)\n• Mohammad Ejaz Mehendi (A6-25)\n• Nidhish Dadhe (A6-53)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
            }
        }
    }
}
