package dev.handywibowo.diskquilt

import dev.handywibowo.diskquilt.scan.NodeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeStoreTest {

    @Test
    fun accumulatesChildSizesIntoParents() {
        val store = NodeStore()
        val root = store.add("/storage/emulated/0", -1, true, 0)
        val dcim = store.add("DCIM", root, true, 0)
        val cam = store.add("Camera", dcim, true, 0)
        val f1 = store.add("IMG_1.jpg", cam, false, 0)
        val f2 = store.add("IMG_2.jpg", cam, false, 0)
        val top = store.add("note.txt", root, false, 0)
        store.addSize(f1, 2_000_000)
        store.addSize(f2, 3_000_000)
        store.addSize(top, 512)

        store.accumulateSizes()

        assertEquals(5_000_512L, store.sizeOf(root))
        assertEquals(5_000_000L, store.sizeOf(dcim))
        assertEquals(5_000_000L, store.sizeOf(cam))
        assertEquals(2, store.childCount(root))
        assertEquals(3, store.fileCount)
        assertEquals(3, store.dirCount)
    }

    @Test
    fun pathIsAbsoluteFromRoot() {
        val store = NodeStore()
        val root = store.add("/storage/emulated/0", -1, true, 0)
        val dl = store.add("Download", root, true, 0)
        val file = store.add("movie.mkv", dl, false, 0)
        assertEquals("/storage/emulated/0", store.pathOf(root))
        assertEquals("/storage/emulated/0/Download", store.pathOf(dl))
        assertEquals("/storage/emulated/0/Download/movie.mkv", store.pathOf(file))
    }

    @Test
    fun childrenAreSortedBySizeDescending() {
        val store = NodeStore()
        val root = store.add("/r", -1, true, 0)
        val small = store.add("small", root, false, 0)
        val big = store.add("big", root, false, 0)
        val mid = store.add("mid", root, false, 0)
        store.addSize(small, 10)
        store.addSize(big, 1000)
        store.addSize(mid, 100)
        store.accumulateSizes()

        val sorted = store.childrenSorted(root)
        assertEquals(big, sorted[0])
        assertEquals(mid, sorted[1])
        assertEquals(small, sorted[2])
    }

    @Test
    fun largestFilesSkipsDirsAndDeleted() {
        val store = NodeStore()
        val root = store.add("/r", -1, true, 0)
        val dir = store.add("dir", root, true, 0)
        val inner = store.add("inner.bin", dir, false, 0)
        val a = store.add("a.bin", root, false, 0)
        val b = store.add("b.bin", root, false, 0)
        store.addSize(inner, 900)
        store.addSize(a, 500)
        store.addSize(b, 100)
        store.accumulateSizes()

        val top = store.largestFiles(2)
        assertEquals(2, top.size)
        assertEquals(inner, top[0])
        assertEquals(a, top[1])
    }

    @Test
    fun markDeletedZeroesSubtreeAndShrinksAncestors() {
        val store = NodeStore()
        val root = store.add("/r", -1, true, 0)
        val dir = store.add("dir", root, true, 0)
        val f1 = store.add("f1", dir, false, 0)
        val f2 = store.add("f2", root, false, 0)
        store.addSize(f1, 400)
        store.addSize(f2, 100)
        store.accumulateSizes()
        assertEquals(500L, store.sizeOf(root))

        val freed = store.markDeleted(dir)
        assertEquals(400L, freed)
        assertEquals(100L, store.sizeOf(root))
        assertEquals(1, store.childCount(root))
        assertTrue(store.isDeleted(dir))
        assertTrue(store.isDeleted(f1))
        assertFalse(store.isDeleted(f2))
    }

    @Test
    fun deletingFirstChildKeepsRemainingChildrenListed() {
        val store = NodeStore()
        val root = store.add("/r", -1, true, 0)
        val keep = store.add("keep", root, false, 0)
        val nested = store.add("nested", root, true, 0)
        val inner = store.add("inner.bin", nested, false, 0)
        store.addSize(keep, 200)
        store.addSize(inner, 300)
        store.accumulateSizes()

        // "nested" is the head of the sibling chain (add() prepends).
        store.markDeleted(nested)

        val remaining = store.childrenSorted(root).toList()
        assertEquals(listOf(keep), remaining)
        assertEquals(1, store.childCount(root))
        assertEquals(200L, store.sizeOf(root))
    }


    @Test
    fun mtimeRoundTripsThroughSeconds() {
        val store = NodeStore()
        val root = store.add("/r", -1, true, 0)
        val f = store.add("f", root, false, 1_700_000_000_000)
        assertEquals(1_700_000_000_000, store.mtimeOf(f))
    }

    @Test
    fun childCountSkipsDeleted() {
        val store = NodeStore()
        val root = store.add("/r", -1, true, 0)
        val a = store.add("a", root, false, 0)
        val b = store.add("b", root, true, 0)
        store.add("inner", b, false, 0)
        store.accumulateSizes()
        assertEquals(2, store.childCount(root))
        store.markDeleted(a)
        assertEquals(1, store.childCount(root))
    }

    @Test
    fun trimToSizeKeepsDataAndAllowsMoreNodes() {
        val store = NodeStore(initialCapacity = 1024)
        val root = store.add("/r", -1, true, 0)
        repeat(10) { store.add("f$it", root, false, 0) }
        store.trimToSize()
        assertEquals(11, store.count)
        assertEquals("f9", store.nameOf(store.count - 1))
        val extra = store.add("late", root, false, 0)
        assertEquals(12, store.count)
        assertEquals("late", store.nameOf(extra))
    }

    @Test
    fun growsPastInitialCapacity() {
        val store = NodeStore(initialCapacity = 4)
        val root = store.add("/r", -1, true, 0)
        for (i in 0 until 5000) store.add("f$i", root, false, 0)
        store.accumulateSizes()
        assertEquals(5001, store.count)
        assertEquals(5000, store.childCount(root))
        assertEquals("f4999", store.nameOf(5000))
    }
}
