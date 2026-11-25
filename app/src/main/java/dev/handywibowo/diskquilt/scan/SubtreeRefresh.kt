package dev.handywibowo.diskquilt.scan

/**
 * Rebuilds one node's subtree from disk.
 *
 * Needed after a *partial* delete: some children are gone, some are not, and the in-memory tree
 * cannot express "this folder changed shape". Shrinking the byte total was not enough — it left
 * deleted files listed and the item counters wrong.
 *
 * The old subtree is marked deleted (which removes it from listings and subtracts its bytes from
 * every ancestor) and a fresh node with the same name is added under the same parent and re-listed.
 * Deleted nodes stay in the arrays as tombstones; they are filtered everywhere and cost nothing but
 * a few bytes until the next scan.
 */
object SubtreeRefresh {

    data class Result(
        val refreshed: Boolean,
        val files: Long,
        val dirs: Long,
        val bytes: Long,
        val errors: Int,
    )

    fun refresh(store: NodeStore, node: Int, isCancelled: () -> Boolean = { false }): Result {
        if (node <= 0 || node >= store.count) return Result(false, 0, 0, 0, 0)
        val parent = store.parents[node]
        if (parent < 0) return Result(false, 0, 0, 0, 0)

        val path = store.pathOf(node)
        val name = store.nameOf(node)
        val mtime = store.mtimeOf(node)

        // Order matters: capture identity first, then retire the old subtree.
        store.markDeleted(node)
        val fresh = store.add(name, parent, true, mtime)
        val stats = DiskScanner(store).scanInto(fresh, path, isCancelled)
        store.accumulateSubtree(fresh)

        return Result(true, stats.files, stats.dirs, stats.bytes, stats.errors)
    }
}
