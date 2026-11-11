package io.github.jamal_wia.kmptoolkit.audio.recorder.testing
// path-hygiene-file: logical-paths the test runs on the recorder's fake file system, whose paths are logical '/'-separated names

import io.github.jamal_wia.kmptoolkit.audio.recorder.RecorderResult
import io.github.jamal_wia.kmptoolkit.testsupport.PathSyntax
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The real recorder joins a directory and a name into one clean path (no repeated separators); the
 * generated path of the fake is what a consumer test compares against.
 */
class FakeAudioRecorderDirectoryShapeTest {

    @Test
    fun `a directory that is absolute for the running file system is accepted and prepare takes such an explicit path`() = runTest {
        val directory: String = if (TestHost.pathSyntax == PathSyntax.WINDOWS) "C:\\fake\\dir" else "/fake/dir"
        val recorder = FakeAudioRecorder(directory = directory)

        val generated: String = (recorder.prepare() as RecorderResult.Success).value
        assertTrue(generated.startsWith(directory), "generated path was $generated")
        assertEquals(RecorderResult.Success("$directory/own.m4a"), recorder.prepare("$directory/own.m4a"))
    }

    @Test
    fun `a directory with a trailing separator still yields a path without repeated separators`() = runTest {
        val recorder = FakeAudioRecorder(directory = "/fake/dir/")

        val path: String = (recorder.prepare() as RecorderResult.Success).value

        assertFalse(path.contains("//"), "generated path was $path")
    }

    @Test
    fun `the root directory yields a path that starts with a single separator`() = runTest {
        val recorder = FakeAudioRecorder(directory = "/")

        val path: String = (recorder.prepare() as RecorderResult.Success).value

        assertFalse(path.startsWith("//"), "generated path was $path")
    }

    @Test
    fun `the default directory yields exactly the path it always did`() = runTest {
        val recorder = FakeAudioRecorder()

        val path: String = (recorder.prepare() as RecorderResult.Success).value

        assertEquals("/fake/recordings/recording_1.m4a", path)
    }

    @Test
    fun `repeated separators in the directory collapse to one`() = runTest {
        val recorder = FakeAudioRecorder(directory = "/fake//dir//")

        val path: String = (recorder.prepare() as RecorderResult.Success).value

        assertEquals("/fake/dir/recording_1.m4a", path)
    }
}
