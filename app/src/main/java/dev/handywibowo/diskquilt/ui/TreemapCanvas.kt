package dev.handywibowo.diskquilt.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import dev.handywibowo.diskquilt.R
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.handywibowo.diskquilt.scan.NodeStore
import dev.handywibowo.diskquilt.treemap.FileCategory
import dev.handywibowo.diskquilt.treemap.Treemap
import kotlin.math.max
import kotlin.math.roundToInt

/** One laid-out cell with everything the draw pass needs precomputed. */
private data class DrawnCell(
    val cell: Treemap.Cell,
    val color: Color,
    val label: androidx.compose.ui.text.TextLayoutResult?,
    /** Spoken description; independent of whether the text fitted on screen. */
    val description: String,
)

/**
 * Treemap canvas. Cells are emitted parent-first, so reverse iteration yields the deepest cell
 * under a tap.
 *
 * Layout, colours and text are computed once per (root, size, version) in a coroutine off the UI
 * thread; the draw pass only issues `drawRect`/`drawText`. Previously every frame ran HSV maths,
 * allocated a HashSet and re-measured hundreds of labels.
 */
@Composable
fun TreemapCanvas(
    store: NodeStore,
    root: Int,
    version: Int,
    darkTheme: Boolean,
    onNodeTap: (node: Int, aggregate: Boolean) -> Unit,
    onNodeLongPress: (node: Int, aggregate: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val smallerItemsPlain = stringResource(R.string.smaller_items_plain)
    // resources.getQuantityString is not @Composable, so it can be used inside the layout lambda.
    val resources = LocalContext.current.resources
    val smallerItemsPlural = { n: Int ->
        resources.getQuantityString(R.plurals.smaller_items_count, n, n)
    }
    val measurer = rememberTextMeasurer(cacheSize = 512)
    val density = LocalDensity.current
    // Labels are 11 sp, so a larger system font needs proportionally more cell before it fits.
    val fontScale = density.fontScale
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var drawn by remember { mutableStateOf<List<DrawnCell>>(emptyList()) }

    val labelStyle = remember(density) {
        TextStyle(
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            // Ink is chosen per cell from its luminance, so the style carries no colour.
        )
    }

    // TalkBack gets one sentence for the visible treemap instead of an unreadable Canvas.
    val semanticsLabel = stringResource(
        R.string.item_count,
        formatBytes(store.sizeOf(root)),
        pluralStringResource(R.plurals.items_count, store.childCount(root), store.childCount(root)),
    )

    LaunchedEffect(root, version, canvasSize, darkTheme, density) {
        val size = canvasSize
        if (size.width <= 0f || size.height <= 0f) {
            drawn = emptyList()
            return@LaunchedEffect
        }
        drawn = buildCells(
            store, root, size.width, size.height, version, darkTheme, measurer, labelStyle,
            smallerItems = smallerItemsPlural,
            smallerItemsPlain = smallerItemsPlain,
            labelThreshold = fontScale.coerceAtLeast(1f),
        )
    }

    Column(modifier) {
        CategoryLegend(darkTheme)
        Box(Modifier.fillMaxSize()) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .semantics {
                        contentDescription = semanticsLabel
                    }
                    .pointerInput(drawn) {
                        detectTapGestures(
                            onTap = { offset ->
                                drawn.lastOrNull { it.cell.contains(offset.x, offset.y) }
                                    ?.let { onNodeTap(it.cell.node, it.cell.aggregate) }
                            },
                            onLongPress = { offset ->
                                drawn.lastOrNull { it.cell.contains(offset.x, offset.y) }
                                    ?.let { onNodeLongPress(it.cell.node, it.cell.aggregate) }
                            },
                        )
                    },
            ) {
                canvasSize = size
                if (drawn.isEmpty()) {
                    drawRect(Color(0xFF2A3238))
                    return@Canvas
                }
                for (d in drawn) {
                    val c = d.cell
                    drawRect(
                        color = d.color,
                        topLeft = Offset(c.x, c.y),
                        size = Size(max(c.w - 0.75f, 0.5f), max(c.h - 0.75f, 0.5f)),
                    )
                    d.label?.let { laid ->
                        drawText(textLayoutResult = laid, topLeft = Offset(c.x + 4f, c.y + 3f))
                    }
                }
            }
        }
        TreemapAccessibilityLayer(drawn, onActivate = onNodeTap)
    }
}

/**
 * TalkBack cannot read a Canvas, so the largest visible blocks are mirrored as invisible focusable
 * nodes with a name/size description and a click action. They carry no pointer input, so touch
 * still reaches the canvas underneath.
 */
