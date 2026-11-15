package io.github.jamal_wia.kmptoolkit.files

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume

/**
 * T10 on a real volume without permissions: a directory on a FAT or exFAT volume (a loop-mounted image on Linux, a
 * virtual disk on Windows) must take every `ownerOnly` request without an error, because the volume has nowhere to store
 * the mode or the ACL and disregards it. The volume is created by hand, outside the test: pass its mount point as
 * `-Pkmptoolkit.test.fatVolumeDirectory=<directory>` (the build hands every `kmptoolkit.test.*` Gradle property to the
 * test JVM). Skipped, with this reason, when it is not given: the plan names this check, and a run without a volume
 * proves nothing about it.
 *
 * Asserted: success and intact content. Not asserted: any mode, because what a FAT mount reports is decided by its
 * mount options, not by the request.
 */
class OwnerOnlyFatVolumeT10Test {
    private var base: File? = null

    @BeforeTest
    fun setUp() {
        val given: String? = System.getProperty(FAT_DIRECTORY_PROPERTY)
        Assume.assumeTrue(
            "needs a FAT or exFAT directory: pass -P$FAT_DIRECTORY_PROPERTY=<mounted FAT directory> (see the class comment)",
            !given.isNullOrBlank(),
        )
        val volume: File = File(given!!)
        Assume.assumeTrue("$FAT_DIRECTORY_PROPERTY=$given is not a writable directory", volume.isDirectory && volume.canWrite())
        base = Files.createTempDirectory(volume.toPath(), "kmptoolkit-owner-only").toFile()
    }

    @AfterTest
    fun tearDown() {
        base?.deleteRecursively()
    }

    private fun path(vararg segments: String): String = segments.fold(requireNotNull(base)) { parent: File, name: String -> File(parent, name) }.path

    @Test
    fun `an ownerOnly write on a volume without permissions succeeds and keeps the content`() {
        val files: FileAccess = createFileAccess()
        val written: FileResult<Unit> = files.writeAtomically(path("f"), byteArrayOf(1, 2, 3), WriteOptions(ownerOnly = true))
        assertTrue(written.isSuccess, "$written")
        assertContentEquals(byteArrayOf(1, 2, 3), File(path("f")).readBytes())
        val replaced: FileResult<Unit> = files.writeAtomically(path("f"), byteArrayOf(4), WriteOptions(ownerOnly = true))
        assertTrue(replaced.isSuccess, "$replaced")
        assertContentEquals(byteArrayOf(4), File(path("f")).readBytes())
        assertEquals(listOf("f"), requireNotNull(base).list()!!.toList(), "no temp file is left behind")
    }

    @Test
    fun `ownerOnly directories and parents on a volume without permissions are created without an error`() {
        val files: FileAccess = createFileAccess()
        assertTrue(files.createDirectories(path("a", "b"), durable = true, ownerOnly = true).isSuccess)
        assertTrue(File(path("a", "b")).isDirectory)
        val options: WriteOptions = WriteOptions(createParentDirectories = true, tempDirectory = path("scratch"), ownerOnly = true)
        val written: FileResult<Unit> = files.writeAtomically(path("c", "d", "f"), byteArrayOf(9), options)
        assertTrue(written.isSuccess, "$written")
        assertContentEquals(byteArrayOf(9), File(path("c", "d", "f")).readBytes())
    }

    private companion object {
        const val FAT_DIRECTORY_PROPERTY: String = "kmptoolkit.test.fatVolumeDirectory"
    }
}
