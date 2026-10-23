package com.sabihon.todo.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.toRoute
import com.sabihon.todo.ui.auth.ForgotPasswordScreen
import com.sabihon.todo.ui.auth.LoginScreen
import com.sabihon.todo.ui.auth.SignUpScreen
import com.sabihon.todo.ui.auth.SplashScreen
import com.sabihon.todo.ui.auth.StartDestination
import com.sabihon.todo.ui.onboarding.OnboardingScreen
import com.sabihon.todo.ui.onboarding.OnboardingViewModel
import com.sabihon.todo.ui.categories.CategoriesScreen
import com.sabihon.todo.ui.categories.CategoryDetailScreen
import com.sabihon.todo.ui.history.HistoryScreen
import com.sabihon.todo.ui.home.HomeScreen
import com.sabihon.todo.ui.profile.ProfileScreen
import com.sabihon.todo.ui.projects.ProjectsScreen
import com.sabihon.todo.ui.search.SearchScreen
import com.sabihon.todo.ui.settings.SettingsScreen
import com.sabihon.todo.ui.addedit.AddEditTaskScreen

@Composable
fun SabihonNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: Route = Route.Splash
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable<Route.Splash> {
            SplashScreen(
                onFinished = { destination ->
                    val next: Route = when (destination) {
                        StartDestination.HOME -> Route.Home
                        StartDestination.ONBOARDING -> Route.Onboarding
                        StartDestination.LOGIN -> Route.Login
                    }
                    navController.navigate(next) {
                        popUpTo(Route.Splash) { inclusive = true }
                    }
                }
            )
        }
        composable<Route.Onboarding> {
            val onboardingViewModel: OnboardingViewModel = hiltViewModel()
            OnboardingScreen(
                onGetStarted = {
                    onboardingViewModel.markSeen()
                    navController.navigate(Route.SignUp) {
                        popUpTo(Route.Onboarding) { inclusive = true }
                    }
                },
                onSignIn = {
                    onboardingViewModel.markSeen()
                    navController.navigate(Route.Login) {
                        popUpTo(Route.Onboarding) { inclusive = true }
                    }
                },
                onSkip = {
                    onboardingViewModel.markSeen()
                    navController.navigate(Route.Login) {
                        popUpTo(Route.Onboarding) { inclusive = true }
                    }
                }
            )
        }
        composable<Route.Login> {
            LoginScreen(
                onNavigateToSignUp = { navController.navigate(Route.SignUp) },
                onNavigateToForgot = { navController.navigate(Route.ForgotPassword) },
                onLoginSuccess = {
                    navController.navigate(Route.Home) {
                        popUpTo(Route.Login) { inclusive = true }
                    }
                }
            )
        }
        composable<Route.SignUp> {
            SignUpScreen(
                onNavigateBack = { navController.popBackStack() },
                onSignUpSuccess = {
                    navController.navigate(Route.Home) {
                        popUpTo(Route.SignUp) { inclusive = true }
                    }
                }
            )
        }
        composable<Route.ForgotPassword> {
            ForgotPasswordScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        // Main screens with bottom navigation
        composable<Route.Home>(
            enterTransition = tabEnterTransition,
            exitTransition = tabExitTransition,
            popEnterTransition = tabPopEnterTransition,
            popExitTransition = tabPopExitTransition
        ) {
            MainScaffold(navController = navController) { _ ->
                HomeScreen(
                    onNavigateToAllTasks = { navController.navigate(Route.AllTasks) },
                    onNavigateToCategories = { navController.navigate(Route.Categories) },
                    // Tapping a task opens the editor directly – no read-only detail page.
                    onNavigateToTaskDetail = { taskId -> navController.navigate(Route.AddEditTask(taskId)) },
                    onNavigateToEditTask = { taskId -> navController.navigate(Route.AddEditTask(taskId)) },
                    onNavigateToAddTask = { navController.navigate(Route.AddEditTask()) },
                    onNavigateToSearch = { navController.navigate(Route.Search) },
                    onNavigateToHistory = { navController.navigate(Route.History) },
                    onNavigateToProfile = { navController.navigate(Route.Profile) },
                    onNavigateToSettings = { navController.navigate(Route.Settings) },
                    onNavigateToCategory = { catId -> navController.navigate(Route.CategoryDetail(catId)) }
                )
            }
        }
        composable<Route.Projects>(
            enterTransition = tabEnterTransition,
            exitTransition = tabExitTransition,
            popEnterTransition = tabPopEnterTransition,
            popExitTransition = tabPopExitTransition
        ) {
            MainScaffold(navController = navController) { _ ->
                ProjectsScreen(
                    onProjectClick = { catId -> navController.navigate(Route.CategoryDetail(catId)) },
                    onProfileClick = { navController.navigate(Route.Profile) }
                )
            }
        }
        composable<Route.Categories> {
            MainScaffold(navController = navController) { _ ->
                CategoriesScreen(
                    onBack = { navController.popBackStack() },
                    onCategoryClick = { catId -> navController.navigate(Route.CategoryDetail(catId)) }
                )
            }
        }
        composable<Route.AllTasks> {
            MainScaffold(navController = navController) { _ ->
                CategoriesScreen(
                    onBack = { navController.popBackStack() },
                    onCategoryClick = { catId -> navController.navigate(Route.CategoryDetail(catId)) }
                )
            }
        }
        composable<Route.CategoryDetail> { backStackEntry ->
            val args = backStackEntry.toRoute<Route.CategoryDetail>()
            CategoryDetailScreen(
                categoryId = args.categoryId,
                onBack = { navController.popBackStack() },
                onTaskClick = { taskId -> navController.navigate(Route.AddEditTask(taskId)) },
                onAddTask = { navController.navigate(Route.AddEditTask()) }
            )
        }
        composable<Route.AddEditTask> { backStackEntry ->
            val args = backStackEntry.toRoute<Route.AddEditTask>()
            AddEditTaskScreen(
                taskId = args.taskId,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }
        composable<Route.Search> {
            MainScaffold(navController = navController) { _ ->
                SearchScreen(
                    onBack = { navController.navigate(Route.Home) {
                        popUpTo(Route.Home) { inclusive = false }
                    }},
                    onTaskClick = { taskId -> navController.navigate(Route.AddEditTask(taskId)) }
                )
            }
        }
        composable<Route.History>(
            enterTransition = tabEnterTransition,
            exitTransition = tabExitTransition,
            popEnterTransition = tabPopEnterTransition,
            popExitTransition = tabPopExitTransition
        ) {
            MainScaffold(navController = navController) { _ ->
                HistoryScreen(
                    onTaskClick = { taskId -> navController.navigate(Route.AddEditTask(taskId)) },
                    onProfileClick = { navController.navigate(Route.Profile) }
                )
            }
        }
        composable<Route.Profile> {
            MainScaffold(navController = navController) { _ ->
                ProfileScreen(
                    onBack = { navController.navigate(Route.Home) {
                        popUpTo(Route.Home) { inclusive = false }
                    }},
                    onSignOut = {
                        navController.navigate(Route.Login) {
                            popUpTo(Route.Home) { inclusive = true }
                        }
                    }
                )
            }
        }
        composable<Route.Settings>(
            enterTransition = tabEnterTransition,
            exitTransition = tabExitTransition,
            popEnterTransition = tabPopEnterTransition,
            popExitTransition = tabPopExitTransition
        ) {
            MainScaffold(navController = navController) { _ ->
                SettingsScreen(
                    onBack = { navController.navigate(Route.Home) {
                        popUpTo(Route.Home) { inclusive = false }
                    }},
                    onNavigateToHistory = { navController.navigate(Route.History) },
                    onNavigateToProfile = { navController.navigate(Route.Profile) },
                    onNavigateToAbout = { /* Could navigate to about */ }
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Tab transitions – a short slide + cross-fade in the swipe direction  */
/* ------------------------------------------------------------------ */

private fun NavDestination?.tabIndex(): Int {
    val route = this?.route ?: return -1
    return when {
        route.contains("Home") || route.contains("AllTasks") -> 0
        route.contains("Projects") || route.contains("Categories") -> 1
        route.contains("History") -> 2
        route.contains("Settings") || route.contains("Profile") -> 3
        else -> -1
    }
}

/** +1 moving right through the tabs, -1 moving left, 0 when it isn't a tab swap. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabShift(): Int {
    val from = initialState.destination.tabIndex()
    val to = targetState.destination.tabIndex()
    return when {
        from < 0 || to < 0 -> 0
        to > from -> 1
        to < from -> -1
        else -> 0
    }
}

private val tabEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    val shift = tabShift()
    fadeIn(tween(200)) + slideInHorizontally(tween(280)) { full -> shift * full / 10 }
}

private val tabExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    val shift = tabShift()
    fadeOut(tween(150)) + slideOutHorizontally(tween(280)) { full -> -shift * full / 10 }
}

private val tabPopEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    val shift = tabShift()
    fadeIn(tween(200)) + slideInHorizontally(tween(280)) { full -> -shift * full / 10 }
}

private val tabPopExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    val shift = tabShift()
    fadeOut(tween(150)) + slideOutHorizontally(tween(280)) { full -> shift * full / 10 }
}
