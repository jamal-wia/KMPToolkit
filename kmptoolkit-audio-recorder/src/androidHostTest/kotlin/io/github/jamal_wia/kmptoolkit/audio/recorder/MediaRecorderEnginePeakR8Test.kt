package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The engine's own answer to a peak read that fails, whatever the platform throws, at several levels. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 29, 35], shadows = [StrictShadowMediaRecorder::class])
class MediaRecorderEnginePeakR8Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val engine = MediaRecorderEngine(context)
    private lateinit var file: File

    @Before
    fun setUp() {
        StrictRecorderWorld.reset()
        file = File(context.cacheDir, "peak-${System.nanoTime()}.m4a")
    }

    @After
    fun tearDown() {
        engine.release()
        file.delete()
    }

    private fun recording() {
        runBlocking { withTimeout(10_000) { engine.prepare(file.path, AudioRecorderConfig(), null) } }
        engine.start()
    }

    @Test
    fun `a peak read that throws any runtime exception is no reading rather than a failure`() {
        recording()
        for (thrown in listOf<RuntimeException>(IllegalStateException("a"), RuntimeException("b"), UnsupportedOperationException("c"))) {
            StrictRecorderWorld.failures["getMaxAmplitude"] = thrown
            assertNull(engine.peakDbfs(), "$thrown")
        }
        StrictRecorderWorld.failures.clear()
        StrictRecorderWorld.amplitude = 32_767
        assertEquals(0f, engine.peakDbfs())
    }

    @Test
    fun `no peak outside the recording states and a released engine answers nothing`() {
        runBlocking { withTimeout(10_000) { engine.prepare(file.path, AudioRecorderConfig(), null) } }
        assertNull(engine.peakDbfs(), "prepared but not started: the platform throws, the engine answers null")
        engine.start()
        engine.stop()
        assertNull(engine.peakDbfs())
        engine.release()
        assertNull(engine.peakDbfs())
    }
}
