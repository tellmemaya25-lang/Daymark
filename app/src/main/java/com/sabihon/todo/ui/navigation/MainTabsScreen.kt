package com.sabihon.todo.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.sabihon.todo.core.ui.components.RoundedBottomNavBar
import com.sabihon.todo.ui.history.HistoryScreen
import com.sabihon.todo.ui.home.HomeScreen
import com.sabihon.todo.ui.projects.ProjectsScreen
import com.sabihon.todo.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

/**
 * Mailbox for "open this tab" requests coming from screens outside the pager
 * (Search, a project page, the profile screen …).
 *
 * Kept as Compose snapshot state so a request that arrives while the pager is
 * already on screen still lands, instead of being swallowed on first composition.
 */
object MainTabsRequest {
    var pendingPage by mutableStateOf<Int?>(null)
        private set

    fun open(page: Int) {
        pendingPage = page
    }

    fun consume() {
        pendingPage = null
    }
}

/**
 * Which tab was last shown, kept outside the composition.
 *
 * Profile is reachable from all four tabs, and it is a full navigation destination:
 * coming back to Route.Home recomposes this screen. The pager's own saveable state
 * usually restores the page, but "usually" is what made pressing back from Profile
 * land on Home even when it was opened from Projects or Settings. This is the
 * fallback, and it is authoritative because it is written on every page change.
 */
object MainTabsState {
    // A delegated property cannot carry accessor visibility, so the setter is a
    // function rather than `internal set`.
    private var current by mutableIntStateOf(0)

    val lastPage: Int get() = current

    fun record(page: Int) {
        current = page
    }
}

/** The four top-level destinations, in swipe order. */
private data class MainTab(
    val key: String,
    /** Substring the bottom bar matches to decide which tab is selected. */
    val matchToken: String
)

/** Index of the Projects page, used by the "+" so the constant has one home. */
private const val PROJECTS_PAGE = 1

private val MAIN_TABS = listOf(
    MainTab("home", "Home"),
    MainTab("projects", "Projects"),
    MainTab("activity", "History"),
    MainTab("settings", "Settings")
)

