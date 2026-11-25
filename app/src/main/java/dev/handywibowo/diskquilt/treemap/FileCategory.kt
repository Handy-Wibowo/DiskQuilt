package dev.handywibowo.diskquilt.treemap

/**
 * File categories used for treemap colouring.
 *
 * Colour previously hashed the node index, which made the treemap a rainbow with no meaning.
 * Category colour at least answers "what kind of thing is eating my storage".
 */
enum class FileCategory {
    VIDEO,
    IMAGE,
    AUDIO,
    APP,
    ARCHIVE,
    DOCUMENT,
    FOLDER,
    OTHER,
    ;

    companion object {
        private val BY_EXTENSION: Map<String, FileCategory> = buildMap {
            fun put(category: FileCategory, vararg extensions: String) {
                for (e in extensions) put(e, category)
            }
            put(
                FileCategory.VIDEO,
                "mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "ts", "flv", "wmv",
            )
            put(
                FileCategory.IMAGE,
                "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tif", "tiff", "svg", "avif",
            )
            put(
                FileCategory.AUDIO,
                "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "amr", "wma", "mid",
            )
            put(FileCategory.APP, "apk", "apks", "xapk", "aab", "obb", "dex", "so")
            put(
                FileCategory.ARCHIVE,
                "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst", "iso", "tgz",
            )
            put(
                FileCategory.DOCUMENT,
                "pdf", "epub", "mobi", "azw3", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
                "txt", "log", "md", "csv", "json", "xml", "html", "ini", "conf",
            )
        }

        /** Directory names that usually mean media, so a folder reads as its content type. */
        private val BY_FOLDER_NAME: Map<String, FileCategory> = mapOf(
            "dcim" to FileCategory.IMAGE,
            "camera" to FileCategory.IMAGE,
            "pictures" to FileCategory.IMAGE,
            "screenshots" to FileCategory.IMAGE,
            "movies" to FileCategory.VIDEO,
            "videos" to FileCategory.VIDEO,
            "music" to FileCategory.AUDIO,
            "podcasts" to FileCategory.AUDIO,
            "download" to FileCategory.ARCHIVE,
            "downloads" to FileCategory.ARCHIVE,
            "documents" to FileCategory.DOCUMENT,
            "whatsapp" to FileCategory.IMAGE,
        )

        fun of(name: String, isDir: Boolean): FileCategory {
            if (isDir) {
                return BY_FOLDER_NAME[name.lowercase()] ?: FileCategory.FOLDER
            }
            val dot = name.lastIndexOf('.')
            if (dot <= 0 || dot == name.length - 1) return FileCategory.OTHER
            val ext = name.substring(dot + 1).lowercase()
            return BY_EXTENSION[ext] ?: FileCategory.OTHER
        }
    }
}
