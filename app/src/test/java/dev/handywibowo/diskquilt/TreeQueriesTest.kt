package dev.handywibowo.diskquilt

import dev.handywibowo.diskquilt.scan.NodeStore
import dev.handywibowo.diskquilt.scan.SortMode
import dev.handywibowo.diskquilt.scan.TreeQueries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeQueriesTest {

    private fun store(): NodeStore {
        val s = NodeStore()
        val root = s.add("/r", -1, true, 0)
        val dcim = s.add("DCIM", root, true, 0)
        val a = s.add("trip.mp4", dcim, false, 0)
        val b = s.add("IMG_0001.jpg", dcim, false, 0)
        val c = s.add("trip-notes.txt", root, false, 0)
        val d = s.add("archive.zip", root, false, 0)
        s.addSize(a, 5000)
        s.addSize(b, 3000)
        s.addSize(c, 100)
        s.addSize(d, 2000)
        s.accumulateSizes()
        return s
    }

    @Test
    fun searchIsCaseInsensitiveAndSizeOrdered() {
        val s = store()
        val hits = TreeQueries.search(s, "TRIP").map { s.nameOf(it) }
        assertEquals(listOf("trip.mp4", "trip-notes.txt"), hits)
    }

    @Test
    fun searchMatchesDirectoriesToo() {
        val s = store()
        val hits = TreeQueries.search(s, "dcim").map { s.nameOf(it) }
        assertEquals(listOf("DCIM"), hits)
    }

    @Test
    fun blankQueryReturnsNothing() {
        val s = store()
        assertTrue(TreeQueries.search(s, "   ").isEmpty())
    }

    @Test
    fun searchRespectsLimit() {
        val s = NodeStore()
        val root = s.add("/r", -1, true, 0)
        repeat(50) { s.add("match$it.bin", root, false, 0) }
        for (i in 1..50) s.addSize(i, 10L * i)
        s.accumulateSizes()
        assertEquals(10, TreeQueries.search(s, "match", limit = 10).size)
    }

    @Test
    fun typesAggregateBytesAndCounts() {
        val s = store()
        val types = TreeQueries.types(s)
        val byExt = types.associate { it.extension to it }
        assertEquals(5000L, byExt.getValue("mp4").bytes)
        assertEquals(1, byExt.getValue("mp4").count)
        assertEquals(2000L, byExt.getValue("zip").bytes)
        // Sorted biggest first.
        assertEquals("mp4", types.first().extension)
    }

    @Test
    fun typesSumToScannedFileBytes() {
        val s = store()
        val total = TreeQueries.types(s).sumOf { it.bytes }
        assertEquals(5000L + 3000L + 100L + 2000L, total)
    }

    @Test
    fun extensionlessFilesAreGrouped() {
        val s = NodeStore()
        val root = s.add("/r", -1, true, 0)
        val f = s.add("README", root, false, 0)
        s.addSize(f, 42)
        s.accumulateSizes()
        assertEquals("(none)", TreeQueries.types(s).first().extension)
    }

    @Test
    fun sortModesOrderChildren() {
        val s = store()
        val root = 0
        val bySize = s.childrenSorted(root, SortMode.SIZE).map { s.nameOf(it) }
        assertEquals(listOf("DCIM", "archive.zip", "trip-notes.txt"), bySize)

        // Names read A→Z by default; size reads biggest-first.
        val byName = s.childrenSorted(root, SortMode.NAME).map { s.nameOf(it) }
        assertEquals(listOf("archive.zip", "DCIM", "trip-notes.txt"), byName)

        val byNameDesc = s.childrenSorted(root, SortMode.NAME, descending = true).map { s.nameOf(it) }
        assertEquals(listOf("trip-notes.txt", "DCIM", "archive.zip"), byNameDesc)
    }

    @Test
    fun typeSortGroupsByExtension() {
        val s = store()
        val dcim = s.childByName(0, "DCIM")
        // Extensions read A→Z, so .jpg comes before .mp4.
        val order = s.childrenSorted(dcim, SortMode.TYPE).map { TreeQueries.extensionOf(s.nameOf(it)) }
        assertEquals(listOf("jpg", "mp4"), order)
    }
}
