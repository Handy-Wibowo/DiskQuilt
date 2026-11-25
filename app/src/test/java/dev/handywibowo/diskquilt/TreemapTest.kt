package dev.handywibowo.diskquilt

import dev.handywibowo.diskquilt.scan.NodeStore
import dev.handywibowo.diskquilt.treemap.Treemap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TreemapTest {

    private fun assertNoOverlap(cells: List<Treemap.Cell>) {
        for (i in cells.indices) {
            for (j in i + 1 until cells.size) {
                val a = cells[i]
                val b = cells[j]
                val overlapW = minOf(a.x + a.w, b.x + b.w) - maxOf(a.x, b.x)
                val overlapH = minOf(a.y + a.h, b.y + b.h) - maxOf(a.y, b.y)
                assertTrue(
                    "cells ${a.node} and ${b.node} overlap by ${overlapW}x$overlapH",
                    overlapW <= 0.01f || overlapH <= 0.01f,
                )
            }
        }
    }

    @Test
    fun squarifyFillsRectangleExactly() {
        val values = listOf(600L, 300L, 200L, 120L, 60L, 40L, 20L, 10L, 5L, 3L)
        val ids = values.indices.toList()
        val cells = Treemap.squarify(ids, values, 0f, 0f, 400f, 300f)

        assertEquals(values.size, cells.size)
        val totalArea = cells.sumOf { it.area.toDouble() }
        assertEquals(400.0 * 300.0, totalArea, 1.0)
        assertNoOverlap(cells)
        for (c in cells) {
            assertTrue(c.x >= -0.01f && c.y >= -0.01f)
            assertTrue(c.x + c.w <= 400.01f && c.y + c.h <= 300.01f)
        }
    }

    @Test
    fun squarifyAreaProportionalToValue() {
        val values = listOf(500L, 250L, 125L, 125L)
        val cells = Treemap.squarify(values.indices.toList(), values, 0f, 0f, 200f, 200f)
        val totalValue = values.sum().toDouble()
        val totalArea = 200.0 * 200.0
        for (c in cells) {
            val expected = values[c.node] / totalValue * totalArea
            assertEquals(expected, c.area.toDouble(), expected * 0.01)
        }
    }

    @Test
    fun squarifyProducesReasonableAspectRatios() {
        val values = (1..40).map { (1000L / it) }
        val cells = Treemap.squarify(values.indices.toList(), values, 0f, 0f, 500f, 500f)
        val worst = cells.maxOf { maxOf(it.w / it.h, it.h / it.w) }
        assertTrue("worst aspect ratio was $worst", worst < 8f)
    }

    @Test
    fun layoutEmitsParentsBeforeChildrenAndRespectsDepth() {
        val store = NodeStore()
        val root = store.add("/root", -1, true, 0)
        val a = store.add("a", root, true, 0)
        val b = store.add("b", root, false, 0)
        val a1 = store.add("a1", a, false, 0)
        val a2 = store.add("a2", a, false, 0)
        store.addSize(b, 100)
        store.addSize(a1, 300)
        store.addSize(a2, 50)
        store.accumulateSizes()

        assertEquals(450L, store.sizeOf(root))
        assertEquals(350L, store.sizeOf(a))

        val cells = Treemap.layout(store, root, 300f, 200f, maxDepth = 5)
        assertTrue(cells.isNotEmpty())
        val indexOf = cells.withIndex().associate { it.value.node to it.index }
        assertTrue(indexOf.getValue(a) < indexOf.getValue(a1))
        assertTrue(indexOf.containsKey(b))

        val shallow = Treemap.layout(store, root, 300f, 200f, maxDepth = 1)
        assertTrue(shallow.none { it.node == a1 })
    }

    @Test
    fun layoutHandlesEmptyAndZeroSize() {
        val store = NodeStore()
        val root = store.add("/empty", -1, true, 0)
        assertTrue(Treemap.layout(store, root, 100f, 100f).isEmpty())
        store.accumulateSizes()
        assertTrue(Treemap.layout(store, root, 100f, 100f).isEmpty())
        assertTrue(Treemap.layout(store, root, 0f, 0f).isEmpty())
    }

    @Test
    fun aggregatesSiblingsThatCannotBeDrawnIndividually() {
        val store = NodeStore()
        val root = store.add("/r", -1, true, 0)
        // 400 equal children in a 300x300 box: only ~(90000/36) = 2500 cells fit, but each child is
        // far below minArea, so most must collapse into one aggregate block.
        repeat(400) { store.add("f$it.bin", root, false, 0) }
        for (i in 1..400) store.addSize(i, 1000)
        store.accumulateSizes()

        val cells = Treemap.layout(store, root, 300f, 300f, minArea = 36f, maxCells = 60)
        assertTrue("expected a capped cell list, got ${cells.size}", cells.size <= 61)
        val aggregate = cells.filter { it.aggregate }
        assertEquals(1, aggregate.size)
        // The aggregate stands for the parent so a tap can drill into the folder.
        assertEquals(root, aggregate.first().node)
    }

    @Test
    fun aggregateAreaCoversTheSkippedChildren() {
        val store = NodeStore()
        val root = store.add("/r", -1, true, 0)
        repeat(50) { store.add("big$it.bin", root, false, 0) }
        for (i in 1..50) store.addSize(i, 10_000)
        store.accumulateSizes()

        val cells = Treemap.layout(store, root, 200f, 200f, minArea = 400f, maxCells = 10)
        val total = cells.sumOf { it.area.toDouble() }
        // Whatever is drawn must still tile the rectangle: nothing is silently dropped.
        assertEquals(200.0 * 200.0, total, 200.0)
    }

    @Test
    fun wideDirectoryLaysOutQuickly() {
        val store = NodeStore()
        val root = store.add("/r", -1, true, 0)
        val wide = store.add("wide", root, true, 0)
        repeat(10_000) { store.add("f$it", wide, false, 0) }
        for (i in 2..10_001) store.addSize(i, 100)
        store.accumulateSizes()

        val started = System.nanoTime()
        val cells = Treemap.layout(store, root, 1080f, 1600f, maxCells = 2000)
        val ms = (System.nanoTime() - started) / 1_000_000

        assertTrue(cells.isNotEmpty())
        // Generous ceiling: this is a smoke check against the old O(n^2) row scan, not a benchmark.
        assertTrue("layout took ${ms}ms for 10k children", ms < 2000)
    }
}
