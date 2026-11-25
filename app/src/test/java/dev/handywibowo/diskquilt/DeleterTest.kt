package dev.handywibowo.diskquilt

import dev.handywibowo.diskquilt.scan.Deleter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DeleterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String, size: Int): File {
        val f = File(tmp.root, name)
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(size))
        return f
    }

    @Test
    fun deletesWholeTreeAndCountsFreedBytes() {
        val root = File(tmp.root, "tree")
        File(root, "sub").mkdirs()
        file("tree/a.bin", 1000)
        file("tree/sub/b.bin", 2500)

        val report = Deleter.delete(root)

        assertTrue(report.complete)
        assertEquals(2, report.deletedFiles)
        assertEquals(3500L, report.freedBytes)
        assertFalse(root.exists())
    }

    @Test
    fun deletesSingleFile() {
        val f = file("single.bin", 700)

        val report = Deleter.delete(f)

        assertTrue(report.complete)
        assertEquals(1, report.deletedFiles)
        assertEquals(700L, report.freedBytes)
        assertFalse(f.exists())
    }

    @Test
    fun missingPathReportsNotFoundAndFreesNothing() {
        val ghost = File(tmp.root, "ghost.bin")

        val report = Deleter.delete(ghost)

        assertFalse(report.anythingDeleted)
        assertEquals(0L, report.freedBytes)
        assertEquals("Not found", report.firstError)
    }

    @Test
    fun emptyDirectoryIsRemoved() {
        val dir = File(tmp.root, "empty")
        dir.mkdirs()

        val report = Deleter.delete(dir)

        assertTrue(report.complete)
        assertFalse(dir.exists())
        assertEquals(1, report.deletedDirs)
    }

    @Test
    fun reportCarriesFirstErrorWhenSomethingFails() {
        val missing = File(tmp.root, "nope")
        val report = Deleter.delete(missing)
        assertNotNull(report.firstError)
    }
}
