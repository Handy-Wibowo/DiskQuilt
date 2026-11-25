package dev.handywibowo.diskquilt.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.handywibowo.diskquilt.data.RecentScan
import dev.handywibowo.diskquilt.data.RecentScanStore
import dev.handywibowo.diskquilt.R
import dev.handywibowo.diskquilt.deep.DeepScan
import dev.handywibowo.diskquilt.deep.DeepStatus
import dev.handywibowo.diskquilt.deep.RootShell
import dev.handywibowo.diskquilt.deep.ShellExecutor
import dev.handywibowo.diskquilt.scan.DeleteReport
import dev.handywibowo.diskquilt.scan.Deleter
import dev.handywibowo.diskquilt.scan.DiskScanner
import dev.handywibowo.diskquilt.scan.NodeStore
import dev.handywibowo.diskquilt.scan.ScanProgress
import dev.handywibowo.diskquilt.scan.ScanResult
import dev.handywibowo.diskquilt.scan.SubtreeRefresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/** What the optional root pass actually achieved. */
data class DeepOutcome(
    val used: Boolean,
    val files: Long,
    val bytes: Long,
    val errors: Int,
    val status: DeepStatus? = null,
    val message: String? = null,
) {
    companion object {
        val None = DeepOutcome(false, 0, 0, 0)
    }
}

sealed interface ScanState {
    data object Idle : ScanState
    data class Running(val progress: ScanProgress, val target: ScanTarget) : ScanState
    data class Done(
        val result: ScanResult,
        val target: ScanTarget,
        val deep: DeepOutcome,
    ) : ScanState

