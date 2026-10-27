package com.roadguard.app.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.roadguard.app.presentation.theme.CyanAccent
import com.roadguard.app.presentation.theme.SurfaceCard
import com.roadguard.app.presentation.theme.TextSecondary

@Composable
fun RoughnessGraph(
    samples: List<Float>,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceCard)
            .border(1.dp, Color(0xFF263554), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "ACCELEROMETER Z-AXIS WAVEFORM",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Text(
                    text = "MPU6050 50Hz Realtime",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanAccent
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(70.dp)
            ) {
                val width = size.width
                val height = size.height
                val midY = height / 2

                // Center zero line
                drawLine(
                    color = Color(0xFF22304A),
                    start = Offset(0f, midY),
                    end = Offset(width, midY),
                    strokeWidth = 1.dp.toPx()
                )

                if (samples.size < 2) return@Canvas

                val stepX = width / (samples.size - 1)
                val path = Path()

                samples.forEachIndexed { index, sample ->
                    // Normalize around 9.8 m/s² (1G baseline), mapping ±15 m/s² to height
                    val normalized = (sample - 9.8f) / 15f
                    val y = (midY - (normalized * (height / 2))).coerceIn(0f, height)
                    val x = index * stepX

                    if (index == 0) {
                        path.moveTo(x, y)
                    } else {
                        path.lineTo(x, y)
                    }
                }

                drawPath(
                    path = path,
                    color = CyanAccent,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
    }
}
