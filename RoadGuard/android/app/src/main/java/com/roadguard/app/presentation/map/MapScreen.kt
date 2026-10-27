package com.roadguard.app.presentation.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.viewinterop.AndroidView
import com.roadguard.app.data.local.HazardEntity
import com.roadguard.app.domain.model.HazardType
import com.roadguard.app.presentation.theme.*
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@Composable
fun MapScreen(
    viewModel: MapViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // OSMDroid Map Integration
        AndroidView(
            factory = { context ->
                MapView(context).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(16.0)
                    controller.setCenter(GeoPoint(state.userLat, state.userLng))
                }
            },
            update = { mapView ->
                mapView.overlays.clear()

                // Add Hazard Markers
                state.filteredHazards.forEach { hazard ->
                    val marker = Marker(mapView).apply {
                        position = GeoPoint(hazard.latitude, hazard.longitude)
                        title = "${hazard.hazardType}: Severity ${hazard.severity}"
                        snippet = "Confirmations: ${hazard.confirmationsCount} | Roughness: ${hazard.roughnessScore}"
                        setOnMarkerClickListener { _, _ ->
                            viewModel.selectHazard(hazard)
                            true
                        }
                    }
                    mapView.overlays.add(marker)
                }
                mapView.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )

        // Top Filter Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopCenter)
        ) {
            Surface(
                color = SurfaceCard.copy(alpha = 0.92f),
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = state.activeFilter == HazardFilter.ALL,
                        onClick = { viewModel.setFilter(HazardFilter.ALL) },
                        label = { Text("All (${state.hazards.size})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyanAccent,
                            selectedLabelColor = DarkBackground
                        )
                    )
                    FilterChip(
                        selected = state.activeFilter == HazardFilter.POTHOLES,
                        onClick = { viewModel.setFilter(HazardFilter.POTHOLES) },
                        label = { Text("Potholes") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PotholeRed,
                            selectedLabelColor = Color.White
                        )
                    )
                    FilterChip(
                        selected = state.activeFilter == HazardFilter.SPEED_BREAKERS,
                        onClick = { viewModel.setFilter(HazardFilter.SPEED_BREAKERS) },
                        label = { Text("Speed Breakers") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SpeedBreakerAmber,
                            selectedLabelColor = DarkBackground
                        )
                    )
                }
            }
        }

        // Floating Action Buttons (Sync and Refresh)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = if (state.selectedHazard != null) 220.dp else 24.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FloatingActionButton(
                onClick = { viewModel.syncWithCloud() },
                containerColor = SurfaceCardElevated,
                contentColor = CyanAccent
            ) {
                if (state.isSyncing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = CyanAccent)
                } else {
                    Icon(Icons.Default.CloudSync, contentDescription = "Sync Cloud")
                }
            }
            FloatingActionButton(
                onClick = { viewModel.loadHazards() },
                containerColor = CyanAccent,
                contentColor = DarkBackground
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Center Location")
            }
        }

        // Bottom Selected Hazard Detail Sheet
        state.selectedHazard?.let { hazard ->
            HazardDetailCard(
                hazard = hazard,
                onClose = { viewModel.selectHazard(null) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            )
        }
    }
}

@Composable
private fun HazardDetailCard(
    hazard: HazardEntity,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isPothole = hazard.hazardType == HazardType.POTHOLE.name
    val accentColor = if (isPothole) PotholeRed else SpeedBreakerAmber

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCardElevated),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
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
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = hazard.hazardType.replace('_', ' '),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("COORDINATES", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Text(
                        String.format("%.4f° N, %.4f° E", hazard.latitude, hazard.longitude),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("SEVERITY", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Text(
                        hazard.severity,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = accentColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Verified by ${hazard.confirmationsCount} vehicles",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanAccent
                )
                Button(
                    onClick = { /* Upvote hazard */ },
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.ThumbUp, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Confirm", fontSize = 12.sp)
                }
            }
        }
    }
}
