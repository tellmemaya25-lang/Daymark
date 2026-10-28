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
    fun `gutter labels are zero padded to a constant width`() {
        assertEquals("00:00", TimelineLabels.hourLabel(0))
        assertEquals("09:00", TimelineLabels.hourLabel(9))
        assertEquals("23:00", TimelineLabels.hourLabel(23))
    }

    @Test
    fun `minutes are printed as a clock time`() {
        assertEquals("00:00", TimelineLabels.minuteLabel(0))
        assertEquals("01:05", TimelineLabels.minuteLabel(65))
        assertEquals("09:30", TimelineLabels.minuteLabel(570))
        assertEquals("23:59", TimelineLabels.minuteLabel(1439))
    }

    @Test
    fun `the end of the day is 24 00 not 00 00`() {
        assertEquals("24:00", TimelineLabels.minuteLabel(1440))
        assertEquals(
            "a block clamped to end-of-day must not look like it starts at midnight",
            "24:00",
            TimelineLabels.minuteLabel(1500)
        )
        assertEquals("00:00", TimelineLabels.minuteLabel(-30))
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
        assertEquals("09:00 - 10:00", TimelineLabels.timeRangeLabel(540, 600))
        assertEquals("23:30 - 24:00", TimelineLabels.timeRangeLabel(1410, 1440))
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
