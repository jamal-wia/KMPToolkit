package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerReleasedException
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.io.FileNotFoundException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * D2 round 15, character THE CONSUMER'S SIDE, vlcj on its fake runtime (the real libvlc cannot run on this machine: x86_64
 * library, arm64 JVM): everything goes through the shared `VideoPlayer`.
 *
 * - Q1: the sentences of the vlcj guide and API reference that a consumer reads as promises: the volume mapping ("round(v x 100) %,
 *   never above 100"), the rate range ("1/32 to 32, the nearest bound outside it; playbackSpeedFlow still shows the speed that was
 *   asked for"), the failure table, "prepare returns once VLC's parser has opened the source", "a volume set while nothing plays
 *   is not lost".
 * - Q2: calls before a prepare and after release, a second release, the failure table, a release from a state collector, a
 *   pause and play storm from four threads.
 * - Q3: the consumer script of `ConsumerFacadeR15ModelTest` (javafx module), with the same literal; the divergence of the
 *   JavaFX engine (key-frame snap) is documented there.
 * - Q4 analogue: the last asked speed wins over seeded random public call sequences, read off libvlc's rate.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjConsumerFacadeR15AdversarialTest {

    private val runtime: FakeVlcRuntime = FakeVlcRuntime().also {
        it.parsedInfo = VlcParsedInfo(durationMs = 3_000L, trackCount = 1, videoSize = null)
    }
    private val engines: MutableList<VlcjVideoEngine> = CopyOnWriteArrayList()

    @AfterTest
    fun tearDown() {
        runtime.shutdown()
    }

    private fun newPlayer(config: VideoPlayerConfig = VideoPlayerConfig(positionUpdateIntervalMs = ONE_HOUR_MS)): VideoPlayer {
        val engine = VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { runtime })
        engines += engine
        return createVlcjVideoPlayer(engine, config, Dispatchers.Default)
    }

    private fun prepare(player: VideoPlayer, source: VideoSource = VideoSource.File(TestClip.file.path)): VideoPlayerState {
        runBlocking { withTimeout(BOUND_MS) { player.prepare(source) } }
        return player.stateFlow.value
    }

    private fun VideoPlayer.line(label: String): String {
        val state: String = when (val s: VideoPlayerState = stateFlow.value) {
            VideoPlayerState.Idle -> "Idle"
            VideoPlayerState.Preparing -> "Preparing"
            is VideoPlayerState.Ready -> "Ready(${s.duration})"
            is VideoPlayerState.Playing -> "Playing(${s.duration})"
            is VideoPlayerState.Paused -> "Paused(${s.duration},${s.currentPosition})"
            is VideoPlayerState.Completed -> "Completed(${s.duration})"
            is VideoPlayerState.Error -> "Error(${s.cause::class.simpleName})"
        }
        return "$label | $state pos=${playbackPositionFlow.value} speed=${playbackSpeedFlow.value} vol=${volumeFlow.value} " +
            "muted=${isMutedFlow.value} repeat=${repeatModeFlow.value}"
    }

    /** Lets everything the player queued on libvlc's task thread run, then delivers `playing` as libvlc does after a play. */
    private fun outputBegins() {
        runtime.drainTasks()
        runtime.player.fire { playing() }
        runtime.drainTasks()
    }

    private fun theEnd() {
        outputBegins()
        runtime.player.fire { finished() }
        runtime.drainTasks()
    }

    // ---- Q1 / Q2 / Q3: the consumer script ----

    @Test
    fun `the consumer script gives the documented trace on the vlcj engine`() {
        val player: VideoPlayer = newPlayer()
        val trace: MutableList<String> = mutableListOf()
        fun note(label: String) {
            trace += player.line(label)
        }
        note("initial")
        player.play(); player.pause(); player.stop(); player.seekTo(500L); player.seekForward(1_000L)
        player.seekBackward(1_000L); player.replay()
        note("transport before prepare is ignored")
        player.setPlaybackSpeed(2f); player.setVolume(0.5f); player.setMuted(true); player.setRepeatMode(RepeatMode.One)
        note("settings before prepare show at once")
        prepare(player)
        note("prepared")
        player.setPlaybackSpeed(Float.NaN); note("speed NaN is ignored")
        player.setPlaybackSpeed(100f); note("speed 100")
        player.setPlaybackSpeed(0f); note("speed 0")
        player.setPlaybackSpeed(-5f); note("speed -5")
        player.setPlaybackSpeed(Float.POSITIVE_INFINITY); note("speed +Inf")
        player.setPlaybackSpeed(Float.NEGATIVE_INFINITY); note("speed -Inf")
        player.setPlaybackSpeed(1f); note("speed 1")
        player.setVolume(5f); note("volume 5")
        player.setVolume(-1f); note("volume -1")
        player.setVolume(Float.NaN); note("volume NaN is ignored")
        player.setVolume(0.5f); note("volume 0.5")
        player.seekTo(Long.MAX_VALUE); note("seek MAX in Ready")
        player.seekTo(-5L); note("seek -5 in Ready")
        player.seekTo(1_200L); note("seek 1200 in Ready")
        player.play(); outputBegins(); note("play")
        player.pause(); runtime.drainTasks(); note("pause")
        player.seekTo(1_000L); note("seek 1000 paused")
        player.seekForward(10_000L); note("seekForward 10000 paused")
        player.seekBackward(Long.MAX_VALUE); note("seekBackward MAX paused")
        player.setRepeatMode(RepeatMode.Off)
        player.play(); theEnd(); note("play to the end with repeat off")
        player.pause(); note("pause in Completed")
        player.seekTo(1_000L); note("seek 1000 in Completed")
        player.play(); theEnd(); note("play to the end again")
        player.stop(); note("stop")
        player.replay(); outputBegins(); note("replay")
        theEnd(); note("replay to the end")
        player.play(); outputBegins(); note("play from Completed")
        player.unload(); note("unload keeps the settings")
        prepare(player); note("prepare again")
        player.release(); note("release")
        player.release(); note("release twice")
        player.play(); player.seekTo(10L); note("transport after release is ignored")
        prepare(player); note("prepare after release")
        player.setVolume(0.3f); note("volume after release shows at once")
        assertEquals(EXPECTED_SCRIPT, trace, "the consumer script, trace per documented sentence")
    }

    // ---- Q1: the vlcj sentences ----

    @Test
    fun `the volume reaches libvlc as round of v times 100 and never above 100 and a muted player gets 0`() {
        val player: VideoPlayer = newPlayer()
        prepare(player)
        player.play()
        outputBegins()
        val fake: FakeVlcPlayer = runtime.player
        for ((asked: Float, percent: Int) in listOf(0.5f to 50, 1f to 100, 7f to 100, 0.004f to 0, 0.006f to 1, 0.995f to 100, 0.994f to 99, -3f to 0)) {
            player.setVolume(asked)
            runtime.drainTasks()
            assertEquals(percent, fake.volume(), "volume $asked")
        }
        player.setVolume(0.8f)
        player.setMuted(true)
        runtime.drainTasks()
        assertEquals(0, fake.volume(), "muted gives 0")
        assertEquals(0.8f, player.volumeFlow.value, "mute keeps the volume")
        player.setMuted(false)
        runtime.drainTasks()
        assertEquals(80, fake.volume(), "unmuting restores it")
        player.release()
    }

    @Test
    fun `a speed outside libvlc range reaches libvlc at the nearest bound while the flow shows the speed asked`() {
        val player: VideoPlayer = newPlayer(VideoPlayerConfig(positionUpdateIntervalMs = ONE_HOUR_MS, minPlaybackSpeed = 0.001f, maxPlaybackSpeed = 1_000f))
        prepare(player)
        player.play()
        outputBegins()
        val fake: FakeVlcPlayer = runtime.player
        for ((asked: Float, native: Float) in listOf(100f to 32f, 0.001f to 1f / 32f, 32f to 32f, 1f / 32f to 1f / 32f, 31.5f to 31.5f, 0.5f to 0.5f, 1_000f to 32f)) {
            player.setPlaybackSpeed(asked)
            runtime.drainTasks()
            assertEquals(native, fake.rate(), "speed $asked")
            assertEquals(asked, player.playbackSpeedFlow.value, "the flow shows the speed asked")
        }
        player.release()
    }

    @Test
    fun `a volume and a speed set while nothing plays are applied once output starts`() {
        val player: VideoPlayer = newPlayer()
        prepare(player)
        player.setVolume(0.25f)
        player.setPlaybackSpeed(2f)
        runtime.drainTasks()
        player.play()
        outputBegins()
        val fake: FakeVlcPlayer = runtime.player
        assertEquals(25, fake.volume(), "a volume set while nothing played is not lost")
        assertEquals(2f, fake.rate(), "nor a speed")
        player.release()
    }

    @Test
    fun `every failure of the failures table arrives as an error of the documented type`() {
        val player: VideoPlayer = newPlayer()
        val directory: String = java.nio.file.Files.createTempDirectory("r15-dir").toFile().also { it.deleteOnExit() }.path
        val bad: List<Pair<VideoSource, Class<out Throwable>>> = listOf(
            VideoSource.File("") to FileNotFoundException::class.java,
            VideoSource.Asset("") to FileNotFoundException::class.java,
            VideoSource.File(java.io.File(directory, "no-such-file-é中.mp4").path) to FileNotFoundException::class.java,
            VideoSource.File(directory) to FileNotFoundException::class.java,
            VideoSource.Asset("r15/no-such-asset.mp4") to FileNotFoundException::class.java,
            VideoSource.Remote("") to IllegalArgumentException::class.java,
            VideoSource.Remote("https://example.test/a.mp4", mapOf("Authorization" to "secret")) to IllegalArgumentException::class.java,
            VideoSource.Remote("https://example.test/a.mp4", mapOf("User-Agent" to "a\nb")) to IllegalArgumentException::class.java,
        )
        for ((source: VideoSource, expected: Class<out Throwable>) in bad) {
            val settled: VideoPlayerState = prepare(player, source)
            assertIs<VideoPlayerState.Error>(settled, "source $source settled on $settled")
            assertTrue(expected.isInstance(settled.cause), "source $source: ${settled.cause} is not a ${expected.simpleName}")
            player.play(); player.seekTo(5L)
            assertTrue(player.stateFlow.value is VideoPlayerState.Error, "a transport call woke a failed player: ${player.stateFlow.value}")
        }
        val header: VideoPlayerState.Error = prepare(player, bad[6].first) as VideoPlayerState.Error
        assertTrue("Authorization" in (header.cause.message ?: ""), "the message names the header: ${header.cause.message}")
        assertTrue("secret" !in (header.cause.message ?: ""), "the header value leaked: ${header.cause.message}")
        assertEquals(0, runtime.players.size, "a source rejected before VLC built a native player")
        val ok: VideoPlayerState = prepare(player)
        assertIs<VideoPlayerState.Ready>(ok)
        // a local source without a playable track (a directory on the class path, or a non-video file) fails with VlcPlaybackException
        runtime.parsedInfo = VlcParsedInfo(durationMs = 0L, trackCount = 0, videoSize = null)
        val noTrack: VideoPlayerState = prepare(player, VideoSource.File(TestClip.file.path))
        assertIs<VideoPlayerState.Error>(noTrack)
        assertIs<VlcPlaybackException>(noTrack.cause)
        // a parse that fails: VlcPlaybackException as well
        runtime.parsedInfo = VlcParsedInfo(durationMs = 3_000L, trackCount = 1, videoSize = null)
        runtime.parseResult = VlcParseStatus.FAILED
        val parseFailed: VideoPlayerState = prepare(player, VideoSource.File(TestClip.file.path))
        assertIs<VideoPlayerState.Error>(parseFailed)
        assertIs<VlcPlaybackException>(parseFailed.cause)
        player.release()
    }

    // ---- Q2: misuse ----

    @Test
    fun `calls after release reach no native player and release frees libvlc once`() {
        val player: VideoPlayer = newPlayer()
        prepare(player)
        player.play()
        outputBegins()
        player.release()
        runtime.drainTasks()
        withTimeout1 { runtime.released >= 1 }
        val fake: FakeVlcPlayer = runtime.player
        val calls: Int = fake.calls.size
        val log: Int = runtime.log.size
        player.play(); player.pause(); player.stop(); player.seekTo(1L); player.seekForward(); player.seekBackward(); player.replay()
        player.setPlaybackSpeed(3f); player.setVolume(0.1f); player.setMuted(true); player.setRepeatMode(RepeatMode.One)
        player.unload(); player.release()
        runtime.drainTasks()
        // late events from VLC's own threads after release are dropped
        runCatching { fake.fire { playing() } }
        runCatching { fake.fire { finished() } }
        runtime.drainTasks()
        assertEquals(calls, fake.calls.size, "native call after release: ${fake.calls.drop(calls)}")
        assertEquals(log, runtime.log.size, "runtime call after release: ${runtime.log.drop(log)}")
        withTimeout1 { runtime.released >= 1 }
        assertEquals(1, runtime.released, "libvlc freed once")
        assertEquals(1, fake.calls.count { it == "release" }, "native player freed once")
        val settled: VideoPlayerState = prepare(player)
        assertIs<VideoPlayerState.Error>(settled)
        assertIs<VideoPlayerReleasedException>(settled.cause)
        assertEquals(1, runtime.players.size, "a prepare after release built another native player")
    }

    @Test
    fun `a state collector that releases the player on the first Playing state ends with one release of each native object`() {
        val player: VideoPlayer = newPlayer()
        prepare(player)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val seen = CountDownLatch(1)
        try {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                player.stateFlow.collect { state: VideoPlayerState ->
                    if (state is VideoPlayerState.Playing) {
                        player.release()
                        seen.countDown()
                    }
                }
            }
            player.play()
            assertTrue(seen.await(BOUND_MS, TimeUnit.MILLISECONDS), "the collector never saw Playing")
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
            withTimeout1 { runtime.released >= 1 }
            runtime.drainTasks()
            assertEquals(1, runtime.released)
            assertEquals(1, runtime.player.calls.count { it == "release" })
            val calls: Int = runtime.player.calls.size
            player.play(); player.setPlaybackSpeed(2f); player.release()
            runtime.drainTasks()
            assertEquals(calls, runtime.player.calls.size, "native call after release")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a pause and play storm from four threads ends in a state the player agrees with and releases cleanly`() {
        val player: VideoPlayer = newPlayer()
        prepare(player)
        val failures: MutableList<Throwable> = CopyOnWriteArrayList()
        val go = CountDownLatch(1)
        val threads: List<Thread> = (0 until 4).map { id: Int ->
            Thread {
                val rng = Random(SEED_BASE + id)
                go.await()
                try {
                    repeat(STORM_OPS) {
                        when (rng.nextInt(6)) {
                            0, 1 -> player.play()
                            2, 3 -> player.pause()
                            4 -> player.seekTo(rng.nextLong(0L, 3_000L))
                            else -> player.setPlaybackSpeed(listOf(0.5f, 1f, 2f, 3f)[rng.nextInt(4)])
                        }
                    }
                } catch (t: Throwable) {
                    failures += t
                }
            }.apply { isDaemon = true; start() }
        }
        go.countDown()
        threads.forEach { it.join(BOUND_MS) }
        assertTrue(threads.none { it.isAlive }, "a storm thread did not finish: a deadlock")
        assertTrue(failures.isEmpty(), "a call threw: $failures")
        outputBegins()
        val state: VideoPlayerState = player.stateFlow.value
        assertTrue(state is VideoPlayerState.Playing || state is VideoPlayerState.Paused || state is VideoPlayerState.Ready, "state $state")
        player.pause()
        assertTrue(player.stateFlow.value is VideoPlayerState.Paused, "pause after the storm: ${player.stateFlow.value}")
        player.play()
        assertTrue(player.stateFlow.value is VideoPlayerState.Playing, "play after the storm: ${player.stateFlow.value}")
        val position: Long = player.playbackPositionFlow.value
        assertTrue(position in 0L..3_000L, "position $position")
        player.release()
        runtime.drainTasks()
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertEquals(1, runtime.player.calls.count { it == "release" })
    }

    @Test
    fun `playing a clip that ended restarts it from zero`() {
        val player: VideoPlayer = newPlayer()
        prepare(player)
        player.play()
        theEnd()
        assertEquals(VideoPlayerState.Completed(3_000L), player.stateFlow.value)
        player.play()
        assertTrue(player.stateFlow.value is VideoPlayerState.Playing, "state ${player.stateFlow.value}")
        runtime.drainTasks()
        outputBegins()
        assertTrue(runtime.player.calls.count { it == "play" } >= 2, "the second play never reached libvlc: ${runtime.player.calls}")
        assertTrue(runtime.player.playhead in -1L..100L, "the replay did not start from 0: playhead ${runtime.player.playhead} calls ${runtime.player.calls}")
        player.release()
    }

    // ---- Q4 analogue ----

    @Test
    fun `the last asked speed wins over seeded random public call sequences`() {
        val failures: MutableList<String> = mutableListOf()
        for (seed: Int in 0 until SEQUENCES) {
            val rt = FakeVlcRuntime().also { it.parsedInfo = VlcParsedInfo(durationMs = 600_000L, trackCount = 1, videoSize = null) }
            try {
                val engine = VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { rt })
                val player: VideoPlayer = createVlcjVideoPlayer(
                    engine, VideoPlayerConfig(positionUpdateIntervalMs = ONE_HOUR_MS, minPlaybackSpeed = 0.01f, maxPlaybackSpeed = 50f), Dispatchers.Default,
                )
                runBlocking { withTimeout(BOUND_MS) { player.prepare(VideoSource.File(TestClip.file.path)) } }
                val rng = Random(SEED_BASE + 100 + seed)
                val log: MutableList<String> = mutableListOf()
                val speeds: List<Float> = listOf(0.01f, 0.5f, 1f, 2f, 3f, 32f, 50f, 1f)
                repeat(OPS) {
                    when (rng.nextInt(10)) {
                        0, 1, 2 -> { player.play(); log += "play" }
                        3 -> { player.pause(); log += "pause" }
                        4 -> { player.stop(); log += "stop" }
                        5 -> { val t: Long = rng.nextLong(0L, 600_000L); player.seekTo(t); log += "seekTo($t)" }
                        6, 7 -> { val s: Float = speeds[rng.nextInt(speeds.size)]; player.setPlaybackSpeed(s); log += "speed($s)" }
                        8 -> { player.replay(); log += "replay" }
                        else -> { val s: Float = player.playbackSpeedFlow.value; player.setPlaybackSpeed(s); log += "speed(same $s)" }
                    }
                    if (rng.nextInt(3) == 0) {
                        rt.drainTasks(); rt.players.lastOrNull()?.fire { playing() }; log += "playing-event"
                    }
                }
                player.play(); log += "play(settle)"
                rt.drainTasks()
                rt.player.fire { playing() }
                rt.drainTasks()
                val asked: Float = player.playbackSpeedFlow.value
                val expected: Float = asked.coerceIn(1f / 32f, 32f)
                val problem: String? = when {
                    player.stateFlow.value !is VideoPlayerState.Playing -> "state ${player.stateFlow.value}"
                    rt.player.rate() != expected -> "libvlc rate ${rt.player.rate()}, last asked $asked (expected $expected)"
                    else -> null
                }
                if (problem != null) failures += "seed ${SEED_BASE + 100 + seed}: $problem after ${log.joinToString(" ")}"
                player.release()
            } finally {
                rt.shutdown()
            }
        }
        assertTrue(failures.isEmpty(), "${failures.size} of $SEQUENCES sequences broke 'the last asked speed wins':\n" + failures.take(5).joinToString("\n"))
    }

    private fun withTimeout1(condition: () -> Boolean) {
        // timing-hygiene-ignore: TH2 bound the real-time hang bound of a teardown that runs on the engine's own thread
        val deadline: Long = System.nanoTime() + BOUND_MS * NANOS_PER_MILLI
        while (!condition()) {
            // timing-hygiene-ignore: TH2 bound the real-time hang bound of a teardown that runs on the engine's own thread
            check(System.nanoTime() - deadline < 0L) { "the condition did not hold within $BOUND_MS ms" }
            Thread.onSpinWait()
        }
    }

    private companion object {
        const val ONE_HOUR_MS: Long = 3_600_000L
        const val BOUND_MS: Long = 10_000L
        const val NANOS_PER_MILLI: Long = 1_000_000L
        const val SEED_BASE: Int = 15_000
        const val STORM_OPS: Int = 600
        const val SEQUENCES: Int = 60
        const val OPS: Int = 14

        val EXPECTED_SCRIPT: List<String> = listOf(
            "initial | Idle pos=0 speed=1.0 vol=1.0 muted=false repeat=Off",
            "transport before prepare is ignored | Idle pos=0 speed=1.0 vol=1.0 muted=false repeat=Off",
            "settings before prepare show at once | Idle pos=0 speed=2.0 vol=0.5 muted=true repeat=One",
            "prepared | Ready(3000) pos=0 speed=2.0 vol=0.5 muted=true repeat=One",
            "speed NaN is ignored | Ready(3000) pos=0 speed=2.0 vol=0.5 muted=true repeat=One",
            "speed 100 | Ready(3000) pos=0 speed=3.0 vol=0.5 muted=true repeat=One",
            "speed 0 | Ready(3000) pos=0 speed=0.25 vol=0.5 muted=true repeat=One",
            "speed -5 | Ready(3000) pos=0 speed=0.25 vol=0.5 muted=true repeat=One",
            "speed +Inf | Ready(3000) pos=0 speed=3.0 vol=0.5 muted=true repeat=One",
            "speed -Inf | Ready(3000) pos=0 speed=0.25 vol=0.5 muted=true repeat=One",
            "speed 1 | Ready(3000) pos=0 speed=1.0 vol=0.5 muted=true repeat=One",
            "volume 5 | Ready(3000) pos=0 speed=1.0 vol=1.0 muted=true repeat=One",
            "volume -1 | Ready(3000) pos=0 speed=1.0 vol=0.0 muted=true repeat=One",
            "volume NaN is ignored | Ready(3000) pos=0 speed=1.0 vol=0.0 muted=true repeat=One",
            "volume 0.5 | Ready(3000) pos=0 speed=1.0 vol=0.5 muted=true repeat=One",
            "seek MAX in Ready | Ready(3000) pos=3000 speed=1.0 vol=0.5 muted=true repeat=One",
            "seek -5 in Ready | Ready(3000) pos=0 speed=1.0 vol=0.5 muted=true repeat=One",
            "seek 1200 in Ready | Ready(3000) pos=1200 speed=1.0 vol=0.5 muted=true repeat=One",
            "play | Playing(3000) pos=1200 speed=1.0 vol=0.5 muted=true repeat=One",
            "pause | Paused(3000,1200) pos=1200 speed=1.0 vol=0.5 muted=true repeat=One",
            "seek 1000 paused | Paused(3000,1000) pos=1000 speed=1.0 vol=0.5 muted=true repeat=One",
            "seekForward 10000 paused | Paused(3000,3000) pos=3000 speed=1.0 vol=0.5 muted=true repeat=One",
            "seekBackward MAX paused | Paused(3000,0) pos=0 speed=1.0 vol=0.5 muted=true repeat=One",
            "play to the end with repeat off | Completed(3000) pos=3000 speed=1.0 vol=0.5 muted=true repeat=Off",
            "pause in Completed | Completed(3000) pos=3000 speed=1.0 vol=0.5 muted=true repeat=Off",
            "seek 1000 in Completed | Paused(3000,1000) pos=1000 speed=1.0 vol=0.5 muted=true repeat=Off",
            "play to the end again | Completed(3000) pos=3000 speed=1.0 vol=0.5 muted=true repeat=Off",
            "stop | Ready(3000) pos=0 speed=1.0 vol=0.5 muted=true repeat=Off",
            "replay | Playing(3000) pos=0 speed=1.0 vol=0.5 muted=true repeat=Off",
            "replay to the end | Completed(3000) pos=3000 speed=1.0 vol=0.5 muted=true repeat=Off",
            "play from Completed | Playing(3000) pos=0 speed=1.0 vol=0.5 muted=true repeat=Off",
            "unload keeps the settings | Idle pos=0 speed=1.0 vol=0.5 muted=true repeat=Off",
            "prepare again | Ready(3000) pos=0 speed=1.0 vol=0.5 muted=true repeat=Off",
            "release | Idle pos=0 speed=1.0 vol=0.5 muted=true repeat=Off",
            "release twice | Idle pos=0 speed=1.0 vol=0.5 muted=true repeat=Off",
            "transport after release is ignored | Idle pos=0 speed=1.0 vol=0.5 muted=true repeat=Off",
            "prepare after release | Error(VideoPlayerReleasedException) pos=0 speed=1.0 vol=0.5 muted=true repeat=Off",
            "volume after release shows at once | Error(VideoPlayerReleasedException) pos=0 speed=1.0 vol=0.3 muted=true repeat=Off",
        )
    }
}
