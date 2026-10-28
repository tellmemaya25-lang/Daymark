package com.sabihon.todo.ui.activity

/**
 * Which body the Activity tab draws under its header.
 *
 * Both segments are views of the same day-ish data, so this is a lens rather than a
 * destination: no route changes, no new tab, no pager page.
 */
enum class ActivitySegment(val label: String) {
    /** The reverse-chronological log: what was completed, created, reopened, deleted. */
    FEED("Feed"),

    /** The 24-hour day agenda, driven by [TimelineLayout]. */
    CALENDAR("Calendar");

    companion object {
        /** Falls back to [FEED] for anything unrecognised (old preference values). */
        fun fromName(value: String?): ActivitySegment =
            values().firstOrNull { it.name == value } ?: FEED
    }
}
