package com.roadguard.app.presentation.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.roadguard.app.presentation.analytics.AnalyticsScreen
import com.roadguard.app.presentation.analytics.AnalyticsViewModel
import com.roadguard.app.presentation.community.CommunityScreen
import com.roadguard.app.presentation.community.CommunityViewModel
import com.roadguard.app.presentation.dashboard.DashboardScreen
import com.roadguard.app.presentation.dashboard.DashboardViewModel
import com.roadguard.app.presentation.history.HistoryScreen
import com.roadguard.app.presentation.history.HistoryViewModel
import com.roadguard.app.presentation.map.MapScreen
import com.roadguard.app.presentation.map.MapViewModel
import com.roadguard.app.presentation.settings.SettingsScreen
import com.roadguard.app.presentation.settings.SettingsViewModel
import com.roadguard.app.presentation.theme.CyanAccent
import com.roadguard.app.presentation.theme.DarkBackground
import com.roadguard.app.presentation.theme.SurfaceCardElevated
import com.roadguard.app.presentation.theme.TextSecondary

@Composable
fun MainScreen() {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            RoadGuardBottomBar(navController = navController)
        },
        containerColor = DarkBackground
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Dashboard.route) {
                val vm: DashboardViewModel = hiltViewModel()
                DashboardScreen(viewModel = vm)
            }
            composable(Screen.Map.route) {
                val vm: MapViewModel = hiltViewModel()
                MapScreen(viewModel = vm)
            }
            composable(Screen.History.route) {
                val vm: HistoryViewModel = hiltViewModel()
                HistoryScreen(viewModel = vm)
            }
            composable(Screen.Analytics.route) {
                val vm: AnalyticsViewModel = hiltViewModel()
                AnalyticsScreen(viewModel = vm)
            }
            composable(Screen.Community.route) {
                val vm: CommunityViewModel = hiltViewModel()
                CommunityScreen(viewModel = vm)
            }
            composable(Screen.Settings.route) {
                val vm: SettingsViewModel = hiltViewModel()
                SettingsScreen(viewModel = vm)
            }
        }
    }
}

@Composable
fun RoadGuardBottomBar(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    NavigationBar(
        containerColor = SurfaceCardElevated,
        tonalElevation = 8.dp
    ) {
        Screen.bottomNavItems.forEach { screen ->
            val isSelected = currentRoute == screen.route
            NavigationBarItem(
                selected = isSelected,
                onClick = {
                    if (currentRoute != screen.route) {
                        navController.navigate(screen.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = {
                    Icon(
                        imageVector = screen.icon,
                        contentDescription = screen.title
                    )
                },
                label = { Text(screen.title) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = DarkBackground,
                    selectedTextColor = CyanAccent,
                    indicatorColor = CyanAccent,
                    unselectedIconColor = TextSecondary,
                    unselectedTextColor = TextSecondary
                )
            )
        }
    }
}
