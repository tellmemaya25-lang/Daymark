package com.sabihon.todo.core.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Keeps at most one swipe-revealed row open per list.
 *
 * One instance per screen/list; rows register themselves when they settle open and
 * close again as soon as somebody else takes over (or the list starts scrolling).
 */
@Stable
class RevealCoordinator {

    var openRowId: String? by mutableStateOf(null)
        private set

    fun onOpened(rowId: String) {
        openRowId = rowId
    }

    fun onClosed(rowId: String) {
        if (openRowId == rowId) openRowId = null
    }

    /** Used when the list starts scrolling – every revealed row folds back. */
    fun closeAll() {
        openRowId = null
    }
}
