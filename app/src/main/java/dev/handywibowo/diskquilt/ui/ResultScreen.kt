package dev.handywibowo.diskquilt.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.handywibowo.diskquilt.R
import dev.handywibowo.diskquilt.scan.NodeStore
import dev.handywibowo.diskquilt.scan.SortMode
import dev.handywibowo.diskquilt.scan.TreeQueries
import dev.handywibowo.diskquilt.scan.TypeStat
import dev.handywibowo.diskquilt.treemap.FileCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAB_TREEMAP = 0
private const val TAB_CONTENTS = 1
private const val TAB_LARGEST = 2
private const val TAB_TYPES = 3
private const val TAB_FIND = 4

@Composable
fun ResultScreen(
    vm: ScanViewModel,
    store: NodeStore,
    version: Int,
    result: ScanState.Done,
    onRescan: () -> Unit,
) {
    val restore = remember { vm.takeRestore() }
    val restoredFocus = remember {
        restore.first?.let { store.findByPath(it) }?.takeIf { it >= 0 }
    }
    // rememberSaveable, not remember: an orientation change must not throw away your location.
    var focus by rememberSaveable { mutableIntStateOf(restoredFocus ?: result.result.root) }
    val backStack = remember { mutableStateListOf<Int>() }
    var tab by rememberSaveable {
        mutableIntStateOf(if (restoredFocus != null) restore.second else TAB_TREEMAP)
    }
    var selected by remember { mutableIntStateOf(-1) }
    var showDetails by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showCoverage by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(SortMode.SIZE) }
    var query by remember { mutableStateOf("") }
    val selection = remember { mutableStateListOf<Int>() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    fun enter(node: Int) {
        backStack.add(focus)
        focus = node
        selected = -1
        selection.clear()
    }

    BackHandler(enabled = true) {
        when {
            selection.isNotEmpty() -> selection.clear()
            backStack.isNotEmpty() -> {
                focus = backStack.removeAt(backStack.lastIndex)
                selected = -1
            }

            else -> onRescan()
        }
    }

    LaunchedEffect(focus, tab, version) {
        if (version >= 0) vm.rememberPosition(store.pathOf(focus), tab)
    }

    val crumb = remember(focus, version) {
        val list = ArrayList<Pair<Int, String>>()
        var cur = focus
        while (cur >= 0) {
            list.add(cur to store.nameOf(cur))
            cur = store.parents[cur]
        }
        list.reverse()
        list
    }

    val scan = result.result
    val atRoot = focus == 0
    val coverageNumbers = remember(version, atRoot, result.target) {
        if (atRoot && result.target.hasVolumeUsage) {
            Coverage.numbers(store.sizeOf(0), result.target.usedBytes)
        } else {
            null
        }
    }
    val coverageLine = coverageNumbers?.let {
        stringResource(R.string.coverage_read, formatBytes(it.read), formatBytes(it.used), it.percent)
    }
    val hiddenLine = if (coverageNumbers != null && !scan.cancelled && coverageNumbers.hidden > 0) {
        stringResource(R.string.coverage_hidden, formatBytes(coverageNumbers.hidden))
    } else {
        null
    }

    Column(Modifier.fillMaxSize()) {
        if (selection.isEmpty()) {
            Header(
                store = store,
                focus = focus,
                version = version,
                result = result,
                backStack = backStack,
                coverageLine = coverageLine,
                hiddenLine = hiddenLine,
                cancelled = scan.cancelled,
                onShowCoverage = { showCoverage = true },
                onNavigate = { node ->
                    selected = -1
                    if (node < 0) {
                        onRescan()
                    } else if (node != focus) {
                        backStack.add(focus)
                        focus = node
                        selection.clear()
                    }
                },
                crumb = crumb,
            )
        } else {
            SelectionBar(
                store = store,
                selection = selection,
                version = version,
                onClear = { selection.clear() },
                onDelete = { confirmDelete = true },
            )
        }

        TabRow(
            selectedTabIndex = tab,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Tab(selected = tab == TAB_TREEMAP, onClick = { tab = TAB_TREEMAP }, text = { Text(stringResource(R.string.tab_treemap)) })
            Tab(selected = tab == TAB_CONTENTS, onClick = { tab = TAB_CONTENTS }, text = { Text(stringResource(R.string.tab_contents)) })
            Tab(selected = tab == TAB_LARGEST, onClick = { tab = TAB_LARGEST }, text = { Text(stringResource(R.string.tab_largest)) })
            Tab(selected = tab == TAB_TYPES, onClick = { tab = TAB_TYPES }, text = { Text(stringResource(R.string.tab_types)) })
            Tab(selected = tab == TAB_FIND, onClick = { tab = TAB_FIND }, text = { Text(stringResource(R.string.tab_find)) })
        }
        Spacer(Modifier.height(6.dp))

        when (tab) {
            TAB_TREEMAP -> Box(Modifier.weight(1f)) {
                TreemapCanvas(
                    store = store,
                    root = focus,
                    version = version,
                    darkTheme = androidx.compose.foundation.isSystemInDarkTheme(),
                    onNodeTap = { node, aggregate ->
                        when {
                            // The block stands for items too small to draw, so listing them is the
                            // only useful action; re-entering the same folder would loop.
                            aggregate -> tab = TAB_CONTENTS
                            store.isDir(node) -> enter(node)
                            else -> {
                                selected = node
                                showDetails = true
                            }
                        }
                    },
                    onNodeLongPress = { node, aggregate ->
                        if (aggregate) tab = TAB_CONTENTS else {
                            selected = node
                            showDetails = true
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (store.childCount(focus) == 0) {
                    Text(
                        if (store.sizeOf(focus) == 0L) stringResource(R.string.empty_folder)
                        else stringResource(R.string.no_itemized_children),
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            TAB_CONTENTS -> ContentsList(
                store = store,
                focus = focus,
                version = version,
                sortMode = sortMode,
                onSortChange = { sortMode = it },
                selection = selection,
                onEnter = { enter(it) },
                onDetails = { selected = it; showDetails = true },
                modifier = Modifier.weight(1f),
            )

            TAB_LARGEST -> LargestList(
                store = store,
                version = version,
                selection = selection,
                onDetails = { selected = it; showDetails = true },
                modifier = Modifier.weight(1f),
            )

            TAB_TYPES -> TypesList(
                store = store,
                version = version,
                onFilterType = { ext ->
                    query = ext
                    tab = TAB_FIND
                },
                modifier = Modifier.weight(1f),
            )

            else -> FindList(
                store = store,
                version = version,
                query = query,
                onQueryChange = { query = it },
                selection = selection,
                onDetails = { selected = it; showDetails = true },
                modifier = Modifier.weight(1f),
            )
        }

        Footer(scan = scan, deep = result.deep, store = store, version = version)
    }

    if (showCoverage) {
        CoverageDialog(
            scanned = store.sizeOf(0),
            used = result.target.usedBytes,
            total = result.target.totalBytes,
            cancelled = scan.cancelled,
            deniedPaths = scan.errorSamples,
            deniedCount = scan.errors,
            deep = result.deep,
            onDismiss = { showCoverage = false },
        )
    }

    if (showDetails && selected >= 0 && selected < store.count) {
        val canDelete = remember(selected, version) { vm.canDelete(selected) }
        NodeDetailsDialog(
            store = store,
            node = selected,
            version = version,
            canDelete = canDelete,
            onDismiss = { showDetails = false },
            onOpen = {
                if (!vm.openNode(selected)) {
                    android.widget.Toast.makeText(ctx, R.string.open_failed, android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onShare = {
                if (!vm.shareNode(selected)) {
                    android.widget.Toast.makeText(ctx, R.string.share_failed, android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onDelete = { confirmDelete = true },
            onShowInFolder = {
                showDetails = false
                val parent = store.parents[selected]
                if (parent >= 0) enter(parent)
            },
            onEnter = {
                showDetails = false
                if (store.isDir(selected)) enter(selected)
            },
        )
    }

    if (confirmDelete && (selection.isNotEmpty() || selected >= 0)) {
        DeleteDialog(
            store = store,
            targets = if (selection.isNotEmpty()) selection.toList() else listOf(selected),
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                showDetails = false
                val nodes = if (selection.isNotEmpty()) selection.toList() else listOf(selected)
                selection.clear()
                scope.launch {
                    var freed = 0L
                    var failed = 0
                    var firstError: String? = null
                    for (node in nodes) {
                        val report = vm.deleteNode(node)
                        freed += report.freedBytes
                        failed += report.failed
                        if (firstError == null) firstError = report.firstError
                        if (focus >= store.count || store.isDeleted(focus)) {
                            focus = 0
                            backStack.clear()
                        }
                    }
                    selected = -1
                    val message = when {
                        freed > 0 && failed == 0 -> ctx.getString(R.string.delete_freed, formatBytes(freed))
                        freed > 0 -> ctx.resources.getQuantityString(R.plurals.delete_partial, failed, formatBytes(freed), failed)
                        firstError == "Not found" -> ctx.getString(R.string.delete_already_gone)
                        else -> ctx.getString(
                            R.string.delete_failed,
                            firstError ?: ctx.getString(R.string.delete_failed_unknown),
                        )
                    }
                    android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_LONG).show()
                }
            },
        )
    }
}

@Composable
private fun Header(
    store: NodeStore,
    focus: Int,
    version: Int,
    result: ScanState.Done,
    backStack: List<Int>,
    coverageLine: String?,
    hiddenLine: String?,
    cancelled: Boolean,
    onShowCoverage: () -> Unit,
    onNavigate: (Int) -> Unit,
    crumb: List<Pair<Int, String>>,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                store.nameOf(focus).ifEmpty { result.target.path },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val childCount = remember(version, focus) { store.childCount(focus) }
            val focusBytes = remember(version, focus) { store.sizeOf(focus) }
            Text(
                stringResource(
                    R.string.item_count,
                    formatBytes(focusBytes),
                    pluralStringResource(R.plurals.items_count, childCount, childCount),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = {
            if (backStack.isNotEmpty()) onNavigate(backStack.last()) else onNavigate(-1)
        }) {
            Text(
                stringResource(
                    if (backStack.isEmpty()) R.string.home else R.string.up,
                )
            )
        }
    }

    if (coverageLine != null) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { onShowCoverage() }
                .padding(horizontal = 12.dp, vertical = 2.dp),
        ) {
            Text(
                coverageLine,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hiddenLine != null) {
                Text(
                    stringResource(R.string.coverage_tap, hiddenLine),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (cancelled) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                stringResource(R.string.cancelled_banner),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }

    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((i, entry) in crumb.withIndex()) {
            if (i > 0) {
                Text(
                    if (rtl) "‹" else "›",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            Text(
                entry.second.ifEmpty { "/" },
                style = MaterialTheme.typography.labelMedium,
                color = if (entry.first == focus) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable { if (entry.first != focus) onNavigate(entry.first) }
                    .padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun SelectionBar(
    store: NodeStore,
    selection: List<Int>,
    version: Int,
    onClear: () -> Unit,
    onDelete: () -> Unit,
) {
    val bytes = remember(selection.toList(), version) { selection.sumOf { store.sizeOf(it) } }
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            pluralStringResource(
                R.plurals.selected_count,
                selection.size,
                selection.size,
                formatBytes(bytes),
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClear) { Text(stringResource(R.string.clear_selection)) }
        TextButton(onClick = onDelete) {
            Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun Footer(
    scan: dev.handywibowo.diskquilt.scan.ScanResult,
    deep: DeepOutcome,
    store: NodeStore,
    version: Int,
) {
    // NodeStore is a plain mutable object, so its counters are only observed when the read is keyed
    // on the version that markDeleted()/shrink() bump.
    val files = remember(version) { store.fileCount }
    val dirs = remember(version) { store.dirCount }
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            stringResource(
                R.string.footer_counts,
                pluralStringResource(R.plurals.files_count, files, files),
                formatCount(dirs.toLong()),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            footerStatus(scan, deep),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** `root +14 GB · 2 denied · 59s`; only the parts that apply. */
private fun footerStatus(scan: dev.handywibowo.diskquilt.scan.ScanResult, deep: DeepOutcome): String {
    val parts = ArrayList<String>(4)
    if (deep.used) {
        // Report what the privileged pass actually achieved, not just that it ran.
        parts.add(
            when (deep.status) {
                dev.handywibowo.diskquilt.deep.DeepStatus.COMPLETE -> "root ${formatBytes(deep.bytes)}"
                dev.handywibowo.diskquilt.deep.DeepStatus.PARTIAL -> "root partial (${deep.errors})"
                dev.handywibowo.diskquilt.deep.DeepStatus.CANCELLED -> "root cancelled"
                else -> "root ${formatBytes(deep.bytes)}"
            }
        )
    }
    if (scan.errors > 0) parts.add("${scan.errors} denied")
    parts.add(formatDuration(scan.elapsedMs))
    return parts.joinToString(" · ")
}

@Composable
private fun SortChips(mode: SortMode, onChange: (SortMode) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.sort_by),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SortMode.entries.forEach { m ->
            FilterChip(
                selected = mode == m,
                onClick = { onChange(m) },
                label = {
                    Text(
                        stringResource(
                            when (m) {
                                SortMode.SIZE -> R.string.sort_size
                                SortMode.NAME -> R.string.sort_name
                                SortMode.DATE -> R.string.sort_date
                                SortMode.TYPE -> R.string.sort_type
                            }
                        )
                    )
                },
            )
        }
    }
}

@Composable
private fun CoverageDialog(
    scanned: Long,
    used: Long,
    total: Long,
    cancelled: Boolean,
    deniedPaths: List<String>,
    deniedCount: Int,
    deep: DeepOutcome,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.coverage_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow(stringResource(R.string.coverage_scanned), formatBytes(scanned))
                DetailRow(stringResource(R.string.coverage_used), formatBytes(used))
                DetailRow(
                    stringResource(R.string.coverage_unreachable),
                    if (cancelled) stringResource(R.string.coverage_cancelled_value)
                    else formatBytes(Coverage.hidden(scanned, used)),
                )
                if (total > 0) DetailRow(stringResource(R.string.coverage_size), formatBytes(total))
                Text(
                    stringResource(
                        if (cancelled) R.string.coverage_cancelled_explanation
                        else R.string.coverage_explanation,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (deep.used) {
                    Text(
                        stringResource(R.string.coverage_deep_added, formatBytes(deep.bytes)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (deniedPaths.isNotEmpty()) {
                    Text(
                        stringResource(R.string.coverage_denied_folders, deniedCount),
                        fontWeight = FontWeight.SemiBold,
                    )
                    for (p in deniedPaths) {
                        Text(
                            p,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ContentsList(
    store: NodeStore,
    focus: Int,
    version: Int,
    sortMode: SortMode,
    onSortChange: (SortMode) -> Unit,
    selection: MutableList<Int>,
    onEnter: (Int) -> Unit,
    onDetails: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val children = remember(focus, version, sortMode) { store.childrenSorted(focus, sortMode) }
    val total = store.sizeOf(focus).coerceAtLeast(1)
    Column(modifier.fillMaxSize()) {
        SortChips(sortMode, onSortChange)
        if (children.isEmpty()) {
            EmptyState(title = stringResource(R.string.empty_folder))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(children.toList()) { node ->
                SelectableRow(
                    store = store,
                    node = node,
                    selected = selection.contains(node),
                    inSelectionMode = selection.isNotEmpty(),
                    progress = store.sizeOf(node).toFloat() / total.toFloat(),
                    subtitle = null,
                    onClick = {
                        if (selection.isNotEmpty()) {
                            toggle(selection, node)
                        } else if (store.isDir(node)) {
                            onEnter(node)
                        } else {
                            onDetails(node)
                        }
                    },
                    onLongClick = { toggle(selection, node) },
                )
            }
        }
    }
}

@Composable
private fun LargestList(
    store: NodeStore,
    version: Int,
    selection: MutableList<Int>,
    onDetails: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val files = remember(version) { store.largestFiles(300) }
    if (files.isEmpty()) {
        EmptyState(title = stringResource(R.string.no_files), modifier = modifier)
        return
    }
    val maxSize = store.sizeOf(files.first()).coerceAtLeast(1)
    LazyColumn(modifier.fillMaxSize()) {
        items(files.toList()) { node ->
            SelectableRow(
                store = store,
                node = node,
                selected = selection.contains(node),
                inSelectionMode = selection.isNotEmpty(),
                progress = store.sizeOf(node).toFloat() / maxSize.toFloat(),
                subtitle = store.pathOf(store.parents[node]),
                onClick = {
                    if (selection.isNotEmpty()) toggle(selection, node) else onDetails(node)
                },
                onLongClick = { toggle(selection, node) },
            )
        }
    }
}

@Composable
private fun FindList(
    store: NodeStore,
    version: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    selection: MutableList<Int>,
    onDetails: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var results by remember { mutableStateOf<IntArray>(IntArray(0)) }
    LaunchedEffect(query, version) {
        results = withContext(Dispatchers.Default) { TreeQueries.search(store, query) }
    }
    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        when {
            query.isBlank() -> Text(
                stringResource(R.string.search_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp),
            )

            results.isEmpty() -> EmptyState(
                title = stringResource(R.string.search_no_results, query),
                body = stringResource(R.string.empty_no_matches_body),
            )

            else -> {
                Text(
                    stringResource(R.string.search_results, formatCount(results.size.toLong())),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                )
                val maxSize = store.sizeOf(results.first()).coerceAtLeast(1)
                LazyColumn(Modifier.fillMaxSize()) {
                    items(results.toList()) { node ->
                        SelectableRow(
                            store = store,
                            node = node,
                            selected = selection.contains(node),
                            inSelectionMode = selection.isNotEmpty(),
                            progress = store.sizeOf(node).toFloat() / maxSize.toFloat(),
                            subtitle = store.pathOf(store.parents[node]),
                            onClick = {
                                if (selection.isNotEmpty()) toggle(selection, node) else onDetails(node)
                            },
                            onLongClick = { toggle(selection, node) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TypesList(
    store: NodeStore,
    version: Int,
    onFilterType: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var types by remember { mutableStateOf<List<TypeStat>>(emptyList()) }
    LaunchedEffect(version) {
        types = withContext(Dispatchers.Default) { TreeQueries.types(store) }
    }
    val totalBytes = remember(types) { types.sumOf { it.bytes }.coerceAtLeast(1) }
    if (types.isEmpty()) {
        EmptyState(title = stringResource(R.string.types_empty), modifier = modifier)
        return
    }
    LazyColumn(modifier.fillMaxSize()) {
        items(types) { stat ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { if (stat.extension != "(none)") onFilterType(stat.extension) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        ".${stat.extension}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${stat.count} files",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(3.dp))
                    LinearProgressIndicator(
                        progress = { stat.bytes.toFloat() / totalBytes.toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        formatBytes(stat.bytes),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        formatPercent(stat.bytes, totalBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SelectableRow(
    store: NodeStore,
    node: Int,
    selected: Boolean,
    inSelectionMode: Boolean,
    progress: Float,
    subtitle: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (inSelectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        } else {
            TypeBadge(store, node)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                store.nameOf(node),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(3.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = if (store.isDir(node)) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.secondary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            formatBytes(store.sizeOf(node)),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

private fun toggle(selection: MutableList<Int>, node: Int) {
    if (!selection.remove(node)) selection.add(node)
}

@Composable
private fun DeleteDialog(
    store: NodeStore,
    targets: List<Int>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val bytes = targets.sumOf { store.sizeOf(it) }
    val single = targets.size == 1
    val name = if (single) store.nameOf(targets.first()) else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (single && name != null) {
                    val isDir = store.isDir(targets.first())
                    Text(
                        stringResource(
                            if (isDir) R.string.delete_body_folder else R.string.delete_body_file,
                            name,
                            formatBytes(bytes),
                        )
                    )
                    Text(
                        store.pathOf(targets.first()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        pluralStringResource(
                            R.plurals.delete_batch,
                            targets.size,
                            targets.size,
                            formatBytes(bytes),
                        )
                    )
                }
                Text(
                    stringResource(R.string.delete_no_undo),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun NodeDetailsDialog(
    store: NodeStore,
    node: Int,
    version: Int,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onEnter: () -> Unit,
    onShowInFolder: () -> Unit,
) {
    val isDir = store.isDir(node)
    val size = store.sizeOf(node)
    val path = store.pathOf(node)
    val modified = remember(node, version) { formatDateTime(store.mtimeOf(node)) }
    val total = store.sizeOf(0).coerceAtLeast(1)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                store.nameOf(node).ifEmpty { "/" },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    formatBytes(size),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                DetailRow(
                    stringResource(R.string.size),
                    stringResource(R.string.size_of_scan, formatBytes(size), formatPercent(size, total)),
                )
                if (isDir) {
                    DetailRow(
                        stringResource(R.string.items),
                        formatCount(store.childCount(node).toLong()),
                    )
                }
                DetailRow(stringResource(R.string.modified), modified)
                if (store.isLink(node)) {
                    DetailRow(stringResource(R.string.type), stringResource(R.string.symlink_not_followed))
                }
                DetailRow(stringResource(R.string.path), path)
                if (!canDelete) {
                    Text(
                        stringResource(R.string.cannot_delete_here),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            // Two rows: four buttons in one row overflow once the labels are translated.
            Column(horizontalAlignment = Alignment.End) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = if (isDir) onEnter else onOpen) {
                        Text(stringResource(R.string.open))
                    }
                    TextButton(onClick = onShowInFolder) { Text(stringResource(R.string.folder)) }
                    TextButton(onClick = onShare) { Text(stringResource(R.string.share)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { onDismiss(); onDelete() }, enabled = canDelete) {
                        Text(
                            stringResource(R.string.delete),
                            color = if (canDelete) MaterialTheme.colorScheme.error else Color.Unspecified,
                        )
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** Small extension/directory badge, replacing the uninformative colour square. */
@Composable
private fun TypeBadge(store: NodeStore, node: Int) {
    val isDir = store.isDir(node)
    val category = if (isDir) {
        FileCategory.FOLDER
    } else {
        FileCategory.of(store.nameOf(node), false)
    }
    val label = if (isDir) "DIR" else TreeQueries.extensionOf(store.nameOf(node)).take(3).uppercase()
    val color = categoryColor(category, 1, androidx.compose.foundation.isSystemInDarkTheme())
    Box(
        Modifier
            .width(30.dp)
            .height(18.dp)
            .background(color, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF0B0F12),
            maxLines = 1,
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
