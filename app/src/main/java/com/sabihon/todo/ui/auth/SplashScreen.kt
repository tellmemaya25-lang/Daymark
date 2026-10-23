package com.sabihon.todo.ui.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sabihon.todo.core.datastore.OnboardingPreferences
import com.sabihon.todo.core.ui.components.DaymarkLockup
import com.sabihon.todo.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Where the splash hands off to.
 */
enum class StartDestination { HOME, ONBOARDING, LOGIN }

/**
 * Splash – checks auth state and whether the first-run cover still needs to be shown.
 */
@Composable
fun SplashScreen(
    onFinished: (StartDestination) -> Unit,
    viewModel: SplashViewModel = hiltViewModel()
) {
    val destination by viewModel.startDestination.collectAsState()

    LaunchedEffect(destination) {
        // Small delay so the daymark lockup is readable
        delay(900)
        destination?.let(onFinished)
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DaymarkLockup(
                markSize = 88.dp,
                fontSize = 34,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Plan it. Mark it. Done.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        CircularProgressIndicator(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
        )
    }
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
