package com.sabihon.todo.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sabihon.todo.core.datastore.OnboardingPreferences
import com.sabihon.todo.domain.repository.AuthRepository
import com.sabihon.todo.ui.launch.LaunchScreen
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
 * Cold-start route: the branded launch screen, then wherever this install belongs.
 *
 * This is the one screen that both **draws** the launch art and **resolves** where to
 * go next, because they finish at roughly the same moment - the auth session and the
 * onboarding flag are both local, so they settle in a frame or two, usually well
 * before the intro has played. Navigation happens when *both* are done, so the intro
 * never holds a ready app and a slow disk never cuts the intro short.
 *
 * Ordering on a cold start, and why there are not two splashes:
 *  1. Android's system splash (`Theme.Sabihon.Splash`) - the launcher mark on charcoal.
 *  2. This screen - the same charcoal, the same mark, now with its orbits.
 *  3. The start destination, via the NavHost's 220 ms cross-fade.
 *
 * Step 1 hands over as soon as the first Compose frame is drawn, so there is one
 * continuous launch surface rather than a splash that hands to a second splash.
 *
 * The intro plays once per process: after a rotation the finished composition is
 * drawn instead (see [SplashViewModel.markIntroShown]), and this route is popped off
 * the back stack on the first navigation, so returning to the app never replays it.
 */
@Composable
fun SplashScreen(
    onFinished: (StartDestination) -> Unit,
    viewModel: SplashViewModel = hiltViewModel()
) {
    val destination by viewModel.startDestination.collectAsState()

    // Read once, at first composition. A rotation mid-intro must not restart the
    // animation, and a genuinely new process must still play it.
    val animateIntro = remember { !viewModel.introShown.value }

    var introSettled by remember { mutableStateOf(false) }
    var handedOff by remember { mutableStateOf(false) }

    LaunchedEffect(destination, introSettled) {
        val target = destination
        if (!handedOff && introSettled && target != null) {
            // Guarded because navigate() is not idempotent here: a second call would
            // push a second entry behind the one that is already on its way in.
            handedOff = true
            onFinished(target)
        }
    }

    LaunchScreen(
        animate = animateIntro,
        onIntroSettled = {
            introSettled = true
            viewModel.markIntroShown()
        }
    )
}

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val onboardingPreferences: OnboardingPreferences
) : ViewModel() {
    private val _startDestination = MutableStateFlow<StartDestination?>(null)
    val startDestination: StateFlow<StartDestination?> = _startDestination

    /**
     * Whether the branded intro has already been seen in this process.
     *
     * Scoped to the ViewModel rather than the composition on purpose: the ViewModel
     * survives a rotation, so the intro cannot replay when the window is recreated,
     * but a new process starts at `false` and plays it again on a real cold start.
     */
    private val _introShown = MutableStateFlow(false)
    val introShown: StateFlow<Boolean> = _introShown

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

    fun markIntroShown() {
        _introShown.value = true
    }
}
