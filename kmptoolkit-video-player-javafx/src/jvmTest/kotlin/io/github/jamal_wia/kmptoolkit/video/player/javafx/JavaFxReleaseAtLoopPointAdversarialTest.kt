package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javafx.application.Platform
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Releasing a looping source around its loop point: JavaFX queues the end-of-cycle event with
 * `Platform.runLater` and runs it even when the player was disposed meanwhile, which throws inside
 * JavaFX on the application thread (an uncaught exception in the host's handler). The engine
 * promises a disposed playback is silent and leaves nothing behind.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxReleaseAtLoopPointAdversarialTest {

    private val errors: MutableList<String> = CopyOnWriteArrayList()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            errors += "${t.name}: $e @ ${e.stackTrace.take(4).joinToString(" < ")}"
        }
    }

    @AfterTest
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    private fun drainFxThread() {
        val done = CountDownLatch(1)
        Platform.runLater { done.countDown() }
        done.await(10L, TimeUnit.SECONDS)
    }

    @Test
    fun `releasing a looping source at random moments around its loop point throws nothing on the FX thread`() {
        val random = Random(5)
        repeat(60) {
            val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
            runBlocking { withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) } }
            engine.setLooping(true)
            engine.seekTo(2_000L)
            engine.start()
            Thread.sleep(random.nextLong(700L, 1_500L))
            engine.release()
            drainFxThread()
        }
        Thread.sleep(500L)
        drainFxThread()
        assertEquals(emptyList(), errors.toList().distinct(), "uncaught exceptions on the FX thread")
    }

    @Test
    fun `releasing a looping one second audio source within a few milliseconds of its loop point throws nothing on the FX thread`() {
        val random = Random(9)
        repeat(80) {
            val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
            try {
                runBlocking { withTimeout(20_000L) { engine.load(VideoSource.Asset("silence-8khz-1s.wav")) } }
            } catch (e: Throwable) {
                val fx = Thread.getAllStackTraces().entries.firstOrNull { it.key.name == "JavaFX Application Thread" }
                throw AssertionError(
                    "iteration $it: load failed with $e; FX thread ${fx?.key?.state} ${fx?.key?.isAlive}:\n   at " +
                        (fx?.value?.take(25)?.joinToString("\n   at ") ?: "none") + "\nerrors so far: ${errors.toList().distinct()}",
                    e,
                )
            }
            engine.setLooping(true)
            engine.start()
            Thread.sleep(random.nextLong(940L, 1_060L))
            engine.release()
            if (it % 8 == 0) drainFxThread()
        }
        Thread.sleep(500L)
        drainFxThread()
        assertEquals(emptyList(), errors.toList().distinct(), "uncaught exceptions on the FX thread")
    }
}
