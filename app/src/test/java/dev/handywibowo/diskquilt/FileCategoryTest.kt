package dev.handywibowo.diskquilt

import dev.handywibowo.diskquilt.treemap.FileCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class FileCategoryTest {

    @Test
    fun mapsMediaExtensions() {
        assertEquals(FileCategory.VIDEO, FileCategory.of("trip.mp4", false))
        assertEquals(FileCategory.VIDEO, FileCategory.of("CLIP.MKV", false))
        assertEquals(FileCategory.IMAGE, FileCategory.of("IMG_0001.JPG", false))
        assertEquals(FileCategory.IMAGE, FileCategory.of("shot.webp", false))
        assertEquals(FileCategory.AUDIO, FileCategory.of("album.flac", false))
    }

    @Test
    fun mapsAppsArchivesDocuments() {
        assertEquals(FileCategory.APP, FileCategory.of("base.apk", false))
        assertEquals(FileCategory.ARCHIVE, FileCategory.of("backup.zip", false))
        assertEquals(FileCategory.DOCUMENT, FileCategory.of("manual.pdf", false))
        assertEquals(FileCategory.DOCUMENT, FileCategory.of("notes.txt", false))
    }

    @Test
    fun unknownAndExtensionlessAreOther() {
        assertEquals(FileCategory.OTHER, FileCategory.of("weird.qqq", false))
        assertEquals(FileCategory.OTHER, FileCategory.of("README", false))
        assertEquals(FileCategory.OTHER, FileCategory.of("trailingdot.", false))
        assertEquals(FileCategory.OTHER, FileCategory.of(".hidden", false))
    }

    @Test
    fun foldersUseContentHintWhenKnown() {
        assertEquals(FileCategory.IMAGE, FileCategory.of("DCIM", true))
        assertEquals(FileCategory.IMAGE, FileCategory.of("Camera", true))
        assertEquals(FileCategory.VIDEO, FileCategory.of("Movies", true))
        assertEquals(FileCategory.AUDIO, FileCategory.of("Music", true))
        assertEquals(FileCategory.FOLDER, FileCategory.of("some-random-dir", true))
    }
}
