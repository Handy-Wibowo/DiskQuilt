package dev.handywibowo.diskquilt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.handywibowo.diskquilt.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.handywibowo.diskquilt.scan.ScanProgress
import dev.handywibowo.diskquilt.scan.Volume

@Composable
fun HomeScreen(
    volumes: List<Volume>,
    hasAccess: Boolean,
    rootAvailable: Boolean,
    deepEnabled: Boolean,
    recent: dev.handywibowo.diskquilt.data.RecentScan?,
    versionName: String,
    onToggleDeep: (Boolean) -> Unit,
    onCheckRoot: () -> Unit,
    onResumeRecent: (ScanTarget) -> Unit,
    onForgetRecent: () -> Unit,
    onRequestAccess: () -> Unit,
    onScan: (ScanTarget) -> Unit,
) {
    var manualPath by remember { mutableStateOf("") }
    var showAbout by remember { mutableStateOf(false) }

    // One list for the whole screen: sections no longer fight over a weighted child, so the layout
    // holds together when the recent card, the explainer and a long volume list are all present.
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("DiskQuilt", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        item {
            Text(
                stringResource(R.string.home_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!hasAccess) {
            item { AccessExplainer(onRequestAccess) }
        }
        if (recent != null) {
            item {
                val target = volumes.firstOrNull { it.path == recent.path }
                    ?.let { ScanTargets.volume(it) }
                    ?: ScanTargets.folder(recent.path)
                RecentScanCard(
                    recent = recent,
                    now = System.currentTimeMillis(),
                    onResume = { onResumeRecent(target) },
                    onForget = onForgetRecent,
                )
            }
        }

        item { SectionLabel(stringResource(R.string.volumes)) }
        if (volumes.isEmpty()) {
            item {
                Text(
                    stringResource(
                        if (hasAccess) R.string.volumes_empty_granted else R.string.volumes_empty_denied
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(volumes) { v ->
            VolumeRow(v, enabled = hasAccess) { onScan(ScanTargets.volume(v)) }
        }

        item {
            AdvancedRootSection(
                rootAvailable = rootAvailable,
                deepEnabled = deepEnabled,
                onToggleDeep = onToggleDeep,
                onCheckRoot = onCheckRoot,
            )
        }

        item { SectionLabel(stringResource(R.string.scan_specific_folder)) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manualPath,
                    onValueChange = { manualPath = it },
                    singleLine = true,
                    enabled = hasAccess,
                    placeholder = { Text(stringResource(R.string.folder_path_hint)) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { if (manualPath.isNotBlank()) onScan(ScanTargets.folder(manualPath.trim())) },
                    enabled = hasAccess && manualPath.isNotBlank(),
                ) { Text(stringResource(R.string.scan)) }
            }
        }
        item {
            TextButton(onClick = { showAbout = true }) { Text(stringResource(R.string.about)) }
        }
    }

    if (showAbout) AboutDialog(versionName, onDismiss = { showAbout = false })
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

/**
 * The all-files permission is unusual enough that a bare jump to Settings reads as a scam. This
 * spells out the three steps, says why scoped access is not enough, and states that nothing leaves
 * the device.
 */
@Composable
private fun AccessExplainer(onRequestAccess: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.permission_title), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.permission_why),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StepLine("1", stringResource(R.string.permission_step1))
            StepLine("2", stringResource(R.string.permission_step2))
            StepLine("3", stringResource(R.string.permission_step3))
            Text(
                stringResource(R.string.permission_privacy),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Button(onClick = onRequestAccess) { Text(stringResource(R.string.permission_action)) }
        }
    }
}

@Composable
private fun StepLine(number: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                .padding(horizontal = 6.dp, vertical = 1.dp)
        ) {
            Text(
                number,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun VolumeRow(v: Volume, enabled: Boolean, onScan: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onScan() }
            .alpha(if (enabled) 1f else 0.55f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(v.label, fontWeight = FontWeight.SemiBold)
                Text(
                    v.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (v.totalBytes > 0) {
                    Spacer(Modifier.height(6.dp))
                    val used = (v.totalBytes - v.freeBytes).coerceAtLeast(0)
                    LinearProgressIndicator(
                        progress = { used.toFloat() / v.totalBytes.toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(
                            R.string.volume_usage,
                            formatBytes(used),
                            formatBytes(v.totalBytes),
                            formatBytes(v.freeBytes),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    stringResource(R.string.scan),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * Collapsed by default and probed only when opened: on a rooted phone the probe can raise a
 * root-manager prompt, which must never happen because the app started.
 */
@Composable
private fun AdvancedRootSection(
    rootAvailable: Boolean,
    deepEnabled: Boolean,
    onToggleDeep: (Boolean) -> Unit,
    onCheckRoot: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val expandLabel = stringResource(R.string.expand)
    val collapseLabel = stringResource(R.string.collapse)

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        val opening = !expanded
                        expanded = opening
                        if (opening) onCheckRoot()
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.advanced_title), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.advanced_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (expanded) "▾" else "▸",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics {
                        contentDescription = if (expanded) collapseLabel else expandLabel
                    },
                )
            }

            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                if (rootAvailable) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.advanced_root_found),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = deepEnabled, onCheckedChange = onToggleDeep)
                    }
                } else {
                    Text(
                        stringResource(R.string.advanced_root_missing),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.advanced_root_prompt_warning),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onCheckRoot) {
                        Text(stringResource(R.string.advanced_check_root))
                    }
                }
            }
        }
    }
}

