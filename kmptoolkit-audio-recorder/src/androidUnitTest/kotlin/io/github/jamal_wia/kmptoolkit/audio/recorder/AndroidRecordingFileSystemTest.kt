package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * [AndroidRecordingFileSystem] against a real (Robolectric) `Context` and a real filesystem — the
 * one part of the Android side that is not a call straight through to `MediaRecorder`, and the part
 * the common tests can only exercise through a fake.
 */
@RunWith(AndroidJUnit4::class)
class AndroidRecordingFileSystemTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fileSystem = AndroidRecordingFileSystem(context)

    @Test
    fun `the app private directory is the app's own files directory`() {
        assertEquals(context.filesDir.absolutePath, fileSystem.appPrivateDirectory())
    }

    @Test
    fun `the application identifier is the consumer's package name`() {
        assertEquals(context.packageName, fileSystem.applicationIdentifier())
    }

    @Test
    fun `resolve joins a directory and a name`() {
        assertEquals("/a/b/c.m4a", fileSystem.resolve("/a/b", "c.m4a"))
    }

    @Test
    fun `parentOf reports the directory holding a file`() {
        assertEquals("/a/b", fileSystem.parentOf("/a/b/c.m4a"))
    }

    @Test
    fun `parentOf reports null for a bare file name`() {
        assertEquals(null, fileSystem.parentOf("c.m4a"))
    }

    @Test
    fun `ensureWritableDirectory creates missing intermediate directories`() {
        val target = File(context.filesDir, "deeply/nested/recordings")
        assertFalse(target.exists())

        assertTrue(fileSystem.ensureWritableDirectory(target.absolutePath))

        assertTrue(target.isDirectory)
    }

    @Test
    fun `ensureWritableDirectory accepts a directory that already exists`() {
        val target = File(context.filesDir, "existing").apply { mkdirs() }

        assertTrue(fileSystem.ensureWritableDirectory(target.absolutePath))
    }

    @Test
    fun `ensureWritableDirectory refuses a path occupied by a file`() {
        val occupied = File(context.filesDir, "not-a-directory").apply { writeText("x") }

        assertFalse(fileSystem.ensureWritableDirectory(occupied.absolutePath))
    }

    @Test
    fun `free space is reported for a directory that exists`() {
        assertTrue(fileSystem.freeSpaceBytes(context.filesDir.absolutePath) > 0)
    }

    @Test
    fun `delete removes an existing file`() {
        val file = File(context.filesDir, "doomed.m4a").apply { writeText("audio") }

        fileSystem.delete(file.absolutePath)

        assertFalse(file.exists())
    }

    @Test
    fun `deleting a file that is not there is not an error`() {
        fileSystem.delete(File(context.filesDir, "never-existed.m4a").absolutePath)
    }
}
