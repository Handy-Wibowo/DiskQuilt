package dev.handywibowo.diskquilt.ui

/**
 * Numbers behind the reconciliation copy. The strings themselves live in resources so both the
 * English and Indonesian UI can render them; this type only does the arithmetic.
 */
data class CoverageNumbers(
    val read: Long,
    val used: Long,
    val hidden: Long,
    val percent: Int,
)

object Coverage {

    fun hidden(scanned: Long, used: Long): Long = (used - scanned).coerceAtLeast(0)

    /** Null when usage is unknown (a folder scan cannot see the volume). */
    fun numbers(scanned: Long, used: Long): CoverageNumbers? {
        if (used <= 0) return null
        val read = scanned.coerceIn(0, used)
        return CoverageNumbers(
            read = read,
            used = used,
            hidden = hidden(scanned, used),
            percent = (read * 100.0 / used).toInt(),
        )
    }
}
