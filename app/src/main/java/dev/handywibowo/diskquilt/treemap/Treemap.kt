package dev.handywibowo.diskquilt.treemap

import dev.handywibowo.diskquilt.scan.NodeStore
import kotlin.math.min

/**
 * Squarified treemap (Bruls, Huizing, van Wijk). Pure Kotlin so it is unit-testable on the JVM.
 *
 * Output is a flat list in draw order: parents before their children. A cell with
 * [Cell.aggregate] set stands for all the siblings too small to draw; it carries the parent's node
 * id so a tap can drill into the parent and list them.
 */
object Treemap {

    data class Cell(
        val node: Int,
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val depth: Int,
        val aggregate: Boolean = false,
        /** For an aggregate cell: how many siblings it stands for. */
        val aggregateCount: Int = 0,
    ) {
        val area: Float get() = w * h
        fun contains(px: Float, py: Float): Boolean =
            px >= x && px < x + w && py >= y && py < y + h
    }

    /** Sentinel id for the aggregated remainder of a directory. */
    private const val AGGREGATE_ID = -2

    fun layout(
        store: NodeStore,
        root: Int,
        width: Float,
        height: Float,
        maxDepth: Int = 5,
        minArea: Float = 36f,
        maxCells: Int = 3000,
    ): List<Cell> {
        val out = ArrayList<Cell>(256)
        if (width <= 0f || height <= 0f || root < 0 || root >= store.count) return out
        if (store.sizeOf(root) <= 0) return out

        val queue = ArrayDeque<Cell>()
        queue.addLast(Cell(root, 0f, 0f, width, height, 0))

        while (queue.isNotEmpty() && out.size < maxCells) {
            val parent = queue.removeFirst()
            val node = parent.node
            if (!store.isDir(node) || store.isDeleted(node)) continue
            if (parent.depth >= maxDepth || parent.area < minArea) continue

            val kids = store.childrenSorted(node)
            if (kids.isEmpty()) continue

            // Drop zero/deleted entries so squarify never divides by zero.
            val ids = ArrayList<Int>(kids.size)
            val values = ArrayList<Long>(kids.size)
            for (k in kids) {
                val s = store.sizeOf(k)
                if (s > 0 && !store.isDeleted(k)) {
                    ids.add(k)
                    values.add(s)
                }
            }
            if (ids.isEmpty()) continue

            // Only as many cells as can be distinguished; the rest becomes one grey block. Without
            // this a folder with 10k children painted thousands of 2 px squares.
            val drawable = (parent.area / minArea).toInt().coerceAtLeast(1)
            val remaining = (maxCells - out.size).coerceAtLeast(1)
            // One slot is reserved for the aggregate block, otherwise the area of the skipped
            // siblings would silently disappear from the treemap.
            val budget = min(drawable, remaining).coerceAtLeast(1)
            val truncating = ids.size > budget
            val shown = if (truncating) {
                // Leave one slot for the aggregate block.
                (budget - 1).coerceAtLeast(1)
            } else {
                ids.size
            }

            val squarifyIds: List<Int>
            val squarifyValues: List<Long>
            // An aggregate must not dominate: if the tail is bigger than the drawn head, keep more
            // children visible instead of one giant grey rectangle.
            val skipped = (ids.size - shown).coerceAtLeast(0)
            if (skipped > 0) {
                var rest = 0L
                for (i in shown until values.size) rest += values[i]
                squarifyIds = ids.subList(0, shown) + AGGREGATE_ID
                squarifyValues = values.subList(0, shown) + rest
            } else {
                squarifyIds = ids
                squarifyValues = values
            }

            val rects = squarify(
                squarifyIds,
                squarifyValues,
                parent.x,
                parent.y,
                parent.w,
                parent.h,
                parent.depth + 1,
            )
            for (c in rects) {
                if (out.size >= maxCells) break
                if (c.node == AGGREGATE_ID) {
                    out.add(c.copy(node = node, aggregate = true, aggregateCount = skipped))
                    continue
                }
                out.add(c)
                if (c.area >= minArea) queue.addLast(c)
            }
        }
        return out
    }

    fun squarify(
        ids: List<Int>,
        values: List<Long>,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        depth: Int = 1,
    ): List<Cell> {
        val n = ids.size
        if (n == 0 || w <= 0f || h <= 0f) return emptyList()

        var total = 0.0
        for (v in values) total += v.toDouble()
        if (total <= 0.0) return emptyList()

        // Areas scaled so their sum equals the rectangle area; values are sorted desc by caller.
        val areaScale = (w.toDouble() * h.toDouble()) / total
        val areas = DoubleArray(n) { values[it].toDouble() * areaScale }
        val out = ArrayList<Cell>(n)

        var rx = x.toDouble()
        var ry = y.toDouble()
        var rw = w.toDouble()
        var rh = h.toDouble()
        var i = 0

        while (i < n) {
            val side = min(rw, rh)
            if (side <= 0.0) break

            // Running min/max: both are monotone as the row grows, so the ratio is O(1) per
            // extension instead of rescanning the row.
            var rowEnd = i + 1
            var rowSum = areas[i]
            var rowMax = areas[i]
            var rowMin = areas[i]
            var worst = ratio(rowSum, rowMax, rowMin, side)
            while (rowEnd < n) {
                val a = areas[rowEnd]
                val candidateSum = rowSum + a
                val candidateMax = if (a > rowMax) a else rowMax
                val candidateMin = if (a < rowMin) a else rowMin
                val candidateWorst = ratio(candidateSum, candidateMax, candidateMin, side)
                if (candidateWorst > worst) break
                worst = candidateWorst
                rowSum = candidateSum
                rowMax = candidateMax
                rowMin = candidateMin
                rowEnd++
            }

            val thickness = rowSum / side
            if (thickness <= 0.0) break
            if (rh <= rw) {
                // Vertical strip: spans full height, advances along x.
                var yy = ry
                for (k in i until rowEnd) {
                    val hh = areas[k] / thickness
                    out.add(Cell(ids[k], rx.toFloat(), yy.toFloat(), thickness.toFloat(), hh.toFloat(), depth))
                    yy += hh
                }
                rx += thickness
                rw -= thickness
            } else {
                // Horizontal strip: spans full width, advances along y.
                var xx = rx
                for (k in i until rowEnd) {
                    val ww = areas[k] / thickness
                    out.add(Cell(ids[k], xx.toFloat(), ry.toFloat(), ww.toFloat(), thickness.toFloat(), depth))
                    xx += ww
                }
                ry += thickness
                rh -= thickness
            }
            i = rowEnd
        }
        return out
    }

    /** True when [total] children cannot all fit in [budget] slots. */
    private fun shownNeedsAggregate(total: Int, budget: Int): Boolean = total > budget

    private fun ratio(sum: Double, maxArea: Double, minArea: Double, side: Double): Double {
        if (sum <= 0.0 || side <= 0.0 || minArea <= 0.0) return Double.MAX_VALUE
        val s2 = sum * sum
        val w2 = side * side
        val a = w2 * maxArea / s2
        val b = s2 / (w2 * minArea)
        return if (a > b) a else b
    }
}
