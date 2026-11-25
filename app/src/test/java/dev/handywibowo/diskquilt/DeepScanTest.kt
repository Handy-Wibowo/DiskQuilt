package dev.handywibowo.diskquilt

import dev.handywibowo.diskquilt.deep.DeepScan
import dev.handywibowo.diskquilt.deep.DeepStatus
import dev.handywibowo.diskquilt.deep.ShellExecutor
import dev.handywibowo.diskquilt.scan.NodeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * The privileged path cannot run in CI (no root), so the parser that turns `find` output into tree
 * nodes is exercised directly with canned output.
 */
class DeepScanTest {

    /**
     * The privileged path cannot run in CI (no root), so the parser is exercised with the exact
     * NUL-delimited bytes `find -printf 'f\\t%s\\t%T@\\t%p\\0'` produces.
     */
    private class FakeShell(
        private val records: List<ByteArray>,
        private val exit: Int = 0,
        private val stderrText: String = "",
    ) : ShellExecutor {
        override val label = "fake"
        override fun available(force: Boolean) = true
        override fun exec(script: String): Process {
            val out = ByteArrayOutputStream()
            for (r in records) {
                out.write(r)
                out.write(0)
            }
            val stdout = ByteArrayInputStream(out.toByteArray())
            val stderr = ByteArrayInputStream(stderrText.toByteArray())
            return object : Process() {
                override fun getOutputStream() = java.io.OutputStream.nullOutputStream()
                override fun getInputStream() = stdout
                override fun getErrorStream() = stderr
                override fun waitFor() = exit
                override fun waitFor(timeout: Long, unit: TimeUnit) = true
                override fun exitValue() = exit
                override fun destroy() {}
            }
        }
    }

    private fun rec(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun nodeStoreWithData(): Pair<NodeStore, Int> {
        val store = NodeStore()
        val root = store.add("/storage/emulated/0", -1, true, 0)
        val android = store.add("Android", root, true, 0)
        val data = store.add("data", android, true, 0)
        store.accumulateSizes()
        return store to data
    }

    @Test
    fun parsesTypedNulRecords() {
        val (store, data) = nodeStoreWithData()
        val shell = FakeShell(
            listOf(
                rec("d\t1700000000.5\t/storage/emulated/0/Android/data/pkg"),
                rec("d\t1700000000.5\t/storage/emulated/0/Android/data/pkg/files"),
                rec("f\t4000\t1700000001.0\t/storage/emulated/0/Android/data/pkg/files/a.bin"),
                rec("f\t6000\t1700000002.0\t/storage/emulated/0/Android/data/pkg/cache/b.bin"),
                rec("l\t/storage/emulated/0/Android/data/pkg/link"),
            ),
        )

        val stats = DeepScan.expand(store, data, "/storage/emulated/0/Android/data", shell)

        assertEquals(DeepStatus.COMPLETE, stats.status)
        assertEquals(2L, stats.files)
        assertEquals(2L, stats.dirs)
        assertEquals(10_000L, stats.bytes)

        val pkg = store.childByName(data, "pkg")
        assertTrue(pkg >= 0)
        assertEquals(10_000L, store.sizeOf(pkg))
        assertEquals(10_000L, store.sizeOf(data))
        val link = store.childByName(pkg, "link")
        assertTrue(store.isLink(link))
        assertEquals(0L, store.sizeOf(link))
    }

    @Test
    fun keepsPathsContainingTabsAndNewlines() {
        val (store, data) = nodeStoreWithData()
        val tricky = "/storage/emulated/0/Android/data/pkg/naughty\tname\nwith newline.bin"
        val shell = FakeShell(listOf(rec("f\t123\t1700000000.0\t$tricky")))

        val stats = DeepScan.expand(store, data, "/storage/emulated/0/Android/data", shell)

        assertEquals(1L, stats.files)
        assertEquals(123L, stats.bytes)
        val pkg = store.childByName(data, "pkg")
        val file = store.childByName(pkg, "naughty\tname\nwith newline.bin")
        assertTrue("tab/newline name was mangled", file >= 0)
    }

    @Test
    fun rejectsSiblingPrefixPaths() {
        val (store, data) = nodeStoreWithData()
        val shell = FakeShell(
            listOf(
                rec("f\t999\t1700000000.0\t/storage/emulated/0/Android/data-other/x.bin"),
            ),
        )

        val stats = DeepScan.expand(store, data, "/storage/emulated/0/Android/data", shell)

        assertEquals(0L, stats.files)
        assertEquals(0L, stats.bytes)
        assertEquals(DeepStatus.PARTIAL, stats.status)
    }

    @Test
    fun nonZeroExitIsReportedAsPartial() {
        val (store, data) = nodeStoreWithData()
        val shell = FakeShell(listOf(rec("f\t10\t1700000000.0\t/storage/emulated/0/Android/data/a")), exit = 1)

        val stats = DeepScan.expand(store, data, "/storage/emulated/0/Android/data", shell)

        assertEquals(DeepStatus.PARTIAL, stats.status)
        assertNotNull(stats.message)
        // Whatever was parsed before the failure is still kept.
        assertEquals(1L, stats.files)
    }

    @Test
    fun cancellationStopsBeforeCompletion() {
        val (store, data) = nodeStoreWithData()
        val shell = FakeShell((1..100).map { rec("f\t1\t1700000000.0\t/storage/emulated/0/Android/data/f$it") })

        val stats = DeepScan.expand(
            store, data, "/storage/emulated/0/Android/data", shell,
            isCancelled = { true },
        )

        assertEquals(DeepStatus.CANCELLED, stats.status)
    }

    @Test
    fun shellFailureIsReportedAsFailed() {
        val (store, data) = nodeStoreWithData()
        val broken = object : ShellExecutor {
            override val label = "broken"
            override fun available(force: Boolean) = false
            override fun exec(script: String): Process = throw java.io.IOException("no root")
        }

        val stats = DeepScan.expand(store, data, "/storage/emulated/0/Android/data", broken)

        assertEquals(DeepStatus.FAILED, stats.status)
        assertEquals(0L, store.sizeOf(data))
    }
}
