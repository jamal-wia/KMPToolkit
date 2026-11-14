package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerReleasedException
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.duration
import io.github.jamal_wia.kmptoolkit.video.player.playbackPosition
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * D1 round 14, question 3: sentences of `03-guide.md`, `04-api-reference.md` and `06-testing.md` about the transport, the
 * flows and the settings, each written from the sentence (the expectation is the sentence, not the code), on the public
 * `FakeVideoPlaybackEngine`, in virtual time. Sentences are quoted in each test name or comment.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocSentencesD1R14AdversarialTest {

    private val source: VideoSource = VideoSource.Remote("https://example.test/v.mp4")

    private class Rig(durationMs: Long = 10_000L, config: VideoPlayerConfig = VideoPlayerConfig(positionUpdateIntervalMs = 100L)) {
        val scheduler: TestCoroutineScheduler = TestCoroutineScheduler()
        val engine: FakeVideoPlaybackEngine = FakeVideoPlaybackEngine(durationMs = durationMs)
        val escaped: MutableList<Throwable> = mutableListOf()
        val player: VideoPlayer = createVideoPlayer(
            engine,
            config,
            StandardTestDispatcher(scheduler) + CoroutineExceptionHandler { _, thrown -> escaped += thrown },
        )
        val state: VideoPlayerState get() = player.stateFlow.value

        fun prepare(src: VideoSource = VideoSource.Remote("https://example.test/v.mp4")) {
            runBlocking { player.prepare(src) }
        }

        fun tick() {
            scheduler.advanceTimeBy(150L)
            scheduler.runCurrent()
        }
    }

    // --- 04: transport ---

    @Test
    fun `every transport call is ignored in Idle Preparing and Error and after release`() {
        val rig = Rig()
        fun poke() {
            rig.player.play(); rig.player.pause(); rig.player.stop(); rig.player.seekTo(500L)
            rig.player.seekForward(); rig.player.seekBackward(); rig.player.replay()
        }
        // Idle
        poke()
        assertEquals(VideoPlayerState.Idle, rig.state)
        assertEquals(emptyList(), rig.engine.seekTargets)
        assertFalse(rig.engine.isPlaying)
        // Error
        rig.engine.loadFailure = IOException("down")
        rig.prepare()
        assertTrue(rig.state is VideoPlayerState.Error)
        poke()
        assertTrue(rig.state is VideoPlayerState.Error, "state ${rig.state}")
        assertEquals(emptyList(), rig.engine.seekTargets)
        assertFalse(rig.engine.isPlaying)
        // After release
        rig.engine.loadFailure = null
        rig.prepare()
        rig.player.release()
        val releases: Int = rig.engine.releaseCount
        poke()
        assertEquals(VideoPlayerState.Idle, rig.state)
        assertEquals(emptyList(), rig.engine.seekTargets)
        assertFalse(rig.engine.isPlaying)
        assertEquals(releases, rig.engine.releaseCount)
        assertEquals(emptyList(), rig.escaped)
    }

    @Test
    fun `a transport call is ignored in Preparing`() {
        val rig = Rig()
        rig.engine.suspendLoads = true
        val caller: Job = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined).let { scope ->
            scope.launchPrepare(rig.player, source)
        }
        assertEquals(VideoPlayerState.Preparing, rig.state)
        rig.player.play(); rig.player.pause(); rig.player.stop(); rig.player.seekTo(5L); rig.player.replay()
        assertEquals(VideoPlayerState.Preparing, rig.state)
        assertEquals(emptyList(), rig.engine.seekTargets)
        assertFalse(rig.engine.isPlaying)
        rig.engine.finishLoad()
        runBlocking { caller.join() }
        assertEquals(VideoPlayerState.Ready(10_000L), rig.state)
        rig.player.release()
    }

    @Test
    fun `play is ignored while already Playing and pause in any other state`() {
        val rig = Rig()
        rig.prepare()
        rig.player.pause()
        assertEquals(VideoPlayerState.Ready(10_000L), rig.state)
        rig.player.play()
        rig.player.play()
        assertTrue(rig.state is VideoPlayerState.Playing)
        rig.player.pause()
        val paused: VideoPlayerState = rig.state
        rig.engine.advancePositionTo(7_000L)
        rig.player.pause()
        assertEquals(paused, rig.state, "a second pause moved the state")
        rig.engine.completePlayback()
        // completePlayback on a paused player: the report is dropped for a non playing source or ends it; either way pause is a no-op.
        val before: VideoPlayerState = rig.state
        rig.player.pause()
        assertEquals(before, rig.state)
        rig.player.release()
        assertEquals(emptyList(), rig.escaped)
    }

    @Test
    fun `stop from any playable state is Ready with the playhead at zero and the source stays loaded`() {
        for (start in listOf("ready", "playing", "paused", "completed")) {
            val rig = Rig()
            rig.prepare()
            when (start) {
                "playing" -> rig.player.play()
                "paused" -> { rig.player.play(); rig.player.pause() }
                "completed" -> { rig.player.play(); rig.engine.completePlayback() }
            }
            val releases: Int = rig.engine.releaseCount
            rig.player.stop()
            assertEquals(VideoPlayerState.Ready(10_000L), rig.state, "from $start")
            assertEquals(0L, rig.player.playbackPositionFlow.value, "from $start")
            assertEquals(0L, rig.engine.positionMs)
            assertFalse(rig.engine.isPlaying)
            assertEquals(releases, rig.engine.releaseCount, "stop freed the source from $start")
            rig.player.play()
            assertEquals(0L, rig.state.playbackPosition, "play after stop starts at 0 (from $start)")
            rig.player.release()
        }
    }

    @Test
    fun `replay seeks to zero and plays from every playable state`() {
        for (start in listOf("ready", "playing", "paused", "completed")) {
            val rig = Rig()
            rig.prepare()
            when (start) {
                "playing" -> { rig.player.play(); rig.engine.advancePositionTo(4_000L); rig.tick() }
                "paused" -> { rig.player.play(); rig.engine.advancePositionTo(4_000L); rig.player.pause() }
                "completed" -> { rig.player.play(); rig.engine.completePlayback() }
            }
            rig.player.replay()
            val state: VideoPlayerState = rig.state
            assertTrue(state is VideoPlayerState.Playing, "from $start state $state")
            assertEquals(0L, state.currentPosition, "from $start")
            assertEquals(0L, rig.player.playbackPositionFlow.value)
            assertTrue(rig.engine.isPlaying)
            assertEquals(0L, rig.engine.seekTargets.last())
            rig.player.release()
        }
    }

    @Test
    fun `seekTo moves a Playing player and keeps it Playing and a Completed one becomes Paused`() {
        val rig = Rig()
        rig.prepare()
        rig.player.play()
        rig.player.seekTo(3_000L)
        assertEquals(VideoPlayerState.Playing(10_000L, 3_000L), rig.state)
        rig.engine.completePlayback()
        assertEquals(VideoPlayerState.Completed(10_000L), rig.state)
        rig.player.seekTo(2_000L)
        assertEquals(VideoPlayerState.Paused(10_000L, 2_000L), rig.state)
        assertFalse(rig.engine.isPlaying, "a seek out of Completed must not start playback")
        rig.player.release()
    }

    // --- 04: flows ---

    @Test
    fun `the buffered position is snapshotted when a load finishes and on pause and polled only while Playing`() {
        val rig = Rig()
        rig.prepare()
        rig.engine.bufferedPositionMs = 2_000L
        rig.tick()
        assertEquals(0L, rig.player.bufferedPositionFlow.value, "not polled while Ready")
        rig.player.play()
        rig.engine.bufferedPositionMs = 5_000L
        rig.tick()
        assertEquals(5_000L, rig.player.bufferedPositionFlow.value)
        rig.engine.bufferedPositionMs = 6_000L
        rig.player.pause()
        assertEquals(6_000L, rig.player.bufferedPositionFlow.value, "snapshotted on pause")
        rig.engine.bufferedPositionMs = 9_000L
        rig.tick()
        assertEquals(6_000L, rig.player.bufferedPositionFlow.value, "not polled while Paused")
        rig.player.release()
    }

    @Test
    fun `polling stops entirely whenever the state is not Playing`() {
        val rig = Rig()
        rig.prepare()
        rig.player.play()
        rig.engine.advancePositionTo(1_000L)
        rig.tick()
        assertEquals(1_000L, rig.player.playbackPositionFlow.value)
        for (stopper in listOf<() -> Unit>({ rig.player.pause() }, { rig.player.stop() }, { rig.engine.completePlayback() })) {
            rig.player.play()
            stopper()
            val position: Long = rig.player.playbackPositionFlow.value
            val buffered: Long = rig.player.bufferedPositionFlow.value
            val state: VideoPlayerState = rig.state
            rig.engine.advancePositionTo(8_000L)
            rig.engine.bufferedPositionMs = 8_500L
            repeat(5) { rig.tick() }
            assertEquals(position, rig.player.playbackPositionFlow.value)
            assertEquals(buffered, rig.player.bufferedPositionFlow.value)
            assertEquals(state, rig.state)
        }
        rig.player.release()
        assertEquals(emptyList(), rig.escaped)
    }

    @Test
    fun `a stall reported before Ready is kept and shown with Ready and a stall after the end is ignored`() {
        val rig = Rig()
        rig.engine.suspendLoads = true
        val caller: Job = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined).launchPrepare(rig.player, source)
        rig.engine.reportBuffering(true)
        assertFalse(rig.player.isBufferingFlow.value, "not shown while preparing")
        rig.engine.finishLoad()
        runBlocking { caller.join() }
        assertEquals(VideoPlayerState.Ready(10_000L), rig.state)
        assertTrue(rig.player.isBufferingFlow.value, "kept and shown with Ready")
        rig.player.play()
        rig.engine.completePlayback()
        assertFalse(rig.player.isBufferingFlow.value, "false on completion")
        rig.engine.reportBuffering(true)
        assertFalse(rig.player.isBufferingFlow.value, "a stall after the end is ignored while Completed")
        rig.player.release()
    }

    @Test
    fun `the picture size is kept on Completed and null again on prepare failure unload and release`() {
        for (ender in listOf("prepare", "failure", "unload", "release")) {
            val rig = Rig()
            rig.prepare()
            rig.engine.reportVideoSize(VideoSize(640, 360))
            assertEquals(VideoSize(640, 360), rig.player.videoSizeFlow.value)
            rig.player.play()
            rig.engine.completePlayback()
            assertEquals(VideoSize(640, 360), rig.player.videoSizeFlow.value, "kept on Completed")
            when (ender) {
                "prepare" -> { rig.engine.suspendLoads = true; rig.player.launchedPrepare(source) }
                "failure" -> { rig.player.seekTo(1L); rig.player.play(); rig.engine.failPlayback(IOException("x")) }
                "unload" -> rig.player.unload()
                else -> rig.player.release()
            }
            assertEquals(null, rig.player.videoSizeFlow.value, "after $ender")
            rig.engine.finishLoad()
            rig.player.release()
        }
    }

    @Test
    fun `a length the platform reports later replaces zero at the next update and no state reverts it to zero`() {
        for (step in listOf("poll", "pause", "seek", "stop")) {
            val rig = Rig(durationMs = 0L)
            rig.prepare()
            assertEquals(VideoPlayerState.Ready(0L), rig.state)
            rig.player.play()
            rig.engine.durationMs = 5_000L
            when (step) {
                "poll" -> rig.tick()
                "pause" -> rig.player.pause()
                "seek" -> rig.player.seekTo(1_000L)
                else -> rig.player.stop()
            }
            assertEquals(5_000L, rig.state.duration, "after $step")
            // The platform forgets the length again: nothing reverts what is known.
            rig.engine.durationMs = 0L
            rig.tick()
            rig.player.play()
            rig.player.pause()
            rig.player.seekTo(500L)
            rig.player.stop()
            rig.player.play()
            rig.tick()
            assertEquals(5_000L, rig.state.duration, "reverted to zero after $step")
            rig.player.release()
        }
    }

    @Test
    fun `a source of unknown length that plays to its end completes at where it ended and that length is then known`() {
        val rig = Rig(durationMs = 0L)
        rig.prepare()
        rig.player.play()
        rig.engine.advancePositionTo(4_200L)
        rig.engine.completePlayback()
        assertEquals(VideoPlayerState.Completed(4_200L), rig.state)
        assertEquals(4_200L, rig.player.playbackPositionFlow.value)
        rig.player.seekTo(9_999L)
        assertEquals(4_200L, rig.engine.seekTargets.last(), "an absolute seek clamps to 0..end")
        rig.player.stop()
        assertEquals(VideoPlayerState.Ready(4_200L), rig.state, "stop keeps the length")
        rig.player.release()
        val zero = Rig(durationMs = 0L)
        zero.prepare()
        zero.player.play()
        zero.engine.completePlayback()
        assertEquals(VideoPlayerState.Completed(0L), zero.state, "if the end is still 0")
        zero.player.release()
    }

    // --- 04: settings ---

    @Test
    fun `settings take effect at once with nothing loaded and after release and survive prepare and unload`() {
        val rig = Rig()
        rig.player.setPlaybackSpeed(2f)
        rig.player.setVolume(0.3f)
        rig.player.setMuted(true)
        rig.player.setRepeatMode(RepeatMode.One)
        assertEquals(2f, rig.player.playbackSpeedFlow.value)
        assertEquals(0.3f, rig.player.volumeFlow.value)
        assertTrue(rig.player.isMutedFlow.value)
        assertEquals(RepeatMode.One, rig.player.repeatModeFlow.value)
        rig.prepare()
        assertEquals(2f, rig.engine.appliedSpeed)
        assertEquals(0f, rig.engine.appliedVolume, "0f while muted")
        assertTrue(rig.engine.isLooping)
        rig.player.unload()
        rig.player.setMuted(false)
        rig.prepare()
        assertEquals(0.3f, rig.engine.appliedVolume, "unmuting restores the volume, which survived unload and prepare")
        rig.player.release()
        rig.player.setPlaybackSpeed(1.5f)
        rig.player.setVolume(0.9f)
        rig.player.setMuted(true)
        rig.player.setRepeatMode(RepeatMode.Off)
        assertEquals(1.5f, rig.player.playbackSpeedFlow.value)
        assertEquals(0.9f, rig.player.volumeFlow.value)
        assertTrue(rig.player.isMutedFlow.value)
        assertEquals(RepeatMode.Off, rig.player.repeatModeFlow.value)
    }

    @Test
    fun `a speed range that excludes 1 starts at its nearest bound and every flow value lies in the range`() {
        for ((lo, hi) in listOf(2f to 3f, 0.1f to 0.5f)) {
            val rig = Rig(config = VideoPlayerConfig(positionUpdateIntervalMs = 100L, minPlaybackSpeed = lo, maxPlaybackSpeed = hi))
            assertEquals(lo..hi, rig.player.playbackSpeedRange)
            val expected: Float = 1f.coerceIn(lo, hi)
            assertEquals(expected, rig.player.playbackSpeedFlow.value)
            rig.prepare()
            rig.player.play()
            assertEquals(expected, rig.engine.appliedSpeed, "the platform got the clamped start rate")
            rig.player.release()
        }
    }

    @Test
    fun `with repeat One a playing source starts over a paused one rewinds and stays paused and Ready or Completed ignore a completion`() {
        val rig = Rig()
        rig.prepare()
        rig.player.setRepeatMode(RepeatMode.One)
        // The fake loops on its own and never reports; the contract also covers a late report, sent through the listener.
        val sink = checkNotNull(listenerOf(rig))
        rig.player.play()
        rig.engine.advancePositionTo(9_000L)
        sink.onCompleted()
        assertEquals(VideoPlayerState.Playing(10_000L, 0L), rig.state, "a playing source starts over")
        assertTrue(rig.engine.isPlaying)
        rig.engine.advancePositionTo(9_000L)
        rig.player.pause()
        sink.onCompleted()
        assertEquals(VideoPlayerState.Paused(10_000L, 0L), rig.state, "a paused one rewinds to 0 and stays paused")
        assertFalse(rig.engine.isPlaying)
        rig.player.stop()
        val ready: VideoPlayerState = rig.state
        sink.onCompleted()
        assertEquals(ready, rig.state, "ignored while Ready")
        rig.player.release()
        // Completed when One is set: stays until the next play().
        val other = Rig()
        other.prepare()
        other.player.play()
        other.engine.completePlayback()
        assertEquals(VideoPlayerState.Completed(10_000L), other.state)
        other.player.setRepeatMode(RepeatMode.One)
        assertEquals(VideoPlayerState.Completed(10_000L), other.state, "a Completed player stays when One is set")
        checkNotNull(listenerOf(other)).onCompleted()
        assertEquals(VideoPlayerState.Completed(10_000L), other.state, "ignored while Completed")
        other.player.play()
        assertTrue(other.state is VideoPlayerState.Playing)
        other.player.release()
    }

    // --- 04: lifecycle and prepare ---

    @Test
    fun `prepare after release sets the released error loads nothing and never throws`() {
        val rig = Rig()
        rig.player.release()
        rig.prepare()
        val state: VideoPlayerState = rig.state
        assertTrue(state is VideoPlayerState.Error && state.cause is VideoPlayerReleasedException, "state $state")
        assertEquals(emptyList(), rig.engine.loadedSources)
        // Idempotent release and close.
        rig.player.release()
        rig.player.close()
        assertEquals(1, rig.engine.disposeCount)
    }

    @Test
    fun `use closes the player once and the engine is disposed exactly once`() {
        val rig = Rig()
        rig.player.use { rig.prepare(); rig.player.play() }
        rig.player.release()
        assertEquals(1, rig.engine.disposeCount)
        assertFalse(rig.engine.hasListener, "detached from the engine")
        assertEquals(VideoPlayerState.Idle, rig.state)
    }

    @Test
    fun `a Job in the consumer context is not cancelled by release`() {
        // 04: "coroutineContext hosts the polling coroutine only": a viewModelScope-like Job must survive release().
        val consumer: Job = Job()
        val scheduler = TestCoroutineScheduler()
        val engine = FakeVideoPlaybackEngine()
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = 100L), StandardTestDispatcher(scheduler) + consumer)
        runBlocking { player.prepare(source) }
        player.play()
        scheduler.advanceTimeBy(150L)
        scheduler.runCurrent()
        player.release()
        scheduler.runCurrent()
        assertTrue(consumer.isActive, "release cancelled the consumer's own Job")
        assertEquals(emptyList(), consumer.children.toList())
        consumer.cancel()
    }

    // --- 04: VideoSource, the state helpers ---

    @Test
    fun `a Remote source copies its headers and prints header names only`() {
        val headers: MutableMap<String, String> = mutableMapOf("Authorization" to "Bearer secret-token")
        val remote = VideoSource.Remote("https://example.test/a", headers)
        headers["X-Late"] = "1"
        headers["Authorization"] = "changed"
        assertEquals(mapOf("Authorization" to "Bearer secret-token"), remote.headers)
        val text: String = remote.toString()
        assertTrue("Authorization" in text && "secret-token" !in text && "<redacted>" in text, text)
        assertEquals(VideoSource.Remote("https://example.test/a", mapOf("Authorization" to "Bearer secret-token")), remote)
        assertEquals(
            VideoSource.Remote("https://example.test/a", mapOf("Authorization" to "Bearer secret-token")).hashCode(),
            remote.hashCode(),
        )
        assertNotEquals(VideoSource.Remote("https://example.test/a"), remote)
    }

    @Test
    fun `the position helper is the position for Playing and Paused the duration for Completed and null for Ready`() {
        assertEquals(7L, VideoPlayerState.Playing(10L, 7L).playbackPosition)
        assertEquals(7L, VideoPlayerState.Paused(10L, 7L).playbackPosition)
        assertEquals(10L, VideoPlayerState.Completed(10L).playbackPosition)
        assertEquals(null, VideoPlayerState.Ready(10L).playbackPosition)
        assertEquals(null, VideoPlayerState.Idle.playbackPosition)
        assertEquals(null, VideoPlayerState.Preparing.duration)
        assertEquals(null, VideoPlayerState.Error(IOException()).duration)
    }

    // --- helpers ---

    private fun listenerOf(rig: Rig): io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener? {
        // The player installed itself on the fake; a probe engine would be needed to read it back, so report through the player's
        // own public engine seam: the fake's listener is private, so this goes through a wrapper installed before the player.
        return rig.captured
    }

    private val Rig.captured: io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener?
        get() = ListenerProbe.of(this.engine)

    private fun kotlinx.coroutines.CoroutineScope.launchPrepare(player: VideoPlayer, src: VideoSource): Job =
        launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { player.prepare(src) }

    private fun VideoPlayer.launchedPrepare(src: VideoSource): Job =
        kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined).launchPrepare(this, src)
}

/** Reads the listener a player installed on a [FakeVideoPlaybackEngine] (a private field), by reflection, for a late report. */
internal object ListenerProbe {
    fun of(engine: FakeVideoPlaybackEngine): io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener? {
        val field = FakeVideoPlaybackEngine::class.java.getDeclaredField("listener")
        field.isAccessible = true
        return field.get(engine) as io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener?
    }
}
