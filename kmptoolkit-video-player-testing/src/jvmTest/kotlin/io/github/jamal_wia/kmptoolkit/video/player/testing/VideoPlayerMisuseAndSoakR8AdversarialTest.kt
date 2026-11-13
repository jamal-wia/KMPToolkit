package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerReleasedException
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.progress
import java.lang.ref.WeakReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 8 (breaker at): API misuse of the public types as a consumer's tool, and a long-run soak of the shared
 * player on the scriptable fake. The soak length is bounded by the system property kmptoolkit.test.soak.seconds (or
 * the system property of the same name); the committed default is a few seconds.
 */
class VideoPlayerMisuseAndSoakR8AdversarialTest {

    private val extremeLongs: List<Long> = listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, -1L, 0L, 1L, Long.MAX_VALUE - 1, Long.MAX_VALUE)
    private val extremeFloats: List<Float> = listOf(
        Float.NaN, Float.NEGATIVE_INFINITY, -1f, -0f, 0f, Float.MIN_VALUE, 0.5f, 1f, 2f, Float.MAX_VALUE, Float.POSITIVE_INFINITY,
    )

    private fun soakSeconds(): Long =
        System.getProperty("kmptoolkit.test.soak.seconds")?.toLongOrNull() ?: 3L

    private fun newPlayer(
        engine: FakeVideoPlaybackEngine,
        config: VideoPlayerConfig = VideoPlayerConfig(positionUpdateIntervalMs = 5L),
    ): VideoPlayer = createVideoPlayer(engine, config, Dispatchers.Default)

    private fun VideoPlayer.checkInvariants(where: String) {
        val range: ClosedFloatingPointRange<Float> = playbackSpeedRange
        val speed: Float = playbackSpeedFlow.value
        assertTrue(speed in range, "$where: speed $speed outside $range")
        val volume: Float = volumeFlow.value
        assertTrue(volume in 0f..1f, "$where: volume $volume")
        assertTrue(playbackPositionFlow.value >= 0L, "$where: position ${playbackPositionFlow.value}")
        assertTrue(bufferedPositionFlow.value >= 0L, "$where: buffered ${bufferedPositionFlow.value}")
        when (val state: VideoPlayerState = stateFlow.value) {
            is VideoPlayerState.Playing -> {
                assertTrue(state.currentPosition >= 0L, "$where: $state")
                if (state.duration > 0L) assertTrue(state.currentPosition <= state.duration, "$where: $state")
            }
            is VideoPlayerState.Paused -> {
                assertTrue(state.currentPosition >= 0L, "$where: $state")
                if (state.duration > 0L) assertTrue(state.currentPosition <= state.duration, "$where: $state")
            }
            is VideoPlayerState.Ready -> assertTrue(state.duration >= 0L, "$where: $state")
            is VideoPlayerState.Completed -> assertTrue(state.duration >= 0L, "$where: $state")
            else -> Unit
        }
    }

    private fun VideoPlayer.hammer(where: String) {
        for (v: Long in extremeLongs) {
            seekTo(v); checkInvariants("$where seekTo($v)")
            seekForward(v); checkInvariants("$where seekForward($v)")
            seekBackward(v); checkInvariants("$where seekBackward($v)")
        }
        for (f: Float in extremeFloats) {
            setPlaybackSpeed(f); checkInvariants("$where speed($f)")
            setVolume(f); checkInvariants("$where volume($f)")
        }
        setMuted(true); setMuted(false); setMuted(true)
        RepeatMode.entries.forEach { setRepeatMode(it) }
        checkInvariants("$where settings")
        setMuted(false)
        setVolume(1f)
        setPlaybackSpeed(1f)
    }

    @Test
    fun `every public call with every extreme argument in every state throws nothing and keeps the invariants`() = runBlocking {
        withTimeout(60_000L) {
            for (stateName: String in listOf("idle", "preparing", "ready", "playing", "paused", "completed", "error", "released")) {
                val engine = FakeVideoPlaybackEngine(durationMs = if (stateName == "completed") 100L else 5_000L)
                val player: VideoPlayer = newPlayer(engine)
                var loading: Job? = null
                when (stateName) {
                    "preparing" -> {
                        engine.suspendLoads = true
                        loading = launch(Dispatchers.Default) { player.prepare(VideoSource.File("x.mp4")) }
                        while (!engine.isLoadPending) kotlinx.coroutines.yield()
                    }
                    "ready" -> player.prepare(VideoSource.File("x.mp4"))
                    "playing" -> { player.prepare(VideoSource.File("x.mp4")); player.play() }
                    "paused" -> { player.prepare(VideoSource.File("x.mp4")); player.play(); player.pause() }
                    "completed" -> { player.prepare(VideoSource.File("x.mp4")); player.play(); engine.completePlayback() }
                    "error" -> { engine.loadFailure = IllegalStateException("boom"); player.prepare(VideoSource.File("x.mp4")) }
                    "released" -> { player.prepare(VideoSource.File("x.mp4")); player.release() }
                }
                player.hammer(stateName)
                player.play(); player.checkInvariants("$stateName play")
                player.pause(); player.stop(); player.replay(); player.pause()
                player.checkInvariants("$stateName transport")
                player.unload()
                assertEquals(VideoPlayerState.Idle, player.stateFlow.value.takeIf { stateName != "released" } ?: VideoPlayerState.Idle)
                loading?.cancelAndJoin()
                if (stateName == "released") {
                    player.prepare(VideoSource.Remote("", mapOf("" to ""))) // after release: documented Error(VideoPlayerReleasedException)
                    val state: VideoPlayerState = player.stateFlow.value
                    assertTrue(state is VideoPlayerState.Error && state.cause is VideoPlayerReleasedException, "after release: $state")
                }
                player.release(); player.release(); player.close()
                assertFalse(engine.hasListener, "$stateName: listener still attached after release")
                assertEquals(1, engine.disposeCount, "$stateName: dispose count")
            }
        }
    }

    @Test
    fun `odd sources in every state never throw and a failed prepare settles on an error or ready`() = runBlocking {
        withTimeout(30_000L) {
            val sources: List<VideoSource> = listOf(
                VideoSource.Asset(""), VideoSource.File(""), VideoSource.Remote(""),
                VideoSource.File("\u0000"), VideoSource.Asset("a".repeat(100_000)),
                VideoSource.Remote("http://x", mapOf("" to "", "k" to "v".repeat(10_000))),
            )
            val engine = FakeVideoPlaybackEngine()
            val player: VideoPlayer = newPlayer(engine)
            for (source: VideoSource in sources) {
                player.prepare(source)
                val state: VideoPlayerState = player.stateFlow.value
                assertTrue(state is VideoPlayerState.Ready, "fake loads any source: $state")
                assertEquals(source, engine.loadedSources.last())
            }
            player.release()
        }
    }

    @Test
    fun `public data types honour equals hashCode and toString`() {
        val a = VideoSize(Int.MAX_VALUE, 1)
        assertEquals(a, VideoSize(Int.MAX_VALUE, 1)); assertEquals(a.hashCode(), VideoSize(Int.MAX_VALUE, 1).hashCode())
        assertTrue(a.aspectRatio.isFinite() && a.aspectRatio > 0f)
        val b = VideoSize(1, Int.MAX_VALUE)
        assertTrue(b.aspectRatio.isFinite() && b.aspectRatio > 0f)
        for (bad: Int in listOf(0, -1, Int.MIN_VALUE)) {
            kotlin.runCatching { VideoSize(bad, 1) }.let { assertTrue(it.exceptionOrNull() is IllegalArgumentException) }
            kotlin.runCatching { VideoSize(1, bad) }.let { assertTrue(it.exceptionOrNull() is IllegalArgumentException) }
        }
        assertFalse(VideoSource.Asset("p") == VideoSource.File("p")) // same path, different kind
        assertEquals(VideoSource.Asset("p").hashCode(), VideoSource.Asset("p").hashCode())
        val headers: MutableMap<String, String> = mutableMapOf("Authorization" to "secret-token")
        val remote = VideoSource.Remote("u", headers)
        headers["X"] = "y"
        assertEquals(setOf("Authorization"), remote.headers.keys, "headers are a snapshot")
        assertFalse(remote.toString().contains("secret-token"), "toString must redact: $remote")
        assertEquals(remote, VideoSource.Remote("u", mapOf("Authorization" to "secret-token")))
        assertEquals(remote.hashCode(), VideoSource.Remote("u", mapOf("Authorization" to "secret-token")).hashCode())
        assertFalse(remote == VideoSource.Remote("u", mapOf("Authorization" to "other")))
        val cause = RuntimeException("x")
        assertEquals(VideoPlayerState.Error(cause), VideoPlayerState.Error(cause))
        assertEquals(VideoPlayerState.Playing(1L, 2L).hashCode(), VideoPlayerState.Playing(1L, 2L).hashCode())
        assertFalse(VideoPlayerState.Playing(1L, 2L) == VideoPlayerState.Paused(1L, 2L))
        for (s: VideoPlayerState in listOf(
            VideoPlayerState.Idle, VideoPlayerState.Preparing, VideoPlayerState.Ready(Long.MAX_VALUE),
            VideoPlayerState.Playing(Long.MAX_VALUE, Long.MAX_VALUE), VideoPlayerState.Paused(-5L, -5L),
            VideoPlayerState.Completed(Long.MIN_VALUE), VideoPlayerState.Error(cause),
        )) {
            assertTrue(s.toString().isNotEmpty())
            val p: Float = s.progress
            assertTrue(p in 0f..1f, "progress $p of $s")
        }
    }

    @Test
    fun `config rejects every bad argument and accepts the extremes it documents`() {
        val nan: Float = Float.NaN
        val inf: Float = Float.POSITIVE_INFINITY
        fun bad(block: () -> Unit) { assertTrue(kotlin.runCatching(block).exceptionOrNull() is IllegalArgumentException) }
        bad { VideoPlayerConfig(positionUpdateIntervalMs = 0L) }
        bad { VideoPlayerConfig(positionUpdateIntervalMs = Long.MIN_VALUE) }
        bad { VideoPlayerConfig(minPlaybackSpeed = nan) }
        bad { VideoPlayerConfig(minPlaybackSpeed = 0f) }
        bad { VideoPlayerConfig(minPlaybackSpeed = -1f) }
        bad { VideoPlayerConfig(minPlaybackSpeed = inf) }
        bad { VideoPlayerConfig(maxPlaybackSpeed = nan) }
        bad { VideoPlayerConfig(maxPlaybackSpeed = inf) }
        bad { VideoPlayerConfig(minPlaybackSpeed = 2f, maxPlaybackSpeed = 1f) }
        val extreme = VideoPlayerConfig(positionUpdateIntervalMs = Long.MAX_VALUE, minPlaybackSpeed = Float.MIN_VALUE, maxPlaybackSpeed = Float.MAX_VALUE)
        val engine = FakeVideoPlaybackEngine()
        val player: VideoPlayer = newPlayer(engine, extreme)
        runBlocking { withTimeout(10_000L) { player.prepare(VideoSource.File("x")); player.play(); player.setPlaybackSpeed(Float.MAX_VALUE) } }
        assertEquals(Float.MAX_VALUE, player.playbackSpeedFlow.value)
        player.setPlaybackSpeed(0f)
        assertEquals(Float.MIN_VALUE, player.playbackSpeedFlow.value)
        player.release()
        val single = VideoPlayerConfig(minPlaybackSpeed = 7f, maxPlaybackSpeed = 7f)
        val p2: VideoPlayer = newPlayer(FakeVideoPlaybackEngine(), single)
        assertEquals(7f, p2.playbackSpeedFlow.value)
        p2.release()
    }

    @Test
    fun `seeded random operations with leak checks hold the invariants for the soak time`() = runBlocking {
        val seed: Long = (System.getProperty("kmptoolkit.test.soak.seed") ?: "8").toLong()
        val random = Random(seed)
        val threadsBefore: Int = Thread.getAllStackTraces().keys.count { it.isAlive }
        val deadline: Long = System.nanoTime() + soakSeconds() * 1_000_000_000L
        val engine = FakeVideoPlaybackEngine(durationMs = 2_000L)
        val player: VideoPlayer = newPlayer(engine)
        val loads: MutableList<Job> = mutableListOf()
        var steps = 0
        var cycles = 0
        try {
            withTimeout((soakSeconds() + 60L) * 1_000L) {
                while (System.nanoTime() < deadline) {
                    steps++
                    when (random.nextInt(24)) {
                        0 -> player.play()
                        1 -> player.pause()
                        2 -> player.stop()
                        3 -> player.seekTo(random.nextLong(-100L, 3_000L))
                        4 -> player.seekForward(random.nextLong(-5_000L, 5_000L))
                        5 -> player.seekBackward(random.nextLong(-5_000L, 5_000L))
                        6 -> player.replay()
                        7 -> player.setPlaybackSpeed(extremeFloats[random.nextInt(extremeFloats.size)])
                        8 -> player.setVolume(random.nextFloat() * 3f - 1f)
                        9 -> player.setMuted(random.nextBoolean())
                        10 -> player.setRepeatMode(RepeatMode.entries[random.nextInt(2)].also { engine.completePlayback().takeIf { _ -> false } })
                        11 -> player.unload()
                        12 -> { engine.suspendLoads = random.nextBoolean(); loads += launch(Dispatchers.Default, CoroutineStart.DEFAULT) { player.prepare(VideoSource.File("s${random.nextInt(5)}")) } }
                        13 -> engine.finishLoad()
                        14 -> engine.failLoad(IllegalStateException("soak"))
                        15 -> engine.advancePositionBy(random.nextLong(0L, 700L))
                        16 -> engine.completePlayback()
                        17 -> engine.failPlayback(IllegalStateException("playback"))
                        18 -> engine.reportBuffering(random.nextBoolean())
                        19 -> engine.reportVideoSize(if (random.nextBoolean()) VideoSize(random.nextInt(1, 4000), random.nextInt(1, 4000)) else null)
                        20 -> if (loads.isNotEmpty()) loads.removeAt(random.nextInt(loads.size)).cancel()
                        21 -> kotlinx.coroutines.yield()
                        22 -> { engine.suspendLoads = false; engine.loadFailure = null }
                        else -> if (random.nextInt(6) == 0) { engine.suspendLoads = false; engine.finishLoad() }
                    }
                    if (steps % 25 == 0) player.checkInvariants("soak step $steps seed $seed")
                    if (steps % 50_000 == 0) cycles++
                }
                engine.suspendLoads = false
                engine.finishLoad()
                loads.forEach { it.cancel() }
                loads.forEach { it.join() }
            }
        } finally {
            player.release()
        }
        player.checkInvariants("after soak")
        assertFalse(engine.hasListener)
        assertEquals(1, engine.disposeCount)
        // Threads: the default dispatcher is bounded; a leak per prepare would show as hundreds.
        val threadsAfter: Int = Thread.getAllStackTraces().keys.count { it.isAlive }
        assertTrue(threadsAfter - threadsBefore < 64, "threads grew $threadsBefore -> $threadsAfter after $steps steps")
    }

    @Test
    fun `a thousand load play release cycles leave nothing reachable`() = runBlocking {
        withTimeout(120_000L) {
            val refs: MutableList<WeakReference<Any>> = mutableListOf()
            val threadsBefore: Int = Thread.getAllStackTraces().keys.count { it.isAlive }
            repeat(1_000) {
                val engine = FakeVideoPlaybackEngine(durationMs = 500L)
                val player: VideoPlayer = newPlayer(engine)
                player.prepare(VideoSource.File("x"))
                player.play()
                player.seekTo(100L)
                player.release()
                if (it % 10 == 0) { refs += WeakReference(engine); refs += WeakReference(player) }
            }
            var alive: Int = refs.size
            val end: Long = System.nanoTime() + 20_000_000_000L
            while (alive > 0 && System.nanoTime() < end) {
                System.gc(); Thread.sleep(50L)
                alive = refs.count { it.get() != null }
            }
            if (alive > 0) fail("$alive of ${refs.size} released players/engines are still reachable after gc")
            val threadsAfter: Int = Thread.getAllStackTraces().keys.count { it.isAlive }
            assertTrue(threadsAfter - threadsBefore < 64, "threads $threadsBefore -> $threadsAfter")
        }
    }
}
