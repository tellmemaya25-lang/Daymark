package com.sabihon.todo.ui.activity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.sabihon.todo.ui.history.HistoryScreen

/**
 * The Activity tab: one header, two lenses.
 *
 * `[Feed]` is the existing activity log, untouched; `[Calendar]` is the day agenda from
 * `TimelineLayout`. Both bodies own their scaffold and their scroll state, so switching
 * never drags one screen's scroll position into the other — the reason the segmented
 * control sits *inside* each body's list rather than floating above both (see
 * `docs/05` D2).
 *
 * The selection is stored by name rather than by enum so `rememberSaveable` has nothing
 * but a `String` to serialise; a preference-backed version arrives in P3.
 */
@Composable
fun ActivityScreen(
    onTaskClick: (String) -> Unit = {},
    onProfileClick: () -> Unit = {}
) {
    var segmentName by rememberSaveable { mutableStateOf(ActivitySegment.FEED.name) }
    val segment = ActivitySegment.fromName(segmentName)

    val segmentBar: @Composable () -> Unit = {
        ActivitySegmentBar(
            selected = segment,
            onSelect = { picked -> segmentName = picked.name }
        )
    }

    when (segment) {
        ActivitySegment.FEED -> HistoryScreen(
            onTaskClick = onTaskClick,
            onProfileClick = onProfileClick,
            headerExtra = segmentBar
        )

        ActivitySegment.CALENDAR -> CalendarScreen(
            onTaskClick = onTaskClick,
            onProfileClick = onProfileClick,
            headerExtra = segmentBar
        )
    }
}
