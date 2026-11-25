package dev.handywibowo.diskquilt.deep

import dev.handywibowo.diskquilt.scan.NodeStore
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

enum class DeepStatus { COMPLETE, PARTIAL, FAILED, CANCELLED }

data class DeepStats(
    val files: Long,
    val dirs: Long,
    val bytes: Long,
    val errors: Int,
    val status: DeepStatus,
    val message: String? = null,
)

/**
 * Fills `/sdcard/Android/data` and `/sdcard/Android/obb` into an existing scan tree.
 *
 * Those two directories are unreachable for the app uid even with MANAGE_EXTERNAL_STORAGE: the FUSE
 * layer serving `/storage/emulated/0` denies them below the permission system. The shell uid holds
 * `ext_data_rw` / `ext_obb_rw`, so the listing runs through [ShellExecutor].
 *
 * Protocol: three `find` passes (`-type d`, `-type f`, `-type l`), each emitting typed,
 * NUL-terminated records (`d<TAB>mtime<TAB>path`, `f<TAB>size<TAB>mtime<TAB>path`). NUL is the only
 * byte that cannot appear in a path, so names containing tabs or newlines stay intact.
 *
 * The process exit code and stderr are checked, the reader honours a cancellation flag and a
 * timeout, and a failed process is reported as such instead of as "0 bytes found".
 */
object DeepScan {

    private const val NUL = 0.toByte()
    private const val TAB = '\t'

