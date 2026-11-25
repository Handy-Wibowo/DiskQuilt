package dev.handywibowo.diskquilt.scan

import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class ScanProgress(
    val files: Long,
    val dirs: Long,
    val bytes: Long,
    val currentPath: String,
    val elapsedMs: Long,
)

data class ScanResult(
    val root: Int,
    val files: Long,
    val dirs: Long,
    val bytes: Long,
    val elapsedMs: Long,
    val errors: Int,
    val cancelled: Boolean,
    val errorSamples: List<String>,
)

/** One directory entry, as read from the filesystem before it becomes a node. */
private class RawEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
    val isLink: Boolean,
    val unreadable: Boolean,
)

/**
 * Breadth-first filesystem walk.
 *
 * BFS keeps the directory queue small and guarantees "parent allocated before child", which is what
 * [NodeStore.accumulateSizes] relies on. Symlinks are recorded but never followed, so a cyclic
 * bind mount cannot hang the scan.
 *
 * Per entry the hot path does one `lstat` into a reused [StructStat] and one directory iteration;
 * it does not build a `Path.toString()`, does not materialise the directory listing into a `List`,
 * and does not allocate `BasicFileAttributes`. Entries of one directory are stat'ed on a small pool
 * (the expensive part on a cold SD card) while node insertion stays serial and in listing order.
 */
