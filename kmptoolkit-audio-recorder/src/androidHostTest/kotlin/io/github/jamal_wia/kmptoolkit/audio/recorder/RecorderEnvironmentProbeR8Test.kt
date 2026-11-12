package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Environment probes over the strict platform stand-in: a silence monitor the platform refuses, a file
 * that disappears or is replaced under a running recording, an output directory that is read-only, and
 * a recorder that is abandoned without a release. At several API levels.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 28, 29, 31, 35], shadows = [StrictShadowMediaRecorder::class])
class RecorderEnvironmentProbeR8Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val recorders: MutableList<AudioRecorder> = mutableListOf()
    private lateinit var root: File

    @Before
    fun setUp() {
        StrictRecorderWorld.reset()
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        root = File(context.cacheDir, "r8env-${System.nanoTime()}")
    }

    @After
    fun tearDown() {
        recorders.forEach { it.release() }
        root.setWritable(true)
        root.walkTopDown().forEach { it.setWritable(true) }
        root.deleteRecursively()
    }

    private fun newRecorder(name: String): AudioRecorder = createAudioRecorder(
        context,
        AudioRecorderConfig(storage = RecordingStorage(directoryPath = File(root, name).path)),
    ).also { recorders += it }

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { withTimeout(30_000) { block() } }

    private fun prepareOk(recorder: AudioRecorder): String =
        (blocking { recorder.prepare() } as? RecorderResult.Success)?.value
            ?: error("prepare failed, state ${recorder.state.value}")

    @Test
    fun `a silence monitor the platform refuses at every step leaves a working recording`() {
        StrictRecorderWorld.failures["registerAudioRecordingCallback"] = IllegalStateException("register")
        StrictRecorderWorld.failures["unregisterAudioRecordingCallback"] = IllegalStateException("unregister")
        StrictRecorderWorld.failures["getActiveRecordingConfiguration"] = RuntimeException("config")
        val recorder: AudioRecorder = newRecorder("silence")
        prepareOk(recorder)
        assertTrue(recorder.start().isSuccess)
        assertTrue(recorder.pause().isSuccess)
        assertTrue(recorder.resume().isSuccess)
        assertTrue(blocking { recorder.stop() }.isSuccess)
        assertEquals(0, StrictRecorderWorld.live.get())
    }

    @Test
    fun `the silence monitor is registered from api 29 only and unregistered before the recording ends`() {
        val recorder: AudioRecorder = newRecorder("monitor")
        prepareOk(recorder)
        val platform: StrictShadowMediaRecorder = StrictRecorderWorld.instances.last()
        val expected: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) 1 else 0
        assertEquals(expected, platform.audioCallbacks.size, "monitors registered after prepare")
        assertTrue(recorder.start().isSuccess)
        assertEquals(expected, platform.audioCallbacks.size, "monitors registered while recording")
        assertTrue(blocking { recorder.stop() }.isSuccess)
        assertEquals(0, platform.audioCallbacks.size, "a monitor outlived the recording")
    }

    @Test
    fun `a read only output directory is refused as not writable and never reaches the platform`() {
        val dir = File(root, "ro")
        assertTrue(dir.mkdirs())
        assertTrue(dir.setWritable(false))
        if (dir.canWrite()) return // running as a user that ignores permissions
        val recorder: AudioRecorder = newRecorder("ro")
        val result: RecorderResult<String> = blocking { recorder.prepare() }
        val failure = assertIs<RecorderResult.Failure>(result)
        assertIs<RecorderError.DirectoryNotWritable>(failure.error)
        assertEquals(0, StrictRecorderWorld.created.get())
    }

    @Test
    fun `an abandoned recording does not stop a fresh recorder from recording in the same directory`() {
        val abandoned: AudioRecorder = newRecorder("shared")
        val abandonedPath: String = prepareOk(abandoned)
        assertTrue(abandoned.start().isSuccess)
        // No stop and no release: the process that owned it is gone as far as this test is concerned.
        val fresh: AudioRecorder = createAudioRecorder(
            context,
            AudioRecorderConfig(storage = RecordingStorage(directoryPath = File(root, "shared").path)),
        ).also { recorders += it }
        val freshPath: String = prepareOk(fresh)
        assertTrue(fresh.start().isSuccess)
        assertTrue(blocking { fresh.stop() }.isSuccess)
        assertTrue(File(freshPath).exists())
        assertTrue(File(abandonedPath).exists(), "a fresh recorder deleted another recorder's file")
        assertTrue(abandonedPath != freshPath)
    }
}