@Composable
fun ScanningScreen(
    progress: ScanProgress,
    target: ScanTarget,
    expectedFiles: Long?,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    // Keep the CPU awake while walking a large tree; released the moment the screen leaves.
    DisposableEffect(Unit) {
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val lock = power?.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "DiskQuilt:scan",
        )
        runCatching { lock?.acquire(10 * 60 * 1000L) }
        onDispose {
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }
    val elapsed = progress.elapsedMs.coerceAtLeast(1)
    val filesPerSecond = progress.files * 1000.0 / elapsed
    val bytesPerSecond = progress.bytes.toDouble() / elapsed * 1000.0
    val etaMs = expectedFiles
        ?.takeIf { it > progress.files && filesPerSecond > 0 }
        ?.let { ((it - progress.files) / filesPerSecond * 1000).toLong() }
    val percent = expectedFiles
        ?.takeIf { it > 0 }
        ?.let { (progress.files * 100 / it).toInt().coerceIn(0, 99) }
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.scanning),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            target.label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            target.path,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(
                R.string.scan_files_folders,
                formatCount(progress.files),
                formatCount(progress.dirs),
            ),
            fontWeight = FontWeight.Medium,
        )
        Text(
            stringResource(
                R.string.scan_bytes_elapsed,
                formatBytes(progress.bytes),
                formatDuration(progress.elapsedMs),
            )
        )
        Spacer(Modifier.height(4.dp))
        if (progress.elapsedMs >= 1000 && filesPerSecond > 0) {
            Text(
                stringResource(
                    R.string.scan_rate,
                    formatCount(filesPerSecond.toLong()),
                    formatBytes(bytesPerSecond.toLong()),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (etaMs != null && percent != null) {
                Text(
                    stringResource(
                        R.string.scan_eta,
                        percent,
                        formatDuration(etaMs),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                stringResource(R.string.scan_estimating),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            progress.currentPath,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
fun FailedScreen(
    message: String,
    target: ScanTarget?,
    onRetry: () -> Unit,
    onHome: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.scan_failed),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        if (target != null) {
            Text(
                target.path,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (target != null) Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            OutlinedButton(onClick = onHome) { Text(stringResource(R.string.choose_another_volume)) }
        }
    }
}
