package com.sabihon.todo.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe routes for Navigation-Compose.
 */
sealed interface Route {

    @Serializable
    data object Splash : Route

    @Serializable
    data object Onboarding : Route

    @Serializable
    data object Login : Route

    @Serializable
    data object SignUp : Route

    @Serializable
    data object ForgotPassword : Route

    @Serializable
    data object Home : Route

    @Serializable
    data object AllTasks : Route

    @Serializable
    data class CategoryDetail(val categoryId: String) : Route

    @Serializable
    data class AddEditTask(val taskId: String? = null) : Route

    @Serializable
    data object Search : Route

    @Serializable
    data object Profile : Route

    @Serializable
    data object Categories : Route

}

// Legacy string routes for fallback
object LegacyRoutes {
    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val LOGIN = "login"
    const val SIGN_UP = "signup"
    const val FORGOT = "forgot"
    const val HOME = "home"
    const val ALL_TASKS = "all_tasks"
    const val CATEGORY_DETAIL = "category_detail/{categoryId}"
    const val TASK_DETAIL = "task_detail/{taskId}"
    const val ADD_EDIT = "add_edit?taskId={taskId}"
    const val SEARCH = "search"
    const val HISTORY = "history"
    const val PROFILE = "profile"
    const val CATEGORIES = "categories"
    const val PROJECTS = "projects"
    const val SETTINGS = "settings"
}
