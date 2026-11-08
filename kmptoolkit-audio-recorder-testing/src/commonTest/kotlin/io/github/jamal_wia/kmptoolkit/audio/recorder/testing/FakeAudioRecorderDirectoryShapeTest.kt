package io.github.jamal_wia.kmptoolkit.audio.recorder.testing

import io.github.jamal_wia.kmptoolkit.audio.recorder.RecorderResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

/**
 * The real recorder joins a directory and a name into one clean path (no repeated separators); the
 * generated path of the fake is what a consumer test compares against.
 */
class FakeAudioRecorderDirectoryShapeTest {

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
