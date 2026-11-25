package dev.handywibowo.diskquilt

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.handywibowo.diskquilt.scan.Volume
import dev.handywibowo.diskquilt.scan.Volumes
import dev.handywibowo.diskquilt.ui.DiskQuiltTheme
import dev.handywibowo.diskquilt.ui.FailedScreen
import dev.handywibowo.diskquilt.ui.HomeScreen
import dev.handywibowo.diskquilt.ui.ResultScreen
import dev.handywibowo.diskquilt.ui.ScanState
import dev.handywibowo.diskquilt.ui.ScanViewModel
import dev.handywibowo.diskquilt.ui.ScanningScreen
import dev.handywibowo.diskquilt.data.RecentScan

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DiskQuiltTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Root()
                    }
                }
            }
        }
    }
}

@Composable
private fun Root(vm: ScanViewModel = viewModel()) {
    val ctx = LocalContext.current
    var hasAccess by remember { mutableStateOf(Volumes.hasAllFilesAccess(ctx)) }
    var volumes by remember { mutableStateOf<List<Volume>>(emptyList()) }

    fun refresh() {
        hasAccess = Volumes.hasAllFilesAccess(ctx)
        volumes = Volumes.list(ctx)
    }

    val legacyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh() }

    fun requestAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${ctx.packageName}")
            )
            val ok = runCatching { ctx.startActivity(intent) }.isSuccess
            if (!ok) {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else {
            legacyPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    // The settings screen returns without a callback, so re-check on resume.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { refresh() }

    val state by vm.state.collectAsStateWithLifecycle()
    val rootAvailable by vm.rootAvailable.collectAsStateWithLifecycle()
    val deepEnabled by vm.deepEnabled.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val expectedFiles by vm.expectedFiles.collectAsStateWithLifecycle()
    val versionName = remember {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: ""
    }

    @Composable
    fun home() {
        HomeScreen(
            volumes = volumes,
            hasAccess = hasAccess,
            rootAvailable = rootAvailable,
            deepEnabled = deepEnabled,
            recent = recent,
            versionName = versionName,
            onToggleDeep = { vm.setDeepEnabled(it) },
            onCheckRoot = { vm.checkRoot() },
            onResumeRecent = { vm.resumeRecent(it) },
            onForgetRecent = { vm.forgetRecent() },
            onRequestAccess = { requestAccess() },
            onScan = { vm.startScan(it) },
        )
    }

    when (val s = state) {
        is ScanState.Idle -> home()

        is ScanState.Running -> ScanningScreen(
            progress = s.progress,
            target = s.target,
            expectedFiles = expectedFiles,
            onCancel = { vm.cancelScan() },
        )

        is ScanState.Done -> {
            val store by vm.store.collectAsStateWithLifecycle()
            val version by vm.version.collectAsStateWithLifecycle()
            val nodeStore = store
            if (nodeStore == null) {
                home()
            } else {
                ResultScreen(
                    vm = vm,
                    store = nodeStore,
                    version = version,
                    result = s,
                    onRescan = { vm.reset() },
                )
            }
        }

        is ScanState.Failed -> FailedScreen(
            message = s.message,
            target = s.target,
            onRetry = { s.target?.let { vm.startScan(it) } ?: vm.reset() },
            onHome = { vm.reset() },
        )
    }
}
