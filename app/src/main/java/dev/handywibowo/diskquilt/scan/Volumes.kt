package dev.handywibowo.diskquilt.scan

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

data class Volume(
    val label: String,
    val path: String,
    val totalBytes: Long,
    val freeBytes: Long,
    val removable: Boolean,
)

object Volumes {

    @SuppressLint("UsableSpace") // "free space now" is what a user means; getAllocatableBytes is a different question
    fun list(context: Context): List<Volume> {
        val out = LinkedHashMap<String, Volume>()
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        if (sm != null) {
            for (v in sm.storageVolumes) {
                val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) v.directory else null
                val path = dir?.absolutePath ?: continue
                if (!File(path).canRead()) continue
                out[path] = Volume(
                    label = v.getDescription(context),
                    path = path,
                    totalBytes = runCatching { dir.totalSpace }.getOrDefault(0L),
                    freeBytes = runCatching { dir.usableSpace }.getOrDefault(0L),
                    removable = v.isRemovable,
                )
            }
        }
        // StorageManager omits the emulated primary volume on some OEM builds.
        val primary = Environment.getExternalStorageDirectory()?.absolutePath
        if (primary != null && File(primary).canRead() && !out.containsKey(primary)) {
            out[primary] = Volume(
                label = "Internal storage",
                path = primary,
                totalBytes = File(primary).totalSpace,
                freeBytes = File(primary).usableSpace,
                removable = false,
            )
        }
        return out.values.toList()
    }

    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
}
