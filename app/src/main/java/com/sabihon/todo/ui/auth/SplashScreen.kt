package com.sabihon.todo.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sabihon.todo.core.datastore.OnboardingPreferences
import com.sabihon.todo.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Where the splash hands off to.
 */
enum class StartDestination { HOME, ONBOARDING, LOGIN }

/**
 * Route that decides where a cold start should land.
 *
 * It deliberately draws **no splash artwork**. Android's splash screen (see
 * Theme.Sabihon.Splash) already shows the branded icon, and re-showing a lockup
 * here - or holding it on screen for a beat so it can be admired - is what produces
 * two launch screens in a row. This route resolves auth + first-run state (both
 * local, so it settles in a frame or two) and navigates straight on.
 */
@Composable
fun SplashScreen(
    onFinished: (StartDestination) -> Unit,
    viewModel: SplashViewModel = hiltViewModel()
) {
    val destination by viewModel.startDestination.collectAsState()

    LaunchedEffect(destination) {
        destination?.let(onFinished)
    }

    // Plain canvas, matching the system splash background, so the hand-off between
    // the two is invisible rather than a flash of empty colour.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    )
}

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val onboardingPreferences: OnboardingPreferences
) : ViewModel() {
    private val _startDestination = MutableStateFlow<StartDestination?>(null)
    val startDestination: StateFlow<StartDestination?> = _startDestination

    init {
        viewModelScope.launch {
            val loggedIn = authRepository.isUserLoggedIn()
            val seenOnboarding = onboardingPreferences.hasSeenOnboardingNow()
            _startDestination.value = when {
                loggedIn -> StartDestination.HOME
                !seenOnboarding -> StartDestination.ONBOARDING
                else -> StartDestination.LOGIN
            }
        }
    }
}