    fun expand(
        store: NodeStore,
        targetNode: Int,
        targetPath: String,
        executor: ShellExecutor,
        isCancelled: () -> Boolean = { false },
        timeoutMs: Long = 180_000,
        onProgress: (files: Long, dirs: Long, bytes: Long) -> Unit = { _, _, _ -> },
    ): DeepStats {
        val nodeByPath = HashMap<String, Int>(4096)
        nodeByPath[targetPath] = targetNode

        var files = 0L
        var dirs = 0L
        var bytes = 0L
        var parseErrors = 0

        val script = buildString {
            appendPass("d", "-type d", "%T@", targetPath)
            append("; ")
            appendPass("f", "-type f", "%s\t%T@", targetPath)
            append("; ")
            append("find '").append(escape(targetPath)).append("' -mindepth 1 -type l -printf 'l")
            append(TAB).append("%p\\0' 2>/dev/null")
        }

        val process = try {
            executor.exec(script)
        } catch (t: Throwable) {
            return DeepStats(0, 0, 0, 1, DeepStatus.FAILED, t.message ?: t.javaClass.simpleName)
        }

        val stderrLines = AtomicInteger(0)
        val firstStderr = AtomicReference<String?>(null)
        val drain = Thread {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) {
                        stderrLines.incrementAndGet()
                        firstStderr.compareAndSet(null, line)
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        var cancelled = false
        var timedOut = false
        val startedAt = System.currentTimeMillis()
        var processed = 0

        try {
            val input: InputStream = BufferedInputStream(process.inputStream, 1 shl 16)
            while (true) {
                if (isCancelled()) {
                    cancelled = true
                    runCatching { process.destroyForcibly() }
                    break
                }
                if (System.currentTimeMillis() - startedAt > timeoutMs) {
                    timedOut = true
                    runCatching { process.destroyForcibly() }
                    break
                }
                val record = readRecord(input) ?: break
                if (record.isEmpty()) continue

                val kind = record[0].toInt().toChar()
                when (kind) {
                    'd' -> {
                        val parts = decode(record).split(TAB, limit = 3)
                        if (parts.size == 3 && inTarget(parts[2], targetPath)) {
                            val mtime = parts[1].toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L
                            nodeFor(store, nodeByPath, targetPath, parts[2], isDir = true, size = 0, mtime = mtime)
                            dirs++
                        } else {
                            parseErrors++
                        }
                    }

                    'f' -> {
                        val parts = decode(record).split(TAB, limit = 4)
                        if (parts.size == 4 && inTarget(parts[3], targetPath)) {
                            val size = parts[1].toLongOrNull() ?: 0L
                            val mtime = parts[2].toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L
                            val node = nodeFor(
                                store, nodeByPath, targetPath, parts[3],
                                isDir = false, size = size, mtime = mtime,
                            )
                            store.addSize(node, size)
                            files++
                            bytes += size
                        } else {
                            parseErrors++
                        }
                    }

                    'l' -> {
                        val parts = decode(record).split(TAB, limit = 2)
                        if (parts.size == 2 && inTarget(parts[1], targetPath)) {
                            val node = nodeFor(
                                store, nodeByPath, targetPath, parts[1],
                                isDir = false, size = 0, mtime = 0,
                            )
                            store.markLink(node)
                        } else {
                            parseErrors++
                        }
                    }

                    else -> parseErrors++
                }

                processed++
                if (processed and 0xFFF == 0) onProgress(files, dirs, bytes)
            }
        } catch (t: Throwable) {
            parseErrors++
        } finally {
            runCatching { process.destroy() }
        }

        val finished = runCatching {
            process.waitFor(5, TimeUnit.SECONDS)
        }.getOrDefault(false)
        val exit = runCatching { process.exitValue() }.getOrDefault(-1)
        drain.join(500)

        store.accumulateSubtree(targetNode)
        onProgress(files, dirs, bytes)

        val errors = parseErrors + stderrLines.get()
        val status = when {
            cancelled -> DeepStatus.CANCELLED
            !finished && timedOut -> DeepStatus.PARTIAL
            !finished -> DeepStatus.PARTIAL
            exit != 0 -> DeepStatus.PARTIAL
            errors > 0 -> DeepStatus.PARTIAL
            else -> DeepStatus.COMPLETE
        }
        val message = when {
            cancelled -> "cancelled"
            timedOut -> "timed out after ${timeoutMs / 1000}s"
            exit != 0 -> "find exited with $exit: ${firstStderr.get() ?: "unknown error"}"
            errors > 0 -> firstStderr.get() ?: "$errors entries could not be read"
            else -> null
        }
        return DeepStats(files, dirs, bytes, errors, status, message)
    }

    /** Appends one typed NUL-delimited find pass. */
    private fun StringBuilder.appendPass(
        kind: String,
        typeArg: String,
        fields: String,
        targetPath: String,
    ) {
        append("find '").append(escape(targetPath)).append("' -mindepth 1 ").append(typeArg)
        append(" -printf '").append(kind).append(TAB).append(fields).append(TAB).append("%p\\0'")
        append(" 2>/dev/null")
    }

    /** Reads bytes up to the next NUL; null at EOF. */
    private fun readRecord(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream(256)
        while (true) {
            val b = input.read()
            if (b < 0) {
                return if (out.size() == 0) null else out.toByteArray()
            }
            if (b == NUL.toInt()) return out.toByteArray()
            out.write(b)
        }
    }

    private fun decode(record: ByteArray): String = String(record, Charsets.UTF_8)

    /** Exact target or a real descendant — not a sibling sharing the prefix. */
    private fun inTarget(path: String, targetPath: String): Boolean =
        path == targetPath || path.startsWith("$targetPath/")

    /** Finds or creates the node for [path], creating missing ancestors under [targetPath]. */
    private fun nodeFor(
        store: NodeStore,
        nodeByPath: HashMap<String, Int>,
        targetPath: String,
        path: String,
        isDir: Boolean,
        size: Long,
        mtime: Long,
    ): Int {
        nodeByPath[path]?.let { return it }
        val name = path.substringAfterLast('/')
        val parentPath = path.substringBeforeLast('/', "")
        val parent = if (parentPath == targetPath || parentPath.isEmpty()) {
            nodeByPath[targetPath] ?: 0
        } else {
            nodeFor(store, nodeByPath, targetPath, parentPath, isDir = true, size = 0, mtime = 0)
        }
        val node = store.add(name, parent, isDir, mtime)
        nodeByPath[path] = node
        return node
    }

    private fun escape(path: String): String = path.replace("'", "'\\''")
}