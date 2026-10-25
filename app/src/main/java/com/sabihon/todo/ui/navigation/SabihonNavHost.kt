package com.sabihon.todo.ui.navigation

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
        composable<Route.Home> {
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
    }
}
