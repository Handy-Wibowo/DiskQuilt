package dev.handywibowo.diskquilt.ui

import dev.handywibowo.diskquilt.scan.Volume

/**
 * A scan request plus the volume numbers needed to reconcile the result with Settings.
 * [usedBytes] is only set when the target is a whole volume; a folder scan cannot claim the
 * volume's usage, so the reconciliation line is suppressed there.
 */
data class ScanTarget(
    val path: String,
    val label: String,
    val usedBytes: Long,
    val totalBytes: Long,
) {
    val hasVolumeUsage: Boolean get() = totalBytes > 0 && usedBytes >= 0
}

object ScanTargets {

    fun volume(v: Volume): ScanTarget {
        val used = (v.totalBytes - v.freeBytes).coerceAtLeast(0)
        return ScanTarget(v.path, v.label, used, v.totalBytes)
    }

    /** Folder scans know nothing about volume usage, so both byte fields stay 0. */
    fun folder(rawPath: String): ScanTarget {
        val path = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
        val label = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
        return ScanTarget(path, label, 0, 0)
    }
}
