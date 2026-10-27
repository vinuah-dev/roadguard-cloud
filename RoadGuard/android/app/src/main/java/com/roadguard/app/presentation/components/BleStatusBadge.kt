package com.roadguard.app.presentation.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.roadguard.app.domain.model.BleConnectionState
import com.roadguard.app.presentation.theme.CyanAccent
import com.roadguard.app.presentation.theme.NormalGreen
import com.roadguard.app.presentation.theme.PotholeRed
import com.roadguard.app.presentation.theme.SpeedBreakerAmber

@Composable
fun BleStatusBadge(
    state: BleConnectionState,
    isDemoMode: Boolean,
    modifier: Modifier = Modifier
) {
    val (statusText, statusColor, icon) = when {
        isDemoMode -> Triple("DEMO MODE", SpeedBreakerAmber, Icons.Default.BluetoothConnected)
        state == BleConnectionState.CONNECTED -> Triple("ESP32 CONNECTED", NormalGreen, Icons.Default.BluetoothConnected)
        state == BleConnectionState.CONNECTING -> Triple("CONNECTING…", CyanAccent, Icons.Default.Bluetooth)
        state == BleConnectionState.SCANNING -> Triple("SCANNING ESP32", CyanAccent, Icons.Default.Bluetooth)
        else -> Triple("DISCONNECTED", PotholeRed, Icons.Default.BluetoothDisabled)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(statusColor.copy(alpha = 0.12f))
            .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (state == BleConnectionState.CONNECTED || isDemoMode) statusColor
                    else statusColor.copy(alpha = pulseAlpha)
                )
        )
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
            imageVector = icon,
            contentDescription = statusText,
            tint = statusColor,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
            color = statusColor
        )
    }
}
