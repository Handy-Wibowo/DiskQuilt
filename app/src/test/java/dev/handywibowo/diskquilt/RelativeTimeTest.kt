package dev.handywibowo.diskquilt

import dev.handywibowo.diskquilt.ui.Ago
import dev.handywibowo.diskquilt.ui.RelativeTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelativeTimeTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour
    private val now = 1_700_000_000_000L

    @Test
    fun justNowUnderAMinute() {
        assertEquals(Ago.Unit.JUST_NOW, RelativeTime.ago(now - 30_000, now)?.unit)
    }

    @Test
    fun minutesHoursDays() {
        assertEquals(Ago(5, Ago.Unit.MINUTES), RelativeTime.ago(now - 5 * minute, now))
        assertEquals(Ago(3, Ago.Unit.HOURS), RelativeTime.ago(now - 3 * hour, now))
        assertEquals(Ago(2, Ago.Unit.DAYS), RelativeTime.ago(now - 2 * day, now))
    }

    @Test
    fun monthsForVeryOld() {
        assertEquals(Ago(3, Ago.Unit.MONTHS), RelativeTime.ago(now - 95 * day, now))
    }

    @Test
    fun clockSkewNeverNegative() {
        assertEquals(Ago.Unit.JUST_NOW, RelativeTime.ago(now + 5_000, now)?.unit)
    }

    @Test
    fun unknownTimestampIsNull() {
        assertNull(RelativeTime.ago(0, now))
    }
}
