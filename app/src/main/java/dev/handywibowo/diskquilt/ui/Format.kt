package dev.handywibowo.diskquilt.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.handywibowo.diskquilt.R
import java.util.Locale

/**
 * Locale-aware formatting.
 *
 * Numbers follow the device locale, but byte units stay binary (1024-based) and are labelled
 * "GB"/"MB" to match what Android Settings shows, so the two screens agree.
 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < UNITS.size - 1) {
        value /= 1024
        unit++
    }
    val pattern = if (unit == 0) "%.0f %s" else if (value < 10) "%.1f %s" else "%.0f %s"
    return String.format(Locale.getDefault(), pattern, value, UNITS[unit])
}

private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB", "PB")

fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.getDefault(), "%.1fK", n / 1_000.0)
    else -> n.toString()
}

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return if (totalSeconds < 60) {
        "${totalSeconds}s"
    } else {
        "${totalSeconds / 60}m ${totalSeconds % 60}s"
    }
}

fun formatPercent(part: Long, whole: Long): String {
    if (whole <= 0) return "0%"
    val pct = part * 100.0 / whole
    return when {
        pct >= 10 -> String.format(Locale.getDefault(), "%.0f%%", pct)
        pct >= 1 -> String.format(Locale.getDefault(), "%.1f%%", pct)
        else -> String.format(Locale.getDefault(), "%.2f%%", pct)
    }
}

/** Locale-aware date/time, replacing the hardcoded `yyyy-MM-dd HH:mm` + Locale.US. */
fun formatDateTime(epochMillis: Long): String {
    if (epochMillis <= 0) return "—"
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(
        Locale.getDefault(),
        "yMdHm",
    )
    return java.text.SimpleDateFormat(pattern, Locale.getDefault())
        .format(java.util.Date(epochMillis))
}

/** Category label for the legend. */
@Composable
fun categoryLabel(category: dev.handywibowo.diskquilt.treemap.FileCategory): String = when (category) {
    dev.handywibowo.diskquilt.treemap.FileCategory.VIDEO -> stringResource(R.string.cat_video)
    dev.handywibowo.diskquilt.treemap.FileCategory.IMAGE -> stringResource(R.string.cat_image)
    dev.handywibowo.diskquilt.treemap.FileCategory.AUDIO -> stringResource(R.string.cat_audio)
    dev.handywibowo.diskquilt.treemap.FileCategory.APP -> stringResource(R.string.cat_app)
    dev.handywibowo.diskquilt.treemap.FileCategory.ARCHIVE -> stringResource(R.string.cat_archive)
    dev.handywibowo.diskquilt.treemap.FileCategory.DOCUMENT -> stringResource(R.string.cat_document)
    dev.handywibowo.diskquilt.treemap.FileCategory.FOLDER -> stringResource(R.string.cat_folder)
    dev.handywibowo.diskquilt.treemap.FileCategory.OTHER -> stringResource(R.string.cat_other)
}
