package com.roadguard.app.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roadguard.app.presentation.theme.CyanAccent
import com.roadguard.app.presentation.theme.PotholeRed
import com.roadguard.app.presentation.theme.SpeedBreakerAmber

@Composable
fun SpeedometerGauge(
    speedKmh: Float,
    maxSpeed: Float = 120f,
    modifier: Modifier = Modifier
) {
    val animatedSpeed by animateFloatAsState(targetValue = speedKmh.coerceIn(0f, maxSpeed), label = "speed")
    val sweepFraction = (animatedSpeed / maxSpeed).coerceIn(0f, 1f)

    Box(
        modifier = modifier.size(160.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            val strokeWidth = 14.dp.toPx()
            val startAngle = 140f
            val totalSweep = 260f

            // Background track
            drawArc(
                color = Color(0xFF1E283E),
                startAngle = startAngle,
                sweepAngle = totalSweep,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                size = Size(size.width, size.height)
            )

            // Active speed gradient arc
            val gradientBrush = Brush.sweepGradient(
                0.0f to CyanAccent,
                0.6f to SpeedBreakerAmber,
                1.0f to PotholeRed,
                center = Offset(size.width / 2, size.height / 2)
            )

            drawArc(
                brush = gradientBrush,
                startAngle = startAngle,
                sweepAngle = totalSweep * sweepFraction,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                size = Size(size.width, size.height)
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${animatedSpeed.toInt()}",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Black
                ),
                color = Color.White
            )
            Text(
                text = "km/h",
                style = MaterialTheme.typography.labelSmall,
                color = CyanAccent
            )
        }
    }
}
