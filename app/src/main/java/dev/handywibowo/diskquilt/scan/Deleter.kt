package dev.handywibowo.diskquilt.scan

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

data class DeleteReport(
    val deletedFiles: Int,
    val deletedDirs: Int,
    val failed: Int,
    val freedBytes: Long,
    val firstError: String?,
) {
    val complete: Boolean get() = failed == 0
    val anythingDeleted: Boolean get() = deletedFiles > 0 || deletedDirs > 0
}

/**
 * Deletes a file or directory tree, reporting what actually happened.
 *
 * `File.deleteRecursively()` collapses to a single boolean: one undeletable child makes the whole
 * call report failure while the rest is already gone. This walks with `walkFileTree`, keeps going
 * past failures, and returns per-item counts plus the bytes that really disappeared.
 */
object Deleter {

    fun delete(root: File): DeleteReport {
        if (!root.exists()) {
            return DeleteReport(0, 0, 0, 0, "Not found")
        }
        var files = 0
        var dirs = 0
        var failed = 0
        var freed = 0L
        var firstError: String? = null
        val rootPath = root.toPath()

        fun fail(path: Path, message: String?) {
            failed++
            if (firstError == null) firstError = if (message.isNullOrBlank()) path.toString() else "$path: $message"
        }

        try {
            Files.walkFileTree(
                rootPath,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        return try {
                            val size = attrs.size()
                            Files.delete(file)
                            files++
                            freed += size
                            FileVisitResult.CONTINUE
                        } catch (t: Throwable) {
                            fail(file, t.message)
                            FileVisitResult.CONTINUE
                        }
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        fail(file, exc.message)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        if (exc != null) {
                            fail(dir, exc.message)
                            return FileVisitResult.CONTINUE
                        }
                        return try {
                            Files.delete(dir)
                            dirs++
                            FileVisitResult.CONTINUE
                        } catch (t: Throwable) {
                            fail(dir, t.message)
                            FileVisitResult.CONTINUE
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            fail(rootPath, t.message)
        }

        return DeleteReport(files, dirs, failed, freed, firstError)
    }
}