    data class Failed(val message: String, val target: ScanTarget?) : ScanState
}

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private val _store = MutableStateFlow<NodeStore?>(null)
    val store: StateFlow<NodeStore?> = _store.asStateFlow()

    /** Bumped whenever the tree mutates so composables recompute derived data. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private val _deepEnabled = MutableStateFlow(true)
    val deepEnabled: StateFlow<Boolean> = _deepEnabled.asStateFlow()

    private val rootShell = RootShell()
    private val _rootAvailable = MutableStateFlow(false)
    val rootAvailable: StateFlow<Boolean> = _rootAvailable.asStateFlow()

    private val recentStore = RecentScanStore(app)
    private val _recent = MutableStateFlow<RecentScan?>(null)
    val recent: StateFlow<RecentScan?> = _recent.asStateFlow()

    /** File count from the previous scan of this path, used only for an ETA estimate. */
    private val _expectedFiles = MutableStateFlow<Long?>(null)
    val expectedFiles: StateFlow<Long?> = _expectedFiles.asStateFlow()

    /** Focus path and tab to restore once the next scan of the same volume finishes. */
    private var restoreFocusPath: String? = null
    private var restoreTab: Int = 0

    private val cancelFlag = AtomicBoolean(false)
    private var job: Job? = null

    /** Incremented by every start/reset so a superseded scan cannot publish its result. */
    private var generation = 0

    /**
     * Probes for `su`. Deliberately not called at startup: on a rooted phone the probe can raise a
     * root-manager prompt, so it only runs when the user opens the advanced section.
     */
    fun checkRoot() {
        viewModelScope.launch(Dispatchers.IO) {
            _rootAvailable.value = rootShell.available(force = true)
        }
    }

    init {
        _recent.value = recentStore.load()
    }

    fun setDeepEnabled(enabled: Boolean) {
        _deepEnabled.value = enabled
    }

    /** Called by the result screen as the user navigates, so reopening lands in the same place. */
    fun rememberPosition(focusPath: String, tab: Int) {
        val current = _recent.value ?: return
        if (current.focusPath == focusPath && current.tab == tab) return
        val updated = current.copy(focusPath = focusPath, tab = tab)
        _recent.value = updated
        recentStore.save(updated)
    }

    fun forgetRecent() {
        _recent.value = null
        recentStore.clear()
    }

    /** Re-scans the volume from the last-scan card and restores the remembered location. */
    fun resumeRecent(scanTarget: ScanTarget) {
        val recent = _recent.value
        restoreFocusPath = recent?.focusPath
        restoreTab = recent?.tab ?: 0
        startScan(scanTarget)
    }

    /** One-shot: the result screen consumes the remembered position of a resumed scan. */
    fun takeRestore(): Pair<String?, Int> {
        val path = restoreFocusPath
        val tab = restoreTab
        restoreFocusPath = null
        restoreTab = 0
        return path to tab
    }

    fun startScan(target: ScanTarget, deep: Boolean = _deepEnabled.value) {
        if (_state.value is ScanState.Running) return
        // Accept "sdcard/Download" as well as "/sdcard/Download".
        val normalized = if (target.path.startsWith("/")) target.path else "/${target.path}"
        val scanTarget = target.copy(path = normalized)

        // A missing or unreadable root is a failure, not an empty scan: reporting "0 files" for a
        // typo would look like the volume is empty.
        val rootFile = File(normalized)
        val app = getApplication<Application>()
        if (!rootFile.exists()) {
            _state.value = ScanState.Failed(
                app.getString(R.string.scan_failed_not_found, normalized),
                scanTarget,
            )
            return
        }
        if (!rootFile.isDirectory) {
            _state.value = ScanState.Failed(
                app.getString(R.string.scan_failed_not_folder, normalized),
                scanTarget,
            )
            return
        }
        if (!rootFile.canRead()) {
            _state.value = ScanState.Failed(
                app.getString(R.string.scan_failed_unreadable, normalized),
                scanTarget,
            )
            return
        }

        _expectedFiles.value = _recent.value
            ?.takeIf { it.path == normalized && it.files > 0 }
            ?.files

        cancelFlag.set(false)
        val myGeneration = ++generation
        val store = NodeStore()
        _store.value = store
        _state.value = ScanState.Running(ScanProgress(0, 0, 0, normalized, 0), scanTarget)

        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val startedAt = System.currentTimeMillis()
                val result = DiskScanner(store, onProgress = { p ->
                    if (myGeneration == generation) {
                        _state.value = ScanState.Running(p, scanTarget)
                    }
                }).scan(normalized) { cancelFlag.get() || myGeneration != generation }

                var files = result.files
                var dirs = result.dirs
                var bytes = result.bytes
                var errors = result.errors

                // Second pass: the two directories the app uid is denied. Only possible with root.
                var deepOutcome = DeepOutcome.None
                // Cached flag, not a live probe: an unrooted phone must not exec `su` on every scan,
                // and a rooted phone must not block on a root-manager prompt mid-scan.
                val executor: ShellExecutor? =
                    if (deep && !result.cancelled && _rootAvailable.value) rootShell else null
                if (executor != null) {
                    var deepFiles = 0L
                    var deepBytes = 0L
                    var deepErrors = 0
                    var lastStatus: DeepStatus? = null
                    var lastMessage: String? = null
                    for (deepTarget in deepTargets(normalized)) {
                        if (cancelFlag.get() || myGeneration != generation) break
                        val node = store.findByPath(deepTarget)
                        if (node <= 0 || store.childCount(node) > 0) continue
                        val sub = DeepScan.expand(
                            store = store,
                            targetNode = node,
                            targetPath = deepTarget,
                            executor = executor,
                            isCancelled = { cancelFlag.get() || myGeneration != generation },
                        ) { f, d, b ->
                            if (myGeneration == generation) {
                                _state.value = ScanState.Running(
                                    ScanProgress(
                                        files + f,
                                        dirs + d,
                                        bytes + b,
                                        deepTarget,
                                        System.currentTimeMillis() - startedAt,
                                    ),
                                    scanTarget,
                                )
                            }
                        }
                        files += sub.files
                        dirs += sub.dirs
                        bytes += sub.bytes
                        errors += sub.errors
                        deepFiles += sub.files
                        deepBytes += sub.bytes
                        deepErrors += sub.errors
                        lastStatus = sub.status
                        lastMessage = sub.message
                    }
                    deepOutcome = DeepOutcome(
                        used = true,
                        files = deepFiles,
                        bytes = deepBytes,
                        errors = deepErrors,
                        status = lastStatus,
                        message = lastMessage,
                    )
                    if (myGeneration == generation) _version.value++
                }

                if (myGeneration != generation) return@launch
                val finishedAt = System.currentTimeMillis()
                _state.value = ScanState.Done(
                    result.copy(
                        files = files,
                        dirs = dirs,
                        bytes = bytes,
                        errors = errors,
                        elapsedMs = finishedAt - startedAt,
                    ),
                    scanTarget,
                    deepOutcome,
                )
                _version.value++

                if (!result.cancelled) {
                    val summary = RecentScan(
                        path = normalized,
                        label = scanTarget.label,
                        bytes = bytes,
                        files = files,
                        dirs = dirs,
                        finishedAt = finishedAt,
                        focusPath = normalized,
                        tab = 0,
                    )
                    _recent.value = summary
                    recentStore.save(summary)
                }
            } catch (t: Throwable) {
                if (myGeneration == generation) {
                    _state.value = ScanState.Failed(t.message ?: t::class.java.simpleName, scanTarget)
                }
            }
        }
    }

    /** `/Android/data` and `/Android/obb` directly under the scanned root. */
    private fun deepTargets(rootPath: String): List<String> {
        val root = rootPath.trimEnd('/')
        return listOf("$root/Android/data", "$root/Android/obb")
    }

    fun cancelScan() {
        cancelFlag.set(true)
    }

    fun reset() {
        generation++
        cancelFlag.set(true)
        job?.cancel()
        _state.value = ScanState.Idle
        _store.value = null
        _version.value++
    }

    /** True when the app uid may remove [node] from disk. */
    fun canDelete(node: Int): Boolean {
        val store = _store.value ?: return false
        if (node <= 0 || node >= store.count) return false
        return File(store.pathOf(node)).canWrite()
    }

    /**
     * Deletes [node] and reconciles the tree from what actually disappeared. A partial delete keeps
     * the node (its size shrinks by the freed bytes) instead of claiming the whole subtree is gone.
     */
    suspend fun deleteNode(node: Int): DeleteReport = withContext(Dispatchers.IO) {
        val store = _store.value ?: return@withContext DeleteReport(0, 0, 0, 0, "No scan loaded")
        if (node <= 0 || node >= store.count) {
            return@withContext DeleteReport(0, 0, 0, 0, "Invalid item")
        }
        val file = File(store.pathOf(node))
        if (!file.exists()) {
            return@withContext DeleteReport(0, 0, 0, 0, "Not found")
        }

        val report = Deleter.delete(file)
        if (report.anythingDeleted) {
            if (report.complete) {
                store.markDeleted(node)
            } else {
                // Some children survived: rebuild this folder so the tree matches the disk instead
                // of keeping deleted files listed with stale sizes and counters.
                SubtreeRefresh.refresh(store, node) { cancelFlag.get() }
            }
            _version.value++
        }
        report
    }

    /** Builds a shareable content:// URI for [node] so external viewers can open it. */
    fun openNode(node: Int): Boolean {
        val store = _store.value ?: return false
        val path = store.pathOf(node)
        val file = File(path)
        if (!file.exists()) return false
        val ctx = getApplication<Application>()
        val uri: Uri = try {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        } catch (e: IllegalArgumentException) {
            return false
        }
        val mime = runCatching { Files.probeContentType(file.toPath()) }.getOrNull()
            ?: guessMime(file.name)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { ctx.startActivity(intent) }.isSuccess
    }

    fun shareNode(node: Int): Boolean {
        val store = _store.value ?: return false
        val file = File(store.pathOf(node))
        if (!file.exists()) return false
        val ctx = getApplication<Application>()
        val uri = runCatching {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        }.getOrNull() ?: return false
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = runCatching { Files.probeContentType(file.toPath()) }.getOrNull() ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            ctx.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "flac" -> "audio/flac"
            "pdf" -> "application/pdf"
            "apk" -> "application/vnd.android.package-archive"
            "zip" -> "application/zip"
            "txt", "log", "md" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "text/xml"
            "epub" -> "application/epub+zip"
            else -> "*/*"
        }
    }
}
