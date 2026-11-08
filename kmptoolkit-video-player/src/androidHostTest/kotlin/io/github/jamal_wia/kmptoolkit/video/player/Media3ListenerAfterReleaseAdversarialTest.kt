package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Media3 engine over a looper that really runs on its own thread. Contract: "Never call the
 * listener after release, including callbacks already queued on a platform thread" - an end that was
 * queued behind a busy looper before release() returned must not reach the listener afterwards.
 *
 * Robolectric's clock is virtual and frozen between looper idles, so a background looper's delayed
 * messages would never come due: the rig advances it with real time.
 */
@RunWith(AndroidJUnit4::class)
class Media3ListenerAfterReleaseAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source: VideoSource = VideoSource.Asset("clips/intro.mp4")

    private class Rig {
        val thread: HandlerThread = HandlerThread("exo-storm").also { it.start() }
        val handler: Handler = Handler(thread.looper)
        val exo: AtomicReference<ExoPlayer?> = AtomicReference(null)

        @Volatile
        private var ticking: Boolean = true
        private val ticker: Thread = thread(isDaemon = true, name = "clock-ticker") {
            while (ticking) {
                ShadowSystemClock.advanceBy(Duration.ofMillis(1))
                try {
                    Thread.sleep(1)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }

        /** Runs [block] on the looper thread and returns its result, bounded. */
        fun <T> onLooper(block: () -> T): T {
            val latch = CountDownLatch(1)
            val out: AtomicReference<Result<T>?> = AtomicReference(null)
            handler.post {
                out.set(runCatching(block))
                latch.countDown()
            }
            if (!latch.await(10, TimeUnit.SECONDS)) fail("the looper did not drain in 10 s")
            return requireNotNull(out.get()).getOrThrow()
        }

        fun idle() {
            onLooper { }
            onLooper { }
        }

        fun close() {
            ticking = false
            ticker.interrupt()
            thread.quitSafely()
        }
    }


    private fun newEngine(rig: Rig): Media3VideoEngine = Media3VideoEngine(
        context = context,
        looper = rig.thread.looper,
        playerFactory = { playerContext: Context, looper: Looper ->
            TestExoPlayerBuilder(playerContext).setClock(Clock.DEFAULT).setLooper(looper).build().also { rig.exo.set(it) }
        },
        mediaSourceFactory = { _, _ -> Media3TestSupport.fakeSource(durationMs = 60_000L) },
    )

    private fun loadBlocking(engine: Media3VideoEngine) {
        runBlocking(Dispatchers.Default) { withTimeout(10_000L) { engine.load(source) } }
    }


    private class CountingListener : VideoPlaybackEngineListener {
        val completed: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(0)
        val events: java.util.concurrent.CopyOnWriteArrayList<String> = java.util.concurrent.CopyOnWriteArrayList()
        override fun onCompleted() { completed.incrementAndGet(); events += "completed" }
        override fun onFailed(cause: Throwable) { events += "failed" }
        override fun onBufferingChanged(isBuffering: Boolean) { events += "buffering=$isBuffering" }
        override fun onVideoSizeChanged(size: VideoSize?) { events += "size" }
    }

    private fun playToTheEndBehindABlockedLooper(release: Boolean): CountingListener {
        val rig = Rig()
        try {
            val engine: Media3VideoEngine = newEngine(rig)
            val listener = CountingListener()
            engine.setListener(listener)
            loadBlocking(engine)
            engine.seekTo(59_900L)
            rig.idle()
            engine.start()
            rig.idle()
            val gate = CountDownLatch(1)
            rig.handler.post { gate.await(20, TimeUnit.SECONDS) }
            // The looper is blocked: the player's own thread reaches the end and queues its news behind the gate.
            Thread.sleep(700L)
            listener.events.clear()
            if (release) engine.release()
            val releasedAt = listener.events.size
            gate.countDown()
            rig.idle()
            Thread.sleep(50L)
            rig.idle()
            if (release) {
                engine.dispose()
                rig.idle()
            }
            return listener.also { it.events.add(0, "mark:$releasedAt") }
        } finally {
            rig.close()
        }
    }

    @Test
    fun `control - a source that reaches its end behind a busy looper is reported once the looper is free`() {
        val listener: CountingListener = playToTheEndBehindABlockedLooper(release = false)
        assertEquals(1, listener.completed.get(), "the setup did not reach the end of the source: ${listener.events}")
    }


    @Test
    fun `an end that was queued behind a busy looper is not reported after release returned`() {
        val listener: CountingListener = playToTheEndBehindABlockedLooper(release = true)
        assertEquals(0, listener.completed.get(), "the engine reported completion after release() had returned: ${listener.events}")
        assertTrue(
            listener.events.drop(1).none { it == "completed" || it.startsWith("buffering") || it == "failed" },
            "the engine called its listener after release() had returned: ${listener.events}",
        )
    }
}
