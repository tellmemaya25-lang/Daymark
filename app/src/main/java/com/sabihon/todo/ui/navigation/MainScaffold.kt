package com.sabihon.todo.ui.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.sabihon.todo.core.ui.components.RoundedBottomNavBar
import kotlinx.coroutines.launch

/** Tab order used by both the bar and the swipe gesture. */
private val TAB_ORDER = listOf("home", "projects", "activity", "settings")

private fun tabMatches(key: String, route: String): Boolean = when (key) {
    "home" -> route.contains("Home") || route.contains("AllTasks") || route.contains("Categories")
    "projects" -> route.contains("Projects") || route.contains("Categories")
    "activity" -> route.contains("History") || route.contains("Activity")
    "settings" -> route.contains("Settings") || route.contains("Profile")
    else -> false
}

/**
 * Navigates to a tab. Each branch uses a concrete destination type on purpose:
 * type-safe navigation serialises the declared type, so passing these through a
 * `Route`-typed variable would resolve to the interface instead of the screen.
 */
private fun NavController.switchTab(key: String) {
    when (key) {
        "projects" -> navigate(Route.Projects) {
            popUpTo(Route.Home) { inclusive = false }
            launchSingleTop = true
        }
        "activity" -> navigate(Route.History) {
            popUpTo(Route.Home) { inclusive = false }
            launchSingleTop = true
        }
        "settings" -> navigate(Route.Settings) {
            popUpTo(Route.Home) { inclusive = false }
            launchSingleTop = true
        }
        else -> navigate(Route.Home) {
            popUpTo(Route.Home) { inclusive = false }
            launchSingleTop = true
        }
    }
}

@Composable
fun MainScaffold(
    navController: NavController,
    content: @Composable (Modifier) -> Unit
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: ""

    // Show bottom bar on main tabs – Home | Projects | + | Activity | Settings
    val showBottomBar = currentRoute.contains("Home") ||
        currentRoute.contains("Search") ||
        currentRoute.contains("Projects") ||
        currentRoute.contains("History") ||
        currentRoute.contains("Profile") ||
        currentRoute.contains("Settings") ||
        currentRoute.contains("Categories") ||
        currentRoute.contains("AllTasks")

    val currentTabIndex = TAB_ORDER.indexOfFirst { tabMatches(it, currentRoute) }

    val navigateToTab: (String) -> Unit = { key -> navController.switchTab(key) }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                RoundedBottomNavBar(
                    currentRoute = currentRoute,
                    onNavigate = { route ->
                        when (route) {
                            "home", "projects", "activity", "settings" -> navigateToTab(route)
                            "add" -> navController.navigate(Route.AddEditTask())
                        }
                    }
                )
            }
        }
    ) { padding ->
        val density = LocalDensity.current
        val haptics = LocalHapticFeedback.current
        val scope = rememberCoroutineScope()

        // Live drag position – the content trails the finger a little, then either
        // commits to the next tab or springs back.
        val drag = remember { Animatable(0f) }
        val threshold = with(density) { 64.dp.toPx() }
        val maxDrag = with(density) { 220.dp.toPx() }

        Box(
            modifier = Modifier
                .padding(padding)
                .pointerInput(currentTabIndex) {
                    if (currentTabIndex < 0) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val travelled = drag.value
                            val target = when {
                                travelled <= -threshold && currentTabIndex < TAB_ORDER.lastIndex ->
                                    TAB_ORDER[currentTabIndex + 1]
                                travelled >= threshold && currentTabIndex > 0 ->
                                    TAB_ORDER[currentTabIndex - 1]
                                else -> null
                            }
                            if (target != null) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                navigateToTab(target)
                            }
                            scope.launch {
                                drag.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                            }
                        },
                        onDragCancel = {
                            scope.launch { drag.animateTo(0f) }
                        },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            scope.launch {
                                drag.snapTo((drag.value + amount).coerceIn(-maxDrag, maxDrag))
                            }
                        }
                    )
                }
        ) {
            Box(
                modifier = Modifier.graphicsLayer {
                    // Resist rather than track 1:1, so a swipe feels elastic.
                    translationX = drag.value * 0.18f
                    alpha = 1f - (kotlin.math.abs(drag.value) / maxDrag) * 0.15f
                }
            ) {
                content(Modifier)
            }
        }
    }
}
