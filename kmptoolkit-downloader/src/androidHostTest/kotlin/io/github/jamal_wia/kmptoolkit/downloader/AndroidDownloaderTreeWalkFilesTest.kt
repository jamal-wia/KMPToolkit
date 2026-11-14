package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/**
 * The Android storage's tree walks (resource size, resource delete) go through kmptoolkit-files and never follow a
 * symbolic link: a link inside a resource directory counts zero bytes, is removed as a link, and its target and a
 * link cycle are left alone.
 */
@RunWith(AndroidJUnit4::class)
class AndroidDownloaderTreeWalkFilesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "tree_walk_files")
    private val outside: File = File(context.filesDir, "tree_walk_outside")

    private class Group : ResourceGroup {
        override val key: String = "g"
        override var units: List<DownloadUnit> = emptyList()
    }

    private class Unit1(override val group: ResourceGroup) : DownloadUnit {
        override val id: String = "dir-unit"
        override val relativePath: String = "models"
        override val apiPath: String = "test/dir-unit"
    }

    @AfterTest
    fun cleanUp() {
        File(context.filesDir, config.baseDirectoryName).deleteRecursively()
        outside.deleteRecursively()
    }

    private fun link(at: File, target: File): Boolean = try {
        Files.createSymbolicLink(at.toPath(), target.toPath())
        true
    } catch (e: Exception) {
        false
    }

    @Test
    fun `a resource directory with links counts only its own files and is deleted without touching a link target`() {
        val storage: DownloaderStorage = createDownloaderStorage(context, config)
        val unit = Unit1(Group())
        val resource = File(storage.getResourcePath(unit))
        resource.mkdirs()
        File(resource, "own.bin").writeBytes(ByteArray(10))
        outside.mkdirs()
        File(outside, "precious.bin").writeBytes(ByteArray(1_000))
        assumeTrue("symbolic links are not available on this host", link(File(resource, "out"), outside))
        assertTrue(link(File(resource, "loop"), resource))

        assertEquals(10L, storage.getResourceSize(unit), "a link counts zero and a cycle does not recurse")

        storage.deleteResource(unit)

        assertFalse(resource.exists(), "the resource directory is gone")
        assertTrue(File(outside, "precious.bin").isFile, "the target of a link inside the resource was followed and deleted")
        assertEquals(1_000L, File(outside, "precious.bin").length())
    }

    @Test
    fun `deleting an absent resource and sizing it are harmless`() {
        val storage: DownloaderStorage = createDownloaderStorage(context, config)
        val unit = Unit1(Group())

        assertEquals(0L, storage.getResourceSize(unit))
        storage.deleteResource(unit)
    }
}
