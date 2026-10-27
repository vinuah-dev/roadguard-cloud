package com.roadguard.app.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roadguard.app.domain.model.RoadCondition
import com.roadguard.app.presentation.theme.*

@Composable
fun RoadConditionCard(
    condition: RoadCondition,
    confidence: Float,
    modifier: Modifier = Modifier
) {
    val (bgColor, accentColor, glowColor, label, icon) = when (condition) {
        RoadCondition.POTHOLE -> Quintuple(
            Color(0xFF2A1417), PotholeRed, PotholeRedGlow, "POTHOLE DETECTED", Icons.Default.Dangerous
        )
        RoadCondition.SPEED_BREAKER -> Quintuple(
            Color(0xFF2A1E10), SpeedBreakerAmber, SpeedBreakerAmberGlow, "SPEED BREAKER", Icons.Default.Warning
        )
        RoadCondition.NORMAL -> Quintuple(
            Color(0xFF102318), NormalGreen, NormalGreenGlow, "ROAD SMOOTH / NORMAL", Icons.Default.CheckCircle
        )
    }

    val animatedAccent by animateColorAsState(targetValue = accentColor, label = "accent")

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .border(2.dp, animatedAccent, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(glowColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = animatedAccent,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "SURFACE CLASSIFICATION",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        ),
                        color = Color.White
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "CONFIDENCE",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Text(
                    text = "${(confidence * 100).toInt()}%",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = animatedAccent
                )
            }
        }
    }
}

private data class Quintuple<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E
)
