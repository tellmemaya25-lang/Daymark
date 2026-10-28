package com.sabihon.todo.ui.activity

/**
 * The text a timeline block and gutter carry.
 *
 * Split out of `CalendarScreen` because it is the part of the drawing code that can be
 * *wrong* rather than merely ugly, and it is the only part that can be tested without a
 * device: a block labelled "1h" that is really 90 minutes, or a block ending at midnight
 * printed as "00:00", is a lie in the same way a missing task is.
 *
 * 24-hour throughout so every gutter label is the same width and nothing shifts when the
 * clock passes 12.
 */
object TimelineLabels {

    private const val MINUTES_PER_HOUR = 60
    private const val MIDNIGHT = 24 * MINUTES_PER_HOUR

    /** "08:00". */
    fun hourLabel(hour: Int): String = "${pad2(hour)}:00"

    /**
     * "09:30" for a minute from midnight.
     *
     * 1440 maps to "24:00", not "00:00": a block that runs to the end of its day must not
     * look like it starts at midnight on its own card.
     */
    fun minuteLabel(minute: Int): String {
        val clamped = minute.coerceIn(0, MIDNIGHT)
        if (clamped == MIDNIGHT) return "24:00"
        return "${pad2(clamped / MINUTES_PER_HOUR)}:${pad2(clamped % MINUTES_PER_HOUR)}"
    }

    /** 30 -> "30m", 60 -> "1h", 90 -> "1h 30m". */
    fun durationLabel(minutes: Int): String {
        if (minutes < MINUTES_PER_HOUR) return "${minutes.coerceAtLeast(0)}m"
        val hours = minutes / MINUTES_PER_HOUR
        val rest = minutes % MINUTES_PER_HOUR
        return if (rest == 0) "${hours}h" else "${hours}h ${rest}m"
    }

    /** "09:00 - 09:30" — the range drawn on a block. */
    fun timeRangeLabel(startMinute: Int, endMinute: Int): String =
        "${minuteLabel(startMinute)} - ${minuteLabel(endMinute)}"

    private fun pad2(value: Int): String = if (value < 10) "0$value" else value.toString()
}
