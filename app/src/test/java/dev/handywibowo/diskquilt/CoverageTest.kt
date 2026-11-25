package dev.handywibowo.diskquilt

import dev.handywibowo.diskquilt.ui.Coverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverageTest {

    private val gib = 1024L * 1024 * 1024

    @Test
    fun computesReadUsedHiddenPercent() {
        val n = Coverage.numbers(100 * gib, 200 * gib)!!
        assertEquals(100 * gib, n.read)
        assertEquals(200 * gib, n.used)
        assertEquals(100 * gib, n.hidden)
        assertEquals(50, n.percent)
    }

    @Test
    fun numbersAreNullWhenUsageUnknown() {
        assertNull(Coverage.numbers(100 * gib, 0))
    }

    @Test
    fun readIsClampedToUsed() {
        val n = Coverage.numbers(250 * gib, 200 * gib)!!
        assertEquals(200 * gib, n.read)
        assertEquals(100, n.percent)
        assertEquals(0L, n.hidden)
    }

    @Test
    fun hiddenIsTheDifference() {
        assertEquals(100 * gib, Coverage.hidden(100 * gib, 200 * gib))
    }

    @Test
    fun hiddenNeverNegative() {
        assertEquals(0L, Coverage.hidden(300 * gib, 200 * gib))
    }
}
