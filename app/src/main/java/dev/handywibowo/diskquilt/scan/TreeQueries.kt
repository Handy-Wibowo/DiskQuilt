package dev.handywibowo.diskquilt.scan

import java.util.Locale

/** One file extension with its footprint, for the Types tab. */
data class TypeStat(
    val extension: String,
    val count: Int,
    val bytes: Long,
)

/** Sort orders offered by the Contents list. */
enum class SortMode { SIZE, NAME, DATE, TYPE }

object TreeQueries {

    /**
     * Case-insensitive name search over the whole scanned tree, biggest matches first.
     * A linear pass is ~128k comparisons on the test device (well under 100 ms), so no index yet.
     */
    fun search(store: NodeStore, rawQuery: String, limit: Int = 400): IntArray {
        val query = rawQuery.trim().lowercase(Locale.ROOT)
        if (query.isEmpty()) return IntArray(0)
        val matches = ArrayList<Int>(64)
        for (i in 1 until store.count) {
            if (store.isDeleted(i)) continue
            if (store.nameOf(i).lowercase(Locale.ROOT).contains(query)) matches.add(i)
        }
        if (matches.isEmpty()) return IntArray(0)
        matches.sortByDescending { store.sizeOf(it) }
        return if (matches.size > limit) {
            IntArray(limit) { matches[it] }
        } else {
            matches.toIntArray()
        }
    }

    /**
     * Aggregates every file by extension. Directories are not counted; `(none)` covers files
     * without an extension so the list always sums to the scanned file total.
     */
    fun types(store: NodeStore): List<TypeStat> {
        val counts = HashMap<String, Int>()
        val bytes = HashMap<String, Long>()
        for (i in 1 until store.count) {
            if (store.isDir(i) || store.isDeleted(i)) continue
            val size = store.sizeOf(i)
            if (size <= 0) continue
            val ext = extensionOf(store.nameOf(i))
            counts[ext] = (counts[ext] ?: 0) + 1
            bytes[ext] = (bytes[ext] ?: 0L) + size
        }
        return bytes.map { (ext, total) ->
            TypeStat(ext, counts[ext] ?: 0, total)
        }.sortedByDescending { it.bytes }
    }

    /** Lowercase extension without the dot, or `(none)`. */
    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return "(none)"
        return name.substring(dot + 1).lowercase(Locale.ROOT)
    }
}
