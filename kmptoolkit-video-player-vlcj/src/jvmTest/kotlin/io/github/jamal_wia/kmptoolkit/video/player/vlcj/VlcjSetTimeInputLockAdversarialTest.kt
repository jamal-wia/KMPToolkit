package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * libvlc 3.0.21 (probe lock4.c/lock5.c): while a MediaPlayer event callback is running on libvlc's
 * input thread and is blocked (on a lock the caller of `libvlc_media_player_set_time` holds),
 * `set_time` cannot return; it returns once the callback does. VLCJ 4.12.1 runs its listeners on that
 * thread (NativeEventManager.EventCallback.callback -> raiseEvent). The engine's KDoc says set_time only
 * posts work and returns at once, and it calls `setTime` while holding [lock] -- the very lock its
 * `timeChanged` / `playing` / `finished` callbacks take. A callback that reaches the lock while a
 * seek holds it and has not yet reached libvlc is a lock-order inversion: neither side can go on.
 *
 * The model below is a libvlc whose `setTime` waits for the input thread to leave a running callback.
 * Every wait is bounded; on the inversion `setTime` gives up after 2 s and reports it.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjSetTimeInputLockAdversarialTest {

    private val inputThread: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "model-input-thread").apply { isDaemon = true } }
    private val taskThread: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "model-task-thread").apply { isDaemon = true } }
    private val inputLock = ReentrantLock()
    private val inSetTime = CountDownLatch(1)
    private val callbackIsBlocked = CountDownLatch(1)
    private val deadlocked = AtomicBoolean(false)
    private var callbacks: VlcPlayerCallbacks? = null

    /** Every native call the model receives, in order, with `-start` / `-end` around the seek. */
    private val events: MutableList<String> = CopyOnWriteArrayList()

    /** When set, [ModelPlayer.time] waits for it, and then answers [timeAnswer]. */
    @Volatile private var timeGate: CountDownLatch? = null
    @Volatile private var timeAnswer: Long = -1L

    /** The size parsing reports; null leaves the load without a size report. */
    @Volatile private var parsedSize: VideoSize? = null

    /** Runs inside [ModelPlayer.play], on the calling thread, like a libvlc event raised synchronously. */
    @Volatile private var onPlay: () -> Unit = {}

    @AfterTest
    fun tearDown() {
        inputThread.shutdownNow()
        taskThread.shutdownNow()
    }

    private inner class ModelPlayer : VlcNativePlayer {
        override fun prepare(mrl: String, options: List<String>): Boolean = true
        override fun parse(): Boolean {
            inputThread.execute { fire { parsed(VlcParseStatus.DONE) } }
            return true
        }
        override fun parsedInfo(): VlcParsedInfo = VlcParsedInfo(durationMs = 10_000L, trackCount = 1, videoSize = parsedSize)
        override fun play() {
            events += "play"
            onPlay()
        }
        override fun pause() {
            events += "pause"
        }
        override fun setTime(timeMs: Long) {
            events += "setTime-start($timeMs)"
            inSetTime.countDown()
            callbackIsBlocked.await(3L, TimeUnit.SECONDS)
            // libvlc: the call needs the input thread, which is inside a callback.
            if (!inputLock.tryLock(2L, TimeUnit.SECONDS)) {
                deadlocked.set(true)
            } else {
                inputLock.unlock()
            }
            events += "setTime-end($timeMs)"
        }
        override fun time(): Long {
            events += "time"
            timeGate?.await(5L, TimeUnit.SECONDS)
            return timeAnswer
        }
        override fun setRate(rate: Float) {
            events += "setRate"
        }
        override fun setVolume(percent: Int) {
            events += "setVolume"
        }
        override fun rate(): Float = 1f
        override fun volume(): Int = 100
        override fun setRepeat(repeat: Boolean) = Unit
        override fun repeat(): Boolean = false
        override fun submit(task: () -> Unit) {
            taskThread.execute(task)
        }
        override fun stopParsing() {
            events += "stopParsing"
        }
        override fun stop() {
            events += "stop"
        }
        override fun release() {
            events += "release"
        }
    }

    /** Delivers an event the way libvlc does: on the input thread, which is inside the callback until it returns. */
    private fun fire(event: VlcPlayerCallbacks.() -> Unit) {
        inputLock.lock()
        try {
            callbacks!!.event()
        } finally {
            inputLock.unlock()
        }
    }

    private val runtime = object : VlcRuntime {
        override fun newPlayer(callbacks: VlcPlayerCallbacks): VlcNativePlayer {
            this@VlcjSetTimeInputLockAdversarialTest.callbacks = callbacks
            return ModelPlayer()
        }
        override fun release() = Unit
    }

    @Test
    fun `a seek never waits for a libvlc callback that is waiting for the engine lock the seek holds`() {
        val engine = VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { runtime })
        runBlocking { withTimeout(5_000L) { engine.load(VideoSource.File(TestClip.file.path)) } }
        engine.start()
        inputThread.submit { fire { playing() } }.get(5L, TimeUnit.SECONDS)
        taskThread.submit {}.get(5L, TimeUnit.SECONDS)

        val seeker = thread(name = "seeker") { engine.seekTo(500L) }
        assertTrue(inSetTime.await(5L, TimeUnit.SECONDS), "the seek never reached libvlc")
        // The seek holds the engine lock and is about to reach libvlc. libvlc's regular time report
        // arrives now and runs the engine's callback, which takes that lock.
        inputThread.execute { fire { timeChanged(300L) } }
        Thread.sleep(300L)
        callbackIsBlocked.countDown()
        seeker.join(8_000L)
        assertFalse(seeker.isAlive, "the seeking thread is stuck")
        assertFalse(deadlocked.get(), "set_time waited for a callback that waited for the lock the seek held")
        engine.dispose()
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10L)
        assertTrue(condition(), "timed out waiting for $what: $events")
    }

    private fun loadAndStartPlaying(engine: VlcjVideoEngine) {
        runBlocking { withTimeout(5_000L) { engine.load(VideoSource.File(TestClip.file.path)) } }
        engine.start()
        inputThread.submit { fire { playing() } }.get(5L, TimeUnit.SECONDS)
        taskThread.submit {}.get(5L, TimeUnit.SECONDS)
    }

    private fun listenerOf(onBuffering: () -> Unit): VideoPlaybackEngineListener = object : VideoPlaybackEngineListener {
        override fun onCompleted() = Unit
        override fun onFailed(cause: Throwable) = Unit
        override fun onBufferingChanged(isBuffering: Boolean) = onBuffering()
        override fun onVideoSizeChanged(size: VideoSize?) = Unit
    }

    @Test
    fun `a pending seek applied at Playing never waits for a callback that waits for the engine lock`() {
        val engine = VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { runtime })
        runBlocking { withTimeout(5_000L) { engine.load(VideoSource.File(TestClip.file.path)) } }
        engine.seekTo(500L) // parked: there is no output yet
        engine.start()
        // Playing queues the pending seek onto the task thread, where set_time waits for the input thread.
        inputThread.submit { fire { playing() } }.get(5L, TimeUnit.SECONDS)
        assertTrue(inSetTime.await(5L, TimeUnit.SECONDS), "the pending seek never reached libvlc")
        // Another event arrives while set_time waits; it takes the engine lock.
        inputThread.execute { fire { playing() } }
        Thread.sleep(300L)
        callbackIsBlocked.countDown()
        taskThread.submit {}.get(8L, TimeUnit.SECONDS)
        assertFalse(deadlocked.get(), "set_time waited for a callback that waited for the engine lock")
        assertTrue("setTime-end(500)" in events, "the pending seek was not applied: $events")
        engine.dispose()
    }

    @Test
    fun `release from inside a callback while another thread's seek waits in libvlc for that callback neither deadlocks nor frees the player under the seek`() {
        val engine = VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { runtime })
        val callbackRunning = CountDownLatch(1)
        val callbackDone = CountDownLatch(1)
        engine.setListener(listenerOf {
            // On the input thread, inside the engine's callback: the seek below is now waiting for it.
            callbackRunning.countDown()
            inSetTime.await(5L, TimeUnit.SECONDS)
            engine.release()
            callbackDone.countDown()
        })
        loadAndStartPlaying(engine)
        callbackIsBlocked.countDown()
        inputThread.execute { fire { buffering(10f) } }
        assertTrue(callbackRunning.await(5L, TimeUnit.SECONDS), "the callback never ran")

        val seeker = thread(name = "seeker") { engine.seekTo(500L) }
        assertTrue(callbackDone.await(5L, TimeUnit.SECONDS), "release inside the callback never returned")
        seeker.join(5_000L)
        assertFalse(seeker.isAlive, "the seeking thread is stuck")
        assertFalse(deadlocked.get(), "set_time waited for a callback that waited for the engine")
        waitFor("the native player to be released") { "release" in events }
        val order: List<String> = events.toList()
        assertTrue(order.indexOf("setTime-end(500)") in 0 until order.indexOf("stop"), "stop came before the seek returned: $order")
        assertTrue(order.indexOf("stop") < order.indexOf("release"), "release before stop: $order")
        engine.dispose()
    }

    @Test
    fun `a seek in flight on one thread and release on another - the player is freed only after the seek returned and nothing starts after release returned`() {
        val engine = VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { runtime })
        loadAndStartPlaying(engine)
        val seeker = thread(name = "seeker") { engine.seekTo(500L) }
        assertTrue(inSetTime.await(5L, TimeUnit.SECONDS), "the seek never reached libvlc")
        val returned = CountDownLatch(1)
        val releaser = thread(name = "releaser") {
            engine.release()
            events += "release-returned"
            returned.countDown()
        }
        Thread.sleep(200L)
        callbackIsBlocked.countDown() // the seek goes on and returns
        seeker.join(5_000L)
        releaser.join(5_000L)
        assertFalse(seeker.isAlive || releaser.isAlive, "a thread is stuck: $events")
        assertTrue(returned.await(5L, TimeUnit.SECONDS), "release never returned")
        // Everything after release returned: further calls reach nothing.
        engine.start()
        engine.pause()
        engine.seekTo(900L)
        engine.setSpeed(2f)
        engine.setVolume(0.5f)
        engine.positionMs()
        waitFor("the native player to be released") { "release" in events }
        val order: List<String> = events.toList()
        assertTrue(order.indexOf("setTime-end(500)") in 0 until order.indexOf("stop"), "the player was stopped under the seek: $order")
        assertTrue(order.indexOf("stop") < order.indexOf("release"), "release before stop: $order")
        val afterRelease: List<String> = order.drop(order.indexOf("release-returned") + 1)
        assertTrue(
            afterRelease.all { it == "setTime-end(500)" || it == "stopParsing" || it == "stop" || it == "release" },
            "a native call started after release returned: $afterRelease",
        )
        engine.dispose()
    }

    @Test
    fun `a clock reading taken while a seek arrives from a VLC thread does not replace its target`() {
        val engine = VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { runtime })
        val seekMade = CountDownLatch(1)
        engine.setListener(listenerOf {
            // A listener reacting synchronously, on the input thread.
            engine.seekTo(500L)
            seekMade.countDown()
        })
        loadAndStartPlaying(engine)
        callbackIsBlocked.countDown()
        val gate = CountDownLatch(1)
        timeGate = gate
        timeAnswer = 100L // the clock before the seek
        var reading = -1L
        val reader = thread(name = "reader") { reading = engine.positionMs() }
        waitFor("the clock read to start") { "time" in events }
        inputThread.execute { fire { buffering(10f) } }
        seekMade.await(1L, TimeUnit.SECONDS)
        gate.countDown()
        reader.join(5_000L)
        assertFalse(reader.isAlive, "the reader is stuck")
        taskThread.submit {}.get(5L, TimeUnit.SECONDS)
        assertEquals(500L, reading, "the clock reading replaced the seek's target")
        assertEquals(500L, engine.positionMs())
        engine.dispose()
    }

    @Test
    fun `a listener called from the load's size report never waits for a native call whose callback waits for the listener lock`() {
        parsedSize = VideoSize(64, 48)
        val engine = VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { runtime })
        val errors: MutableList<String> = CopyOnWriteArrayList()
        val previous: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> errors += "${t.name}: $e" }
        try {
            val inPlay = CountDownLatch(1)
            // libvlc raises some events on the calling thread: the callback runs inside play() on the
            // transport thread and waits for the listener lock, which the load's thread holds.
            onPlay = {
                inPlay.countDown()
                callbacks!!.buffering(50f)
            }
            lateinit var transport: Thread
            engine.setListener(object : VideoPlaybackEngineListener {
                override fun onCompleted() = Unit
                override fun onFailed(cause: Throwable) = Unit
                override fun onBufferingChanged(isBuffering: Boolean) = Unit
                override fun onVideoSizeChanged(size: VideoSize?) {
                    transport = thread(name = "transport", isDaemon = true) { engine.start() }
                    inPlay.await(5L, TimeUnit.SECONDS)
                    engine.pause()
                    engine.positionMs()
                }
            })
            val loader = thread(name = "loader", isDaemon = true) {
                runBlocking { engine.load(VideoSource.File(TestClip.file.path)) }
            }
            loader.join(5_000L)
            assertFalse(loader.isAlive, "the load is stuck in the listener call: $events")
            transport.join(5_000L)
            assertFalse(transport.isAlive, "the transport thread is stuck on the listener lock: $events")
            assertEquals(emptyList(), errors.toList())
            engine.dispose()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