@Composable
private fun TreemapAccessibilityLayer(
    drawn: List<DrawnCell>,
    onActivate: (node: Int, aggregate: Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val focusable = remember(drawn) {
        drawn.asSequence()
            .filter { !it.cell.aggregate && it.cell.w > 1f && it.cell.h > 1f }
            .sortedByDescending { it.cell.area }
            .take(32)
            .toList()
    }
    focusable.forEach { d ->
        val c = d.cell
        Box(
            Modifier
                .offset { IntOffset(c.x.roundToInt(), c.y.roundToInt()) }
                .size(
                    with(density) { c.w.toDp() },
                    with(density) { c.h.toDp() },
                )
                .semantics {
                    contentDescription = describe(d)
                    onClick(label = "Open") {
                        onActivate(c.node, false)
                        true
                    }
                },
        )
    }
}

private fun describe(d: DrawnCell): String = d.description

private fun buildCells(
    store: NodeStore,
    root: Int,
    width: Float,
    height: Float,
    version: Int,
    darkTheme: Boolean,
    measurer: TextMeasurer,
    style: TextStyle,
    smallerItems: (Int) -> String,
    smallerItemsPlain: String,
    labelThreshold: Float,
): List<DrawnCell> {
    val cells = Treemap.layout(store, root, width, height)
    if (cells.isEmpty()) return emptyList()

    // A parent's label would be covered by its children's labels, so only leaves get one.
    val hasRenderedChildren = HashSet<Int>(cells.size)
    for (c in cells) {
        if (c.aggregate) continue
        val p = store.parents[c.node]
        if (p >= 0) hasRenderedChildren.add(p)
    }

    // A folder's colour is the category holding most of its bytes, so a grey box no longer hides
    // whether it is photos, video or app data.
    val dominantCache = HashMap<Int, FileCategory>(cells.size)
    fun dominant(node: Int): FileCategory {
        dominantCache[node]?.let { return it }
        var best = FileCategory.OTHER
        var bestBytes = -1L
        for (k in store.childrenSorted(node)) {
            val cat = if (store.isDir(k)) FileCategory.FOLDER else FileCategory.of(store.nameOf(k), false)
            if (cat == FileCategory.FOLDER) continue
            val s = store.sizeOf(k)
            if (s > bestBytes) {
                bestBytes = s
                best = cat
            }
        }
        dominantCache[node] = best
        return best
    }

    val out = ArrayList<DrawnCell>(cells.size)
    for (c in cells) {
        val category = when {
            c.aggregate -> FileCategory.OTHER
            store.isDir(c.node) -> dominant(c.node)
            else -> FileCategory.of(store.nameOf(c.node), false)
        }
        val color = categoryColor(category, c.depth, darkTheme)
        val ink = if (color.luminance() > 0.5f) Color(0xFF10201A) else Color(0xFFF3F7F5)

        val spoken = if (c.aggregate) {
            if (c.aggregateCount > 0) smallerItems(c.aggregateCount) else smallerItemsPlain
        } else {
            "${store.nameOf(c.node)}, ${formatBytes(store.sizeOf(c.node))}"
        }
        var label: androidx.compose.ui.text.TextLayoutResult? = null
        // An aggregate carries its parent's id, so the "parent has children" rule would suppress
        // exactly the label that explains the grey block. Aggregates are always labelled.
        val labelable = c.aggregate || !hasRenderedChildren.contains(c.node)
        if (c.w >= 64f * labelThreshold && c.h >= 22f * labelThreshold && labelable) {
            val text = when {
                c.aggregate && c.aggregateCount > 0 -> smallerItems(c.aggregateCount)
                c.aggregate -> smallerItemsPlain
                else -> "${store.nameOf(c.node)} · ${formatBytes(store.sizeOf(c.node))}"
            }
            // Text starts 4 px inside the cell, so it may use the full inner width.
            val maxWidth = (c.w - 8f).toInt().coerceAtLeast(8)
            val laid = measurer.measure(
                text = text,
                style = style.copy(color = ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = maxWidth),
            )
            if (laid.size.width <= maxWidth) label = laid
        }
        out.add(DrawnCell(c, color, label, spoken))
    }
    return out
}

/** Category palette: distinct hues, fixed per category, shaded by depth. */
internal fun categoryColor(category: FileCategory, depth: Int, darkTheme: Boolean): Color {
    val base = when (category) {
        FileCategory.VIDEO -> Color(0xFF4C7BD9)
        FileCategory.IMAGE -> Color(0xFF3FA37A)
        FileCategory.AUDIO -> Color(0xFF9B6BD6)
        FileCategory.APP -> Color(0xFFD98A3F)
        FileCategory.ARCHIVE -> Color(0xFFC9A227)
        FileCategory.DOCUMENT -> Color(0xFF4BA6B8)
        FileCategory.FOLDER -> Color(0xFF6E7C88)
        FileCategory.OTHER -> Color(0xFF8A8F94)
    }
    // Nested cells get lighter; the root stays saturated so the biggest blocks read first.
    val lighten = (depth * 0.09f).coerceIn(0f, 0.45f)
    val ink = if (darkTheme) 0.10f else 0.16f
    val r = base.red + (1f - base.red) * lighten * (1f - ink)
    val g = base.green + (1f - base.green) * lighten * (1f - ink)
    val b = base.blue + (1f - base.blue) * lighten * (1f - ink)
    return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
}

@Composable
private fun CategoryLegend(darkTheme: Boolean) {
    val entries = listOf(
        FileCategory.VIDEO,
        FileCategory.IMAGE,
        FileCategory.AUDIO,
        FileCategory.APP,
        FileCategory.ARCHIVE,
        FileCategory.DOCUMENT,
        FileCategory.FOLDER,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (category in entries) {
            val label = categoryLabel(category)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(9.dp)
                        .background(categoryColor(category, 1, darkTheme), RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