/**
 * The tab host: a real [HorizontalPager], so swiping **reveals the next page as the
 * finger moves** rather than sliding a blank screen and loading it afterwards.
 *
 * Why this replaced the slide-and-navigate approach: navigation-based tab switching
 * can only animate a transition *after* the new destination exists, which is exactly
 * why it flashed white — the incoming screen had not composed or loaded yet. A pager
 * composes its neighbours up front ([beyondViewportPageCount]) and keeps each page's
 * scroll state through a [rememberSaveableStateHolder], so the adjacent page is
 * already drawn before the gesture starts.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainTabsScreen(
    navController: NavHostController
) {
    val requestedPage = MainTabsRequest.pendingPage
    // A pending request wins; otherwise come back to whichever tab was showing, so
    // returning from Profile or Search restores the tab it was opened from.
    val pagerState = rememberPagerState(initialPage = requestedPage ?: MainTabsState.lastPage) {
        MAIN_TABS.size
    }
    val scope = rememberCoroutineScope()
    val saveableStateHolder = rememberSaveableStateHolder()

    // Remember the tab for the next time this screen is rebuilt - the trip to
    // Profile and back goes through Route.Home, which recomposes everything here.
    LaunchedEffect(pagerState.currentPage) {
        MainTabsState.record(pagerState.currentPage)
    }

    // A page request from another screen (or a repeat request while we're mounted).
    LaunchedEffect(requestedPage) {
        if (requestedPage != null && requestedPage in MAIN_TABS.indices) {
            pagerState.animateScrollToPage(requestedPage)
            MainTabsRequest.consume()
        }
    }

    /**
     * Bumped when the bar's "+" is tapped while Projects is showing.
     *
     * On every other tab the "+" opens a new task. On Projects the thing you are looking
     * at is a list of projects, so "+" creates a project - and because the dialog lives
     * inside [ProjectsScreen], the tap is handed down as a counter rather than a route.
     *
     * The screen resets it to zero once it has opened the dialog. Without that, swiping
     * away and back would re-enter composition with the old value still set and reopen
     * the dialog on its own.
     */
    var addProjectRequest by remember { mutableIntStateOf(0) }

    // Which tab the drag is heading towards, for the bar's pre-highlight.
    val previewTab by remember {
        derivedStateOf {
            val page = pagerState.currentPage
            when {
                pagerState.currentPageOffsetFraction > 0.12f -> MAIN_TABS.getOrNull(page + 1)?.key
                pagerState.currentPageOffsetFraction < -0.12f -> MAIN_TABS.getOrNull(page - 1)?.key
                else -> null
            }
        }
    }

    // Back returns to the first tab before leaving the app.
    BackHandler(enabled = pagerState.currentPage != 0) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    fun goToPage(index: Int) {
        if (index in MAIN_TABS.indices && index != pagerState.currentPage) {
            scope.launch { pagerState.animateScrollToPage(index) }
        }
    }

    Scaffold(
        bottomBar = {
            RoundedBottomNavBar(
                currentRoute = MAIN_TABS[pagerState.currentPage].matchToken,
                onNavigate = { key ->
                    when (key) {
                        "add" -> {
                            if (pagerState.currentPage == PROJECTS_PAGE) {
                                addProjectRequest++
                            } else {
                                navController.navigate(Route.AddEditTask())
                            }
                        }
                        else -> goToPage(MAIN_TABS.indexOfFirst { it.key == key })
                    }
                },
                previewTab = previewTab
            )
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            // Compose the neighbours ahead of time – this is what removes the white
            // gap when the gesture starts.
            beyondViewportPageCount = 1
        ) { page ->
            // Restores each page's scroll position without keeping four lists around.
            saveableStateHolder.SaveableStateProvider(MAIN_TABS[page].key) {
                when (page) {
                    0 -> HomeScreen(
                        onNavigateToCategories = { navController.navigate(Route.Categories) },
                        onNavigateToTaskDetail = { navController.navigate(Route.AddEditTask(it)) },
                        onNavigateToEditTask = { navController.navigate(Route.AddEditTask(it)) },
                        onNavigateToAddTask = { navController.navigate(Route.AddEditTask()) },
                        onNavigateToSearch = { navController.navigate(Route.Search) },
                        onNavigateToHistory = { goToPage(2) },
                        onNavigateToProfile = { navController.navigate(Route.Profile) },
                        onNavigateToSettings = { goToPage(3) },
                        onNavigateToCategory = { navController.navigate(Route.CategoryDetail(it)) },
                        onNavigateToAddTaskPrefilled = { candidate ->
                            navController.navigate(
                                Route.AddEditTask(
                                    initialTitle = candidate.title,
                                    initialCategoryId = candidate.categoryId,
                                    initialPriority = candidate.priority.name,
                                    initialDueAt = candidate.dueOffsetHours?.let {
                                        System.currentTimeMillis() + it * 3_600_000L
                                    } ?: -1L
                                )
                            )
                        }
                    )

                    PROJECTS_PAGE -> ProjectsScreen(
                        onProjectClick = { navController.navigate(Route.CategoryDetail(it)) },
                        onProfileClick = { navController.navigate(Route.Profile) },
                        onAllTasksClick = { navController.navigate(Route.AllTasks) },
                        addRequest = addProjectRequest,
                        onAddRequestHandled = { addProjectRequest = 0 }
                    )

                    2 -> HistoryScreen(
                        onTaskClick = { navController.navigate(Route.AddEditTask(it)) },
                        onProfileClick = { navController.navigate(Route.Profile) }
                    )

                    3 -> SettingsScreen(
                        onNavigateToHistory = { goToPage(2) },
                        onNavigateToProfile = { navController.navigate(Route.Profile) },
                        onNavigateToAdmin = { navController.navigate(Route.AdminDashboard) }
                    )
                }
            }
        }
    }
}
