package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javafx.application.Platform
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A listener is called on the FX thread, from inside JavaFX's own handlers, and may call straight
 * back into the engine (the SPI allows it: the engine's transport calls run inline there). Each
 * reaction below happens while JavaFX is in the middle of delivering an event to the engine.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxReentrantListenerR4AdversarialTest {

    private val errors: MutableList<String> = CopyOnWriteArrayList()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            errors += "${t.name}: $e @ ${e.stackTrace.take(5).joinToString(" < ")}"
        }
    }

    @AfterTest
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    private fun drainFxThread() {
        val done = CountDownLatch(1)
        Platform.runLater { done.countDown() }
        assertTrue(done.await(10L, TimeUnit.SECONDS), "the FX thread did not drain")
    }

    private open class Quiet : VideoPlaybackEngineListener {
        override fun onCompleted() = Unit
        override fun onFailed(cause: Throwable) = Unit
        override fun onBufferingChanged(isBuffering: Boolean) = Unit
        override fun onVideoSizeChanged(size: VideoSize?) = Unit
    }

    private fun loadClip(engine: JavaFxVideoEngine) = runBlocking {
        withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) }
    }

    @Test
    fun `releasing the engine from inside the completion callback leaves nothing behind and throws nothing`() {
        val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
        val completed = CountDownLatch(1)
        engine.setListener(object : Quiet() {
            override fun onCompleted() {
                engine.release()
                completed.countDown()
            }
        })
        loadClip(engine)
        engine.seekTo(2_700L)
        engine.start()
        assertTrue(completed.await(15L, TimeUnit.SECONDS), "no Completed")
        drainFxThread()
        Thread.sleep(300L)
        drainFxThread()
        assertEquals(0L, engine.durationMs(), "the session should be gone")
        assertEquals(emptyList(), errors.toList(), "uncaught exceptions on the FX thread")
    }

    @Test
    fun `restarting from inside the completion callback completes again every cycle`() {
        val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
        val completions = AtomicInteger(0)
        val third = CountDownLatch(1)
        engine.setListener(object : Quiet() {
            override fun onCompleted() {
                if (completions.incrementAndGet() >= 3) {
                    third.countDown()
                    return
                }
                engine.seekTo(2_600L)
                engine.start()
            }
        })
        loadClip(engine)
        engine.seekTo(2_600L)
        engine.start()
        val finished: Boolean = third.await(30L, TimeUnit.SECONDS)
        val seen: Int = completions.get()
        engine.release()
        drainFxThread()
        assertTrue(finished, "only $seen completions: a restart from inside the callback did not play to the end again")
        assertEquals(3, seen, "extra Completed events")
        assertEquals(emptyList(), errors.toList(), "uncaught exceptions on the FX thread")
    }
}
