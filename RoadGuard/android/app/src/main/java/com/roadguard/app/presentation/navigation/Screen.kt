package com.roadguard.app.presentation.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "Live HUD", Icons.Default.Dashboard)
    object Map : Screen("map", "Map", Icons.Default.Map)
    object History : Screen("history", "Trips", Icons.Default.History)
    object Analytics : Screen("analytics", "Analytics", Icons.Default.Analytics)
    object Community : Screen("community", "Community", Icons.Default.People)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)

    companion object {
        val bottomNavItems = listOf(
            Dashboard,
            Map,
            History,
            Analytics,
            Community,
            Settings
        )
    }
}
