package com.sabihon.todo.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.sabihon.todo.core.ui.components.RoundedBottomNavBar

@Composable
fun MainScaffold(
    navController: NavController,
    content: @Composable (Modifier) -> Unit
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: ""

    // Show bottom bar on main tabs – Home | Search | + | Activity | Settings
    val showBottomBar = currentRoute.contains("Home") || 
                        currentRoute.contains("Search") || 
                        currentRoute.contains("History") || 
                        currentRoute.contains("Profile") || 
                        currentRoute.contains("Settings") ||
                        currentRoute.contains("Categories") ||
                        currentRoute.contains("AllTasks")

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                RoundedBottomNavBar(
                    currentRoute = currentRoute,
                    onNavigate = { route ->
                        when (route) {
                            "home" -> navController.navigate(Route.Home) {
                                popUpTo(Route.Home) { inclusive = false }
                                launchSingleTop = true
                            }
                            "search" -> navController.navigate(Route.Search) {
                                popUpTo(Route.Home) { inclusive = false }
                                launchSingleTop = true
                            }
                            "activity" -> navController.navigate(Route.History) {
                                popUpTo(Route.Home) { inclusive = false }
                                launchSingleTop = true
                            }
                            "settings" -> navController.navigate(Route.Settings) {
                                popUpTo(Route.Home) { inclusive = false }
                                launchSingleTop = true
                            }
                            "add" -> navController.navigate(Route.AddEditTask())
                        }
                    }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            content(Modifier)
        }
    }
}
