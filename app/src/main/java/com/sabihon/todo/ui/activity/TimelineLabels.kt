package com.sabihon.todo.ui.activity

/**
 * The text a timeline block and gutter carry.
 *
 * Split out of `CalendarScreen` because it is the part of the drawing code that can be
 * *wrong* rather than merely ugly, and it is the only part that can be tested without a
 * device: a block labelled "1h" that is really 90 minutes, or a block ending at midnight
 * printed as an ambiguous start time, is a lie in the same way a missing task is.
 *
 * All timeline times use a 12-hour clock with an explicit meridiem so morning and evening
 * blocks cannot be confused.
 */
object TimelineLabels {

    private const val MINUTES_PER_HOUR = 60
    private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR

    /** "9:00 AM", "12:00 PM". */
    fun hourLabel(hour: Int): String = minuteLabel(hour.coerceIn(0, 23) * MINUTES_PER_HOUR)

    /** "9:30 AM" for a minute from midnight. */
    fun minuteLabel(minute: Int): String {
        val clamped = minute.coerceIn(0, MINUTES_PER_DAY)
        val hourOfDay = (clamped / MINUTES_PER_HOUR) % 24
        val minutes = clamped % MINUTES_PER_HOUR
        val hourOnClock = when (val twelveHour = hourOfDay % 12) {
            0 -> 12
            else -> twelveHour
        }
        val meridiem = if (hourOfDay < 12) "AM" else "PM"
        return "$hourOnClock:${pad2(minutes)} $meridiem"
    }

    /** 30 -> "30m", 60 -> "1h", 90 -> "1h 30m". */
    fun durationLabel(minutes: Int): String {
        if (minutes < MINUTES_PER_HOUR) return "${minutes.coerceAtLeast(0)}m"
        val hours = minutes / MINUTES_PER_HOUR
        val rest = minutes % MINUTES_PER_HOUR
        return if (rest == 0) "${hours}h" else "${hours}h ${rest}m"
    }

    /** "9:00 AM - 10:00 AM" — both endpoints carry their meridiem. */
    fun timeRangeLabel(startMinute: Int, endMinute: Int): String =
        "${minuteLabel(startMinute)} - ${minuteLabel(endMinute)}"

    private fun pad2(value: Int): String = if (value < 10) "0$value" else value.toString()
}
