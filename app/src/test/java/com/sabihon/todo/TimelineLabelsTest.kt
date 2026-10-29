package com.sabihon.todo

import com.sabihon.todo.ui.activity.ActivitySegment
import com.sabihon.todo.ui.activity.TimelineLabels
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The strings a timeline block is labelled with.
 *
 * Worth pinning because each one is a number the user will act on: a 90-minute block
 * labelled "1h" is a scheduling error the app caused, and a block that ends at midnight
 * labelled "00:00" reads as if it starts there.
 */
class TimelineLabelsTest {

    @Test
    fun `gutter labels use 12-hour time and include meridiem`() {
        assertEquals("12:00 AM", TimelineLabels.hourLabel(0))
        assertEquals("9:00 AM", TimelineLabels.hourLabel(9))
        assertEquals("12:00 PM", TimelineLabels.hourLabel(12))
        assertEquals("11:00 PM", TimelineLabels.hourLabel(23))
    }

    @Test
    fun `minute labels include the correct meridiem`() {
        assertEquals("12:00 AM", TimelineLabels.minuteLabel(0))
        assertEquals("1:05 AM", TimelineLabels.minuteLabel(65))
        assertEquals("9:30 AM", TimelineLabels.minuteLabel(570))
        assertEquals("12:00 PM", TimelineLabels.minuteLabel(720))
        assertEquals("11:59 PM", TimelineLabels.minuteLabel(1439))
    }

    @Test
    fun `end of day is shown as midnight while values are clamped`() {
        assertEquals("12:00 AM", TimelineLabels.minuteLabel(1440))
        assertEquals("12:00 AM", TimelineLabels.minuteLabel(1500))
        assertEquals("12:00 AM", TimelineLabels.minuteLabel(-30))
    }

    @Test
    fun `durations read the way people say them`() {
        assertEquals("5m", TimelineLabels.durationLabel(5))
        assertEquals("30m", TimelineLabels.durationLabel(30))
        assertEquals("59m", TimelineLabels.durationLabel(59))
        assertEquals("1h", TimelineLabels.durationLabel(60))
        assertEquals("1h 30m", TimelineLabels.durationLabel(90))
        assertEquals("2h", TimelineLabels.durationLabel(120))
        assertEquals("12h", TimelineLabels.durationLabel(720))
    }

    @Test
    fun `a block prints its range`() {
        assertEquals("9:00 AM - 10:00 AM", TimelineLabels.timeRangeLabel(540, 600))
        assertEquals("11:30 PM - 12:00 AM", TimelineLabels.timeRangeLabel(1410, 1440))
        assertEquals("11:30 AM - 12:30 PM", TimelineLabels.timeRangeLabel(690, 750))
    }

    @Test
    fun `an unknown stored segment falls back to the feed`() {
        assertEquals(ActivitySegment.FEED, ActivitySegment.fromName(null))
        assertEquals(ActivitySegment.FEED, ActivitySegment.fromName(""))
        assertEquals(ActivitySegment.FEED, ActivitySegment.fromName("TIMELINE"))
        assertEquals(ActivitySegment.CALENDAR, ActivitySegment.fromName("CALENDAR"))
        assertEquals("Calendar", ActivitySegment.CALENDAR.label)
    }
}
