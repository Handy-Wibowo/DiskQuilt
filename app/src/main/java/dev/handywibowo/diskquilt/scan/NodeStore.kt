package dev.handywibowo.diskquilt.scan

/**
 * Flat, array-backed tree of scanned entries.
 *
 * Chosen over an object graph because a typical phone holds 200k-1M entries and one object per
 * node costs ~3-4x the memory of parallel primitive arrays. Parents are always allocated before
 * their children, so a single reverse pass can propagate sizes.
 */
class NodeStore(initialCapacity: Int = 1 shl 15) {

    var count = 0
        private set

    private var capacity = initialCapacity
    var names: Array<String?> = arrayOfNulls(capacity)
        private set
    var sizes = LongArray(capacity)
        private set
    var parents = IntArray(capacity)
        private set
    var firstChild = IntArray(capacity)
        private set
    var nextSibling = IntArray(capacity)
        private set
    /**
     * Epoch **seconds** as Int, not millis as Long: mtime is only used for display and sorting, and
     * seconds still covers 1970-2038. Saves 4 bytes per entry on a 128k-1M node tree.
     */
    var mtimes = IntArray(capacity)
        private set
    var flags = ByteArray(capacity)
        private set

    var dirCount = 0
        private set
    var fileCount = 0
        private set

    init {
        parents.fill(-1)
        firstChild.fill(-1)
        nextSibling.fill(-1)
    }

    fun nameOf(i: Int): String = names[i] ?: ""
    fun sizeOf(i: Int): Long = sizes[i]

    /** Modification time in epoch millis, converted from the compact storage. */
    fun mtimeOf(i: Int): Long = if (i in 0 until count) mtimes[i].toLong() * 1000 else 0L

    /** Live direct children of [node]; walks the sibling chain instead of storing a counter. */
    fun childCount(node: Int): Int {
        var n = 0
        var c = firstChild[node]
        while (c >= 0) {
            if (!isDeleted(c)) n++
            c = nextSibling[c]
        }
        return n
    }
    fun isDir(i: Int): Boolean = (flags[i].toInt() and DIR) != 0
    fun isLink(i: Int): Boolean = (flags[i].toInt() and LINK) != 0
    fun isDeleted(i: Int): Boolean = (flags[i].toInt() and DELETED) != 0

    fun add(name: String, parent: Int, isDir: Boolean, mtime: Long): Int {
        if (count == capacity) grow()
        val i = count++
        names[i] = name
        parents[i] = parent
        mtimes[i] = (mtime / 1000).toInt()
        var f = 0
        if (isDir) {
            f = f or DIR
            dirCount++
        } else {
            fileCount++
        }
        flags[i] = f.toByte()
        firstChild[i] = -1
        nextSibling[i] = -1
        sizes[i] = 0
        if (parent >= 0) {
            nextSibling[i] = firstChild[parent]
            firstChild[parent] = i
        }
        return i
    }

    fun markLink(i: Int) {
        flags[i] = (flags[i].toInt() or LINK).toByte()
    }

    fun addSize(i: Int, bytes: Long) {
        sizes[i] += bytes
    }

    /** Reverse-order accumulation: every child index is greater than its parent's. */
    fun accumulateSizes() {
        for (i in count - 1 downTo 1) {
            val p = parents[i]
            if (p >= 0) sizes[p] += sizes[i]
        }
    }

    fun pathOf(i: Int): String {
        if (i < 0) return ""
        val parts = ArrayList<String>(8)
        var cur = i
        while (cur >= 0) {
            val n = names[cur]
            if (n != null && n.isNotEmpty()) parts.add(n)
            cur = parents[cur]
        }
        val sb = StringBuilder()
        for (k in parts.indices.reversed()) {
            val part = parts[k]
            if (sb.isEmpty()) sb.append(part)
            else {
                if (!sb.endsWith("/")) sb.append('/')
                sb.append(part)
            }
        }
        return if (sb.isEmpty()) "/" else sb.toString()
    }

    /** First live child of [parent] with exactly [name], or -1. */
    fun childByName(parent: Int, name: String): Int {
        var c = firstChild[parent]
        while (c >= 0) {
            if (!isDeleted(c) && names[c] == name) return c
            c = nextSibling[c]
        }
        return -1
    }

