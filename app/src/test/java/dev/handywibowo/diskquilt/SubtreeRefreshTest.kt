package dev.handywibowo.diskquilt

import dev.handywibowo.diskquilt.scan.NodeStore
import dev.handywibowo.diskquilt.scan.SubtreeRefresh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A partial delete leaves a folder whose on-disk shape no longer matches the tree. Refresh rebuilds
 * it: the old subtree is retired and the surviving files are re-listed.
 */
class SubtreeRefreshTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun rebuildsFolderAfterFilesDisappear() {
        val dir = File(tmp.root, "folder")
        dir.mkdirs()
        File(dir, "keep.bin").writeBytes(ByteArray(1000))
        File(dir, "gone.bin").writeBytes(ByteArray(4000))

        val store = NodeStore()
        val root = store.add(tmp.root.absolutePath, -1, true, 0)
        val node = store.add("folder", root, true, 0)
        val keep = store.add("keep.bin", node, false, 0)
        val gone = store.add("gone.bin", node, false, 0)
        store.addSize(keep, 1000)
        store.addSize(gone, 4000)
        store.accumulateSizes()
        assertEquals(5000L, store.sizeOf(node))

        // Simulate the half-finished delete: one file is already gone from disk.
        File(dir, "gone.bin").delete()

        val result = SubtreeRefresh.refresh(store, node)

        assertTrue(result.refreshed)
        assertEquals(1L, result.files)
        assertEquals(1000L, result.bytes)
        assertEquals(1000L, store.sizeOf(root))
        assertEquals(1, store.childCount(root))
        val rebuilt = store.childByName(root, "folder")
        assertTrue(rebuilt >= 0)
        assertEquals(1, store.childCount(rebuilt))
        assertEquals("keep.bin", store.nameOf(store.childrenSorted(rebuilt).first()))
    }

    @Test
    fun refusesRootAndInvalidNodes() {
        val store = NodeStore()
        val root = store.add("/r", -1, true, 0)
        store.accumulateSizes()
        assertTrue(!SubtreeRefresh.refresh(store, root).refreshed)
        assertTrue(!SubtreeRefresh.refresh(store, 99).refreshed)
    }
}
