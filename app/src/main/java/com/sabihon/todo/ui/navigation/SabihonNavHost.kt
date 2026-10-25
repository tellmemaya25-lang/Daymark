package com.sabihon.todo.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import com.sabihon.todo.ui.profile.ProfileScreen
import com.sabihon.todo.ui.search.SearchScreen
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
                // onExploreDemo is intentionally not supplied: the app has no
                // anonymous/demo destination (Home needs a Firebase user), so the
                // tertiary action stays hidden rather than pointing at a dead end.
                // WelcomeScreen only renders it when a callback is passed.
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
        // Main screens with bottom navigation – one pager hosts all four tabs.
        composable<Route.Home>(
            // Search is presented as a panel that rises from the bottom edge, so Home
            // holds still underneath instead of running the default fade - a fading
            // background behind a sliding panel leaves a strip of nothing at the
            // bottom as the panel climbs.
            exitTransition = {
                if (targetState.destination.route?.contains("Search") == true) {
                    ExitTransition.None
                } else {
                    fadeOut(animationSpec = tween(NAV_FADE_MS))
                }
            },
            popEnterTransition = {
                if (initialState.destination.route?.contains("Search") == true) {
                    EnterTransition.None
                } else {
                    fadeIn(animationSpec = tween(NAV_FADE_MS))
                }
            }
        ) {
            MainTabsScreen(navController = navController)
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
        composable<Route.Search>(
            enterTransition = {
                slideInVertically(
                    initialOffsetY = { fullHeight -> fullHeight },
                    animationSpec = tween(SEARCH_SLIDE_IN_MS, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(SEARCH_FADE_MS, delayMillis = SEARCH_SLIDE_IN_MS / 4))
            },
            popExitTransition = {
                slideOutVertically(
                    targetOffsetY = { fullHeight -> fullHeight },
                    animationSpec = tween(SEARCH_SLIDE_OUT_MS, easing = FastOutLinearInEasing)
                )
            }
        ) {
            MainScaffold(navController = navController) { _ ->
                SearchScreen(
                    // popBackStack, not navigate(Home): going back has to run the pop
                    // transitions above, and navigating would push a second Home entry
                    // behind the one already on the stack.
                    onBack = { navController.popBackStack() },
                    onTaskClick = { taskId -> navController.navigate(Route.AddEditTask(taskId)) }
                )
            }
        }
        composable<Route.Profile> {
            MainScaffold(navController = navController) { _ ->
                ProfileScreen(
                    // Same reason as Search: pop, don't navigate. navigate(Home) after
                    // popUpTo(Home) leaves two Home entries on the stack, so the next
                    // system back press appears to do nothing.
                    onBack = { navController.popBackStack() },
                    onSignOut = {
                        navController.navigate(Route.Login) {
                            popUpTo(Route.Home) { inclusive = true }
                        }
                    }
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Navigation motion                                                   */
/* ------------------------------------------------------------------ */

/**
 * Search slides up over Home rather than fading in. Full-height travel on purpose:
 * it reads as a panel being pulled over the screen, which is the affordance the
 * header's search button implies.
 *
 * In is slower than out - the panel arrives with some weight, and a dismissal that
 * lingers feels sticky.
 */
private const val SEARCH_SLIDE_IN_MS = 320
private const val SEARCH_SLIDE_OUT_MS = 260
private const val SEARCH_FADE_MS = 140

/** Every other transition on this NavHost stays a plain cross-fade. */
private const val NAV_FADE_MS = 220
