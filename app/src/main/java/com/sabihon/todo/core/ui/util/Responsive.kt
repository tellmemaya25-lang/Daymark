package com.sabihon.todo.core.ui.util

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * True when the window is too short to pin a header above scrolling content.
 *
 * Landscape is the obvious case, but it is not the only one: a phone in split-screen
 * or a folded device has the same problem with no rotation involved. So the rule is
 * about height, with orientation as the cheap first test.
 *
 * Screens should use this to decide *where* a header goes (pinned vs. first list
 * item), never to hide content.
 */
@Composable
@ReadOnlyComposable
fun isCompactHeight(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
        configuration.screenHeightDp < COMPACT_HEIGHT_DP
}

/**
 * Below this many dp of window height, a pinned header would eat too much of the
 * viewport on Home. Portrait phones report ~700-800 dp and small tablets more, so
 * there is plenty of margin.
 */
private const val COMPACT_HEIGHT_DP = 480
