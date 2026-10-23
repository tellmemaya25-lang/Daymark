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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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

    // Tab the drag is heading towards; the bar lights it up before the commit.
    var swipePreview by remember { mutableStateOf<String?>(null) }

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
                    },
                    previewTab = swipePreview
                )
            }
        }
    ) { padding ->
        val density = LocalDensity.current
        val haptics = LocalHapticFeedback.current
        val scope = rememberCoroutineScope()

        val drag = remember { Animatable(0f) }
        // 25% of a typical phone width – and a deliberate flick counts too.
        val commitPx = with(density) { 96.dp.toPx() }
        val edgePx = with(density) { 24.dp.toPx() }
        val velocityCommit = with(density) { 800.dp.toPx() }   // px/s
        val overscrollMax = with(density) { 40.dp.toPx() }
        val elastic = 0.35f
        val rawMax = commitPx + overscrollMax / elastic

        var edgeBlocked by remember { mutableStateOf(false) }
        var velocity by remember { mutableStateOf(0f) }
        var lastEventTime by remember { mutableStateOf(0L) }

        Box(
            modifier = Modifier
                .padding(padding)
                .pointerInput(currentTabIndex) {
                    if (currentTabIndex < 0) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { startOffset ->
                            // Leave the screen edges to the system back gesture.
                            edgeBlocked = startOffset.x < edgePx || startOffset.x > size.width - edgePx
                            velocity = 0f
                            lastEventTime = 0L
                        },
                        onDragEnd = {
                            swipePreview = null
                            val travelled = drag.value
                            val flick = velocity
                            val target = when {
                                travelled <= -commitPx || flick <= -velocityCommit ->
                                    TAB_ORDER.getOrNull(currentTabIndex + 1)
                                travelled >= commitPx || flick >= velocityCommit ->
                                    TAB_ORDER.getOrNull(currentTabIndex - 1)
                                else -> null
                            }
                            if (target != null) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                // No snap-back: the navigation transition carries the motion.
                                navigateToTab(target)
                            } else {
                                scope.launch {
                                    drag.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                }
                            }
                        },
                        onDragCancel = {
                            swipePreview = null
                            scope.launch { drag.animateTo(0f) }
                        },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            if (!edgeBlocked) {
                                val now = change.uptimeMillis
                                if (lastEventTime != 0L) {
                                    val dt = (now - lastEventTime).coerceAtLeast(1L)
                                    val instant = amount / dt * 1000f
                                    velocity = velocity * 0.7f + instant * 0.3f
                                }
                                lastEventTime = now
                                val next = (drag.value + amount).coerceIn(-rawMax, rawMax)
                                scope.launch { drag.snapTo(next) }
                                swipePreview = when {
                                    next <= -commitPx * 0.35f -> TAB_ORDER.getOrNull(currentTabIndex + 1)
                                    next >= commitPx * 0.35f -> TAB_ORDER.getOrNull(currentTabIndex - 1)
                                    else -> null
                                }
                            }
                        }
                    )
                }
        ) {
            Box(
                modifier = Modifier.graphicsLayer {
                    translationX = rubberBand(drag.value, commitPx, elastic, overscrollMax)
                }
            ) {
                content(Modifier)
            }
        }
    }
}

/** Compresses travel past [limit] so the page gives instead of stopping dead. */
private fun rubberBand(value: Float, limit: Float, factor: Float, max: Float): Float {
    val magnitude = kotlin.math.abs(value)
    if (magnitude <= limit) return value
    val excess = ((magnitude - limit) * factor).coerceAtMost(max)
    return (limit + excess) * kotlin.math.sign(value)
}
