package dev.handywibowo.diskquilt.data

import android.content.Context

/** Summary of the last finished scan, enough to offer "continue where you left off". */
data class RecentScan(
    val path: String,
    val label: String,
    val bytes: Long,
    val files: Long,
    val dirs: Long,
    val finishedAt: Long,
    val focusPath: String,
    val tab: Int,
)

/**
 * Persists only a handful of scalars, so `SharedPreferences` is enough; the tree itself is never
 * stored (a 128k-node snapshot would be tens of MB and stale the moment a file changes).
 */
class RecentScanStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("recent_scan", Context.MODE_PRIVATE)

    fun load(): RecentScan? {
        val path = prefs.getString(KEY_PATH, null) ?: return null
        return RecentScan(
            path = path,
            label = prefs.getString(KEY_LABEL, null) ?: path,
            bytes = prefs.getLong(KEY_BYTES, 0),
            files = prefs.getLong(KEY_FILES, 0),
            dirs = prefs.getLong(KEY_DIRS, 0),
            finishedAt = prefs.getLong(KEY_FINISHED, 0),
            focusPath = prefs.getString(KEY_FOCUS, null) ?: path,
            tab = prefs.getInt(KEY_TAB, 0),
        )
    }

    fun save(scan: RecentScan) {
        prefs.edit()
            .putString(KEY_PATH, scan.path)
            .putString(KEY_LABEL, scan.label)
            .putLong(KEY_BYTES, scan.bytes)
            .putLong(KEY_FILES, scan.files)
            .putLong(KEY_DIRS, scan.dirs)
            .putLong(KEY_FINISHED, scan.finishedAt)
            .putString(KEY_FOCUS, scan.focusPath)
            .putInt(KEY_TAB, scan.tab)
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_PATH = "path"
        const val KEY_LABEL = "label"
        const val KEY_BYTES = "bytes"
        const val KEY_FILES = "files"
        const val KEY_DIRS = "dirs"
        const val KEY_FINISHED = "finished_at"
        const val KEY_FOCUS = "focus"
        const val KEY_TAB = "tab"
    }
}
