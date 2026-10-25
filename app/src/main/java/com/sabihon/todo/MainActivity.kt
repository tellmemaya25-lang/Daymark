package com.sabihon.todo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.sabihon.todo.core.datastore.ThemePref
import com.sabihon.todo.core.ui.theme.AppThemeViewModel
import com.sabihon.todo.core.ui.theme.LocalIsDarkTheme
import com.sabihon.todo.core.ui.theme.SabihonTheme
import com.sabihon.todo.ui.navigation.SabihonNavHost
import dagger.hilt.android.AndroidEntryPoint

/**
 * Main launcher activity – hosts Compose NavHost.
 * Handles POST_NOTIFICATIONS permission for API 33+ and dynamic light/dark theme.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate(). Android keeps this splash on screen only
        // until the first Compose frame is ready, then dismisses it - we never hold it
        // to play an animation, and there is no second Compose splash behind it.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

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

                SabihonNavHost()
            }
        }
    }
}
