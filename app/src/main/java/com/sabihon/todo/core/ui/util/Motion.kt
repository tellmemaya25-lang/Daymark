package com.sabihon.todo.core.ui.util

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the user has left system animations switched on.
 *
 * Compose's `animate*AsState` respects "Remove animations" for *finite* animations,
 * but `rememberInfiniteTransition` and anything driven by a manual spring do not.
 * Decorative motion that would otherwise loop forever has to ask explicitly.
 *
 * Read once per composition: this is a system setting, not something worth
 * re-querying every frame.
 */
@Composable
fun rememberSystemAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE
            )
        }.getOrDefault(1f) > 0f
    }
}
