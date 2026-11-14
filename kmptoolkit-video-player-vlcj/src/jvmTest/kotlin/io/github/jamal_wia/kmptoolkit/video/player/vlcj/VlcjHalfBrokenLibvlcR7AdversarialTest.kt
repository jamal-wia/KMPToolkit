package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 7 (environment: libvlc half-initialised, a native call that fails): a libvlc model that fails one named
 * native operation on demand. An exception from a native call is what a half-broken installation or a missing
 * audio output could produce on the task thread; the engine must keep its books straight anyway: every native
 * player it ever got is stopped and released once, libvlc is freed once, a failed load leaves the engine
 * loadable, and the position keeps following the clock.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjHalfBrokenLibvlcR7AdversarialTest {

    private val executors: MutableList<ExecutorService> = CopyOnWriteArrayList()

    @AfterTest
    fun tearDown() {
        executors.forEach { it.shutdownNow() }
    }

    private inner class Model : VlcRuntime {
        val input: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "half-input").apply { isDaemon = true } }.also { executors += it }
        val tasks: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "half-tasks").apply { isDaemon = true } }.also { executors += it }
        val players: MutableList<HalfPlayer> = CopyOnWriteArrayList()
        val runtimeReleases: AtomicInteger = AtomicInteger()
        @Volatile var failNewPlayer: Int = 0
        @Volatile var fail: String = ""
        @Volatile var failTimes: Int = 0
        @Volatile var trackCount: Int = 1
        @Volatile var timeAnswers: () -> Long = { 0L }

        override fun newPlayer(callbacks: VlcPlayerCallbacks): VlcNativePlayer {
            if (failNewPlayer > 0) {
                failNewPlayer--
                throw IllegalStateException("libvlc could not create a media player")
            }
            return HalfPlayer(this, callbacks).also { players += it }
        }

        override fun release() {
            runtimeReleases.incrementAndGet()
        }

        fun shouldFail(op: String): Boolean {
            if (fail != op || failTimes <= 0) return false
            failTimes--
            return true
        }
    }

    private inner class HalfPlayer(private val m: Model, val callbacks: VlcPlayerCallbacks) : VlcNativePlayer {
        val stops: AtomicInteger = AtomicInteger()
        val releases: AtomicInteger = AtomicInteger()
        val calls: MutableList<String> = CopyOnWriteArrayList()
        @Volatile private var repeat: Boolean = false

        private fun op(name: String) {
            calls += name
            if (m.shouldFail(name)) throw IllegalStateException("native $name failed")
        }

        override fun prepare(mrl: String, options: List<String>): Boolean {
            op("prepare")
            return m.fail != "prepare-false"
        }

        override fun parse(): Boolean {
            op("parse")
            if (m.fail == "parse-false") return false
            m.input.execute { callbacks.parsed(VlcParseStatus.DONE) }
            return true
        }

        override fun parsedInfo(): VlcParsedInfo {
            op("parsedInfo")
            return VlcParsedInfo(durationMs = 4_000L, trackCount = m.trackCount, videoSize = VideoSize(64, 48))
        }

        override fun play() = op("play")
        override fun pause() = op("pause")
        override fun setTime(timeMs: Long) = op("setTime($timeMs)")
        override fun time(): Long {
            op("time")
            return m.timeAnswers()
        }

        override fun setRate(rate: Float) = op("setRate")
        override fun setVolume(percent: Int) = op("setVolume")
        override fun rate(): Float = 1f
        override fun volume(): Int = 100
        override fun setRepeat(repeat: Boolean) {
            this.repeat = repeat
        }

        override fun repeat(): Boolean = repeat
        override fun submit(task: () -> Unit) {
            m.tasks.execute(task)
        }

        override fun stopParsing() = Unit

        override fun stop() {
            stops.incrementAndGet()
            if (m.fail == "stop") throw IllegalStateException("native stop failed")
        }

        override fun release() {
            releases.incrementAndGet()
            if (m.fail == "release") throw IllegalStateException("native release failed")
        }

        fun fire(block: VlcPlayerCallbacks.() -> Unit) {
            m.input.submit { callbacks.block() }.get(5L, TimeUnit.SECONDS)
        }
    }

    private fun newEngine(m: Model): VlcjVideoEngine =
        VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { m })

    private fun load(engine: VlcjVideoEngine) = runBlocking { withTimeout(5_000L) { engine.load(VideoSource.File(TestClip.file.path)) } }

    private fun assertBooksBalanced(m: Model, engine: VlcjVideoEngine, label: String) {
        engine.release()
        engine.dispose()
        val until: Long = System.nanoTime() + 5_000_000_000L
        while ((m.runtimeReleases.get() < 1 || m.players.any { it.releases.get() < 1 }) && System.nanoTime() < until) Thread.sleep(10L)
        for (p: HalfPlayer in m.players) {
            assertEquals(1, p.stops.get(), "$label: a native player stopped ${p.stops.get()} times")
            assertEquals(1, p.releases.get(), "$label: a native player released ${p.releases.get()} times")
        }
        assertEquals(1, m.runtimeReleases.get(), "$label: libvlc freed ${m.runtimeReleases.get()} times")
    }

    @Test
    fun `libvlc that cannot create a media player once fails that load, loads the next and frees nothing twice`() {
        val m = Model().apply { failNewPlayer = 1 }
        val engine = newEngine(m)
        assertFailsWith<IllegalStateException> { load(engine) }
        assertEquals(0, m.players.size)
        load(engine)
        assertEquals(1, m.players.size)
        assertBooksBalanced(m, engine, "newPlayer")
    }

    @Test
    fun `a failure of prepare, parse, parsedInfo or a trackless media fails the load, frees the player once and leaves the engine loadable`() {
        for (mode: String in listOf("prepare", "prepare-false", "parse", "parse-false", "parsedInfo", "no-tracks")) {
            val m = Model()
            when (mode) {
                "no-tracks" -> m.trackCount = 0
                "prepare-false", "parse-false" -> m.fail = mode
                else -> { m.fail = mode; m.failTimes = 1 }
            }
            val engine = newEngine(m)
            assertFailsWith<Throwable>("$mode: the load should fail") { load(engine) }
            assertEquals(1, m.players.size, "$mode: players created")
            m.fail = ""
            m.trackCount = 1
            load(engine)
            assertEquals(2, m.players.size, "$mode: the next load makes a new native player")
            assertBooksBalanced(m, engine, mode)
        }
    }

    @Test
    fun `a native stop or release that throws still frees libvlc once and the media`() {
        for (mode: String in listOf("stop", "release")) {
            val m = Model()
            val engine = newEngine(m)
            load(engine)
            m.fail = mode
            engine.release()
            engine.dispose()
            val until: Long = System.nanoTime() + 5_000_000_000L
            while (m.runtimeReleases.get() < 1 && System.nanoTime() < until) Thread.sleep(10L)
            assertEquals(1, m.runtimeReleases.get(), "$mode throwing: libvlc freed ${m.runtimeReleases.get()} times")
            assertEquals(1, m.players.single().releases.get(), "$mode throwing: the native player was still released")
        }
    }

    @Test
    fun `a play that throws leaves the engine usable and the next start plays`() {
        val m = Model()
        val engine = newEngine(m)
        load(engine)
        m.fail = "play"
        m.failTimes = 1
        runCatching { engine.start() }
        engine.pause()
        engine.seekTo(500L)
        engine.setSpeed(2f)
        engine.positionMs()
        engine.start()
        assertEquals(2, m.players.single().calls.count { it == "play" }, "the second start reached libvlc")
        assertBooksBalanced(m, engine, "play")
    }

    @Test
    fun `a seek that throws in setTime does not pin the position to its target`() {
        val m = Model()
        val engine = newEngine(m)
        load(engine)
        val p: HalfPlayer = m.players.single()
        engine.start()
        p.fire { playing() }
        m.fail = "setTime(3000)"
        m.failTimes = 1
        runCatching { engine.seekTo(3_000L) }
        m.timeAnswers = { 1_234L }
        awaitPosition(engine, 1_234L, "a seek whose setTime threw pinned the position")
        assertBooksBalanced(m, engine, "setTime")
    }

    @Test
    fun `settings that throw when output begins do not freeze the playhead on a seek made before output`() {
        val m = Model()
        val engine = newEngine(m)
        load(engine)
        val p: HalfPlayer = m.players.single()
        engine.seekTo(1_500L)
        engine.start()
        m.fail = "setVolume"
        m.failTimes = 1
        p.fire { playing() }
        m.tasks.submit {}.get(5L, TimeUnit.SECONDS)
        m.timeAnswers = { 2_222L }
        p.fire { timeChanged(2_222L) }
        awaitPosition(engine, 2_222L, "the playhead stayed on the pending seek after the settings of Playing threw")
        assertBooksBalanced(m, engine, "settings")
    }

    @Test
    fun `a clock read that throws does not stop the shared player from following the playhead`() {
        val m = Model()
        val engine = newEngine(m)
        val player: VideoPlayer = createVlcjVideoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = 10L), Dispatchers.Default)
        try {
            runBlocking { withTimeout(5_000L) { player.prepare(VideoSource.File(TestClip.file.path)) } }
            val p: HalfPlayer = m.players.single()
            player.play()
            p.fire { playing() }
            val reads = AtomicInteger()
            m.timeAnswers = {
                val n: Int = reads.incrementAndGet()
                if (n in 3..6) throw IllegalStateException("libvlc clock failed")
                100L + n * 10L
            }
            val until: Long = System.nanoTime() + 5_000_000_000L
            while (reads.get() < 40 && System.nanoTime() < until) Thread.sleep(10L)
            assertTrue(reads.get() >= 40, "the shared player stopped reading the clock after ${reads.get()} reads (a polling coroutine died)")
            assertTrue(player.stateFlow.value is VideoPlayerState.Playing, "state ${player.stateFlow.value}")
            assertTrue(player.playbackPositionFlow.value > 200L, "position ${player.playbackPositionFlow.value}")
        } finally {
            player.release()
        }
    }

    private fun awaitPosition(engine: VlcjVideoEngine, expected: Long, message: String) {
        val until: Long = System.nanoTime() + 3_000_000_000L
        while (engine.positionMs() != expected && System.nanoTime() < until) Thread.sleep(20L)
        assertEquals(expected, engine.positionMs(), message)
    }
}
