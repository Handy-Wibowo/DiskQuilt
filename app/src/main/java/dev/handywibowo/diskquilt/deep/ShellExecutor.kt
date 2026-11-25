package dev.handywibowo.diskquilt.deep

import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Runs a shell script as a privileged uid. `/sdcard/Android/data` and `/sdcard/Android/obb` are
 * unreachable for the app uid even with MANAGE_EXTERNAL_STORAGE, so a privileged shell is the only
 * way to list them.
 */
interface ShellExecutor {
    val label: String

    /** True when the privilege is actually usable right now. [force] bypasses any cache. */
    fun available(force: Boolean = false): Boolean

    /** Runs [script] and returns the live process. */
    @Throws(IOException::class)
    fun exec(script: String): Process
}

/**
 * Root via `su`. Requires an unlocked bootloader and a root manager (Magisk/KernelSU/APatch).
 *
 * Root is strictly optional: without it the app scans everything else and the coverage dialog
 * explains the gap. [available] is cached so an unrooted phone never pays for the probe twice, and
 * every probe is bounded by [PROBE_TIMEOUT_MS] because a root manager can block on a prompt.
 */
class RootShell : ShellExecutor {
    override val label = "root"

    @Volatile
    private var cached: Boolean? = null

    private val su: String by lazy { resolveSu() ?: "" }

    /** [force] re-probes after the user installed or granted root; otherwise the answer is cached. */
    override fun available(force: Boolean): Boolean {
        cached?.let { if (!force) return it }
        val binary = su
        val result = binary.isNotEmpty() && canRunAsRoot(binary)
        cached = result
        return result
    }

    private fun canRunAsRoot(binary: String): Boolean {
        val process = runCatching {
            ProcessBuilder(binary, "-c", "id").redirectErrorStream(true).start()
        }.getOrNull() ?: return false
        return try {
            if (!process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                // A root-manager prompt is waiting: treat as "no root" rather than blocking a scan.
                runCatching { process.destroyForcibly() }
                false
            } else {
                val out = runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
                process.exitValue() == 0 && out.contains("uid=0")
            }
        } catch (t: Throwable) {
            runCatching { process.destroyForcibly() }
            false
        }
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 3_000L
    }

    override fun exec(script: String): Process {
        val binary = su ?: throw IOException("su not found")
        return ProcessBuilder(binary, "-c", script).start()
    }

    /** Cheap existence check first, then one bounded probe; never 9 blocking exec attempts. */
    private fun resolveSu(): String? {
        val candidates = listOf(
            "su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "/data/adb/magisk/su",
        )
        for (c in candidates) {
            val exists = if (c == "su") {
                runCatching {
                    ProcessBuilder("/system/bin/sh", "-c", "command -v su").start()
                        .let { p -> p.waitFor(1, TimeUnit.SECONDS) && p.exitValue() == 0 }
                }.getOrDefault(false)
            } else {
                java.io.File(c).exists()
            }
            if (exists) return c
        }
        return null
    }
}
