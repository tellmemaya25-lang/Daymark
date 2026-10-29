package com.sabihon.todo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import com.sabihon.todo.core.datastore.ReminderPreferences
import com.sabihon.todo.core.datastore.ThemePref
import com.sabihon.todo.core.ui.theme.AppThemeViewModel
import com.sabihon.todo.core.ui.theme.LocalIsDarkTheme
import com.sabihon.todo.core.ui.theme.SabihonTheme
import com.sabihon.todo.notifications.ReminderEvents
import com.sabihon.todo.ui.navigation.SabihonNavHost
import com.sabihon.todo.ui.reminder.InAppReminderHost
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Main launcher activity – hosts Compose NavHost.
 * Handles POST_NOTIFICATIONS permission for API 33+ and dynamic light/dark theme.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var reminderEvents: ReminderEvents

    @Inject
    lateinit var reminderPreferences: ReminderPreferences

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate(). Android keeps this splash on screen only
        // until the first Compose frame is ready, then dismisses it: the splash is
        // never held to play an animation. What it hands over to is the branded launch
        // screen, painted on the same charcoal (the theme's windowSplashScreenBackground
        // and ui/launch/LaunchScreen.kt use the same value), so the two are
        // one continuous surface rather than a duplicated splash.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        maybeAskForNotificationPermission()

        setContent {
            val themeViewModel: AppThemeViewModel = hiltViewModel()
            val themePref by themeViewModel.theme.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themePref) {
                ThemePref.LIGHT -> false
                ThemePref.DARK -> true
                ThemePref.SYSTEM -> systemDark
            }

            val window = this@MainActivity.window

            SabihonTheme(darkTheme = darkTheme) {
                // enableEdgeToEdge() decides the status/nav bar icon colour from the
                // *device* theme. When the app is forced to Light on a dark phone (or the
                // other way round) the icons end up white-on-white and the clock/battery
                // vanish, so icon appearance follows the app's own theme instead.
                //
                // Driving it from [LocalIsDarkTheme] rather than from the target flag is
                // what makes the switch look right: the bars are transparent, so the
                // icons sit on the animated canvas. Flipping them when the target changes
                // turns them black-on-black for the first half of the fade - here they
                // cross over with the canvas at the midpoint, exactly when the scheme
                // local flips.
                val appIsDark = LocalIsDarkTheme.current
                LaunchedEffect(appIsDark) {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !appIsDark
                        isAppearanceLightNavigationBars = !appIsDark
                    }
                }

                Box {
                    SabihonNavHost()
                    // Above the NavHost so a reminder that comes due mid-navigation is not
                    // swallowed by a screen change.
                    InAppReminderHost(events = reminderEvents)
                }
            }
        }
    }

    /**
     * Asks for POST_NOTIFICATIONS at most once, and only when reminders are actually on.
     *
     * The previous behaviour asked on every cold start, before the user had seen a reminder or
     * a settings row - and Android stops re-prompting after two denials, so that spent both
     * chances before there was any context. After this first ask, the Settings > Reminders row
     * is the way to grant it.
     */
    private fun maybeAskForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        lifecycleScope.launch {
            if (!reminderPreferences.current().enabled) return@launch
            if (reminderPreferences.hasAskedForPermission()) return@launch
            reminderPreferences.markPermissionAsked()
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