class DiskScanner(
    private val store: NodeStore,
    private val onProgress: (ScanProgress) -> Unit = {},
    private val progressIntervalMs: Long = 150,
    private val statThreads: Int = 4,
) {

    fun scan(rootPath: String, isCancelled: () -> Boolean): ScanResult {
        val started = System.currentTimeMillis()
        // Full path as the root's name: NodeStore.pathOf() then yields absolute paths for every node.
        val rootName = rootPath.trimEnd('/').ifEmpty { "/" }
        val root = store.add(rootName, -1, true, 0)
        val stats = walk(root, rootPath, initialDirs = 1, isCancelled, started)
        store.accumulateSizes()
        val elapsed = System.currentTimeMillis() - started
        onProgress(ScanProgress(stats.files, stats.dirs, stats.bytes, rootPath, elapsed))
        return ScanResult(
            root = root,
            files = stats.files,
            dirs = stats.dirs,
            bytes = stats.bytes,
            elapsedMs = elapsed,
            errors = stats.errors,
            cancelled = stats.cancelled,
            errorSamples = stats.errorSamples,
        )
    }

    /**
     * Re-lists an existing node in place, adding its children without creating a new root. Used to
     * rebuild a folder after a partial delete; call [NodeStore.accumulateSubtree] afterwards.
     */
    fun scanInto(node: Int, path: String, isCancelled: () -> Boolean): WalkStats {
        val started = System.currentTimeMillis()
        return walk(node, path, initialDirs = 0, isCancelled, started)
    }

    private fun walk(
        root: Int,
        rootPath: String,
        initialDirs: Long,
        isCancelled: () -> Boolean,
        started: Long,
    ): WalkStats {
        val queue = ArrayDeque<Pair<Int, String>>()
        queue.addLast(root to rootPath)

        var files = 0L
        var dirs = initialDirs
        var bytes = 0L
        var errors = 0
        val errorSamples = ArrayList<String>(16)
        var lastReport = 0L
        var cancelled = false
        var visited = 0

        val executor = if (statThreads > 1) {
            Executors.newFixedThreadPool(statThreads) { r ->
                Thread(r, "diskquilt-stat").apply { isDaemon = true }
            }
        } else {
            null
        }

        try {
            while (queue.isNotEmpty()) {
                if (isCancelled()) {
                    cancelled = true
                    break
                }
                val (node, path) = queue.removeFirst()
                val entries = try {
                    listDirectory(path, executor)
                } catch (t: Throwable) {
                    errors++
                    if (errorSamples.size < 16) errorSamples.add(path)
                    continue
                }

                for (e in entries) {
                    val idx = store.add(e.name, node, e.isDir, e.mtime)
                    if (e.unreadable) errors++
                    if (e.isLink) store.markLink(idx)
                    if (e.isDir) {
                        dirs++
                        queue.addLast(idx to childPath(path, e.name))
                    } else {
                        files++
                        if (!e.isLink) {
                            bytes += e.size
                            store.addSize(idx, e.size)
                        }
                    }

                    visited++
                    if (visited and 0xFF == 0) {
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= progressIntervalMs) {
                            lastReport = now
                            onProgress(ScanProgress(files, dirs, bytes, path, now - started))
                        }
                        if (isCancelled()) {
                            cancelled = true
                            break
                        }
                    }
                }
                if (cancelled) break
            }
        } finally {
            executor?.shutdownNow()
        }

        return WalkStats(files, dirs, bytes, errors, cancelled, errorSamples)
    }

    /**
     * Lists one directory. Names come from the stream; sizes come from `lstat`, which is the syscall
     * that actually costs time on a cold cache, so those run concurrently.
     */
    private fun listDirectory(path: String, executor: ExecutorService?): List<RawEntry> {
        val names = ArrayList<String>(64)
        // Iterate the stream directly: no intermediate List<Path>.
        Files.newDirectoryStream(Paths.get(path)).use { stream ->
            for (child in stream) {
                val name = child.fileName?.toString() ?: continue
                names.add(name)
            }
        }
        if (names.isEmpty()) return emptyList()

        val dir = path.trimEnd('/')
        if (executor == null || names.size < 8) {
            return names.map { statEntry("$dir/$it", it) }
        }

        val tasks = names.map { name ->
            Callable { statEntry("$dir/$name", name) }
        }
        // get() preserves submission order, which keeps sibling order stable for the tree.
        return executor.invokeAll(tasks).map { future ->
            try {
                future.get()
            } catch (t: Throwable) {
                RawEntry("?", isDir = false, size = 0, mtime = 0, isLink = false, unreadable = true)
            }
        }
    }

    private fun statEntry(fullPath: String, name: String): RawEntry {
        // lstat via android.system: one syscall, no per-entry object graph.
        return try {
            val st = Os.lstat(fullPath)
            val kind = st.st_mode and OsConstants.S_IFMT
            val isLink = kind == OsConstants.S_IFLNK
            val isDir = kind == OsConstants.S_IFDIR
            RawEntry(
                name = name,
                isDir = isDir,
                size = if (isDir || isLink) 0 else st.st_size,
                // st_mtim is API 27; st_mtime has been there since API 21 and holds the same seconds.
                mtime = st.st_mtime * 1000,
                isLink = isLink,
                unreadable = false,
            )
        } catch (t: Throwable) {
            // Os.lstat is unavailable on the JVM test classpath, so fall back to NIO there.
            statEntryFallback(fullPath, name)
        }
    }

    private fun statEntryFallback(fullPath: String, name: String): RawEntry {
        return try {
            val p: Path = Paths.get(fullPath)
            val attrs = Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val isLink = attrs.isSymbolicLink
            RawEntry(
                name = name,
                isDir = attrs.isDirectory && !isLink,
                size = if (isLink) 0 else attrs.size(),
                mtime = attrs.lastModifiedTime().toMillis(),
                isLink = isLink,
                unreadable = false,
            )
        } catch (t: IOException) {
            RawEntry(name, isDir = false, size = 0, mtime = 0, isLink = false, unreadable = true)
        } catch (t: SecurityException) {
            RawEntry(name, isDir = false, size = 0, mtime = 0, isLink = false, unreadable = true)
        }
    }

    private fun childPath(parent: String, name: String): String =
        if (parent.endsWith("/")) "$parent$name" else "$parent/$name"
}

/** Counters from one walk; [DiskScanner.scan] wraps this into a [ScanResult]. */
data class WalkStats(
    val files: Long,
    val dirs: Long,
    val bytes: Long,
    val errors: Int,
    val cancelled: Boolean,
    val errorSamples: List<String>,
)