    /**
     * Direct children of [node], sorted by [mode]. Deleted entries are skipped.
     *
     * The natural direction differs per mode: biggest and newest first are the useful defaults for
     * a storage tool, while names and extensions read A→Z.
     */
    fun childrenSorted(
        node: Int,
        mode: SortMode = SortMode.SIZE,
        descending: Boolean = defaultDescending(mode),
    ): IntArray {
        var live = 0
        var c = firstChild[node]
        while (c >= 0) {
            if (!isDeleted(c)) live++
            c = nextSibling[c]
        }
        if (live == 0) return IntArray(0)
        val out = IntArray(live)
        c = firstChild[node]
        var k = 0
        while (c >= 0) {
            if (!isDeleted(c)) out[k++] = c
            c = nextSibling[c]
        }
        val comparator: Comparator<Int> = when (mode) {
            SortMode.SIZE -> compareBy { sizes[it] }
            SortMode.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { nameOf(it) }
            SortMode.DATE -> compareBy { mtimes[it] }
            SortMode.TYPE -> compareBy { TreeQueries.extensionOf(nameOf(it)) }
        }
        val ordered = out.sortedWith(comparator)
        return if (descending) ordered.asReversed().toIntArray() else ordered.toIntArray()
    }

    /** True for the modes where "largest/newest first" is the useful default. */
    fun defaultDescending(mode: SortMode): Boolean = when (mode) {
        SortMode.SIZE, SortMode.DATE -> true
        SortMode.NAME, SortMode.TYPE -> false
    }

    /** Resolves an absolute path against the scanned tree, starting at node 0. */
    fun findByPath(path: String): Int {
        if (count == 0) return -1
        val norm = path.trimEnd('/').ifEmpty { "/" }
        val rootName = names[0] ?: return -1
        if (norm == rootName) return 0
        if (!norm.startsWith(rootName)) return -1
        val rest = norm.substring(rootName.length).trim('/')
        if (rest.isEmpty()) return 0
        var node = 0
        for (segment in rest.split('/')) {
            node = childByName(node, segment)
            if (node < 0) return -1
        }
        return node
    }

    /**
     * Adds a freshly scanned subtree's sizes into its ancestors. Used after expanding one directory
     * with a privileged lister, where [accumulateSizes] would double-count the untouched nodes.
     */
    fun accumulateSubtree(root: Int) {
        if (root < 0 || root >= count) return
        for (i in count - 1 downTo root + 1) {
            val p = parents[i]
            if (p >= 0) sizes[p] += sizes[i]
        }
        val total = sizes[root]
        var p = parents[root]
        while (p >= 0) {
            sizes[p] += total
            p = parents[p]
        }
    }

    /** Drops spare capacity once a scan is finished; the tree only shrinks after that. */
    fun trimToSize() {
        if (capacity == count || count == 0) return
        names = names.copyOf(count)
        sizes = sizes.copyOf(count)
        parents = parents.copyOf(count)
        firstChild = firstChild.copyOf(count)
        nextSibling = nextSibling.copyOf(count)
        mtimes = mtimes.copyOf(count)
        flags = flags.copyOf(count)
        capacity = count
    }

    /** Marks [node] and its subtree deleted; returns bytes freed (previously counted). */
    fun markDeleted(node: Int): Long {
        if (node < 0 || node >= count) return 0
        val freed = sizes[node]
        val stack = ArrayDeque<Int>()
        stack.addLast(node)
        while (stack.isNotEmpty()) {
            val n = stack.removeAt(stack.lastIndex)
            if (isDeleted(n)) continue
            flags[n] = (flags[n].toInt() or DELETED).toByte()
            sizes[n] = 0
            if (isDir(n)) {
                dirCount--
            } else {
                fileCount--
            }
            var c = firstChild[n]
            while (c >= 0) {
                stack.addLast(c)
                c = nextSibling[c]
            }
        }
        var p = parents[node]
        while (p >= 0) {
            sizes[p] = (sizes[p] - freed).coerceAtLeast(0)
            p = parents[p]
        }
        return freed
    }

    /** Top [limit] non-deleted files by size. */
    fun largestFiles(limit: Int): IntArray {
        val best = ArrayList<Int>(limit)
        for (i in 1 until count) {
            if (isDir(i) || isDeleted(i) || sizes[i] <= 0) continue
            if (best.size < limit) {
                best.add(i)
                if (best.size == limit) best.sortByDescending { sizes[it] }
            } else if (sizes[i] > sizes[best[best.size - 1]]) {
                best[best.size - 1] = i
                best.sortByDescending { sizes[it] }
            }
        }
        return best.toIntArray()
    }

    private fun grow() {
        val newCap = capacity * 2
        names = names.copyOf(newCap)
        sizes = sizes.copyOf(newCap)
        parents = parents.copyOf(newCap)
        firstChild = firstChild.copyOf(newCap)
        nextSibling = nextSibling.copyOf(newCap)
        mtimes = mtimes.copyOf(newCap)
        flags = flags.copyOf(newCap)
        capacity = newCap
    }

    private companion object {
        const val DIR = 1
        const val LINK = 2
        const val DELETED = 4
    }
}
