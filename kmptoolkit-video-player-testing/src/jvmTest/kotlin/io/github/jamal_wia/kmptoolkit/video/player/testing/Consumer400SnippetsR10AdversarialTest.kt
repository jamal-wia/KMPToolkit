package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.DEFAULT_SEEK_AMOUNT_MS
import io.github.jamal_wia.kmptoolkit.video.player.RemoteFormat
import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.duration
import io.github.jamal_wia.kmptoolkit.video.player.isPlayable
import io.github.jamal_wia.kmptoolkit.video.player.isPlaying
import io.github.jamal_wia.kmptoolkit.video.player.playbackPosition
import io.github.jamal_wia.kmptoolkit.video.player.progress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 10 (breaker bt), upgrade compatibility: consumer code written against the 4.0.0 public API (the docs and the
 * api/ dumps of the 4.0.0 tag, which the current dumps equal) and the behaviour 4.0.0 documented for it. This file
 * compiles unchanged against the 4.0.0 sources (it was run there too) and must pass on the current ones.
 *
 * The engine below is what a 4.0.0 consumer wrote from the SPI table: only the members that were abstract, no
 * `dispose` override, tolerant start/pause, a `load` that throws to fail.
 */
class Consumer400SnippetsR10AdversarialTest {

    private class ConsumerEngine : VideoPlaybackEngine {
        var sink: VideoPlaybackEngineListener? = null
        var position: Long = 0L
        var length: Long = 8_000L
        var running: Boolean = false
        var rate: Float = 1f
        var level: Float = 1f
        var loop: Boolean = false
        var loads: Int = 0
        var releases: Int = 0
        var failLoad: Boolean = false
        val calls: MutableList<String> = mutableListOf()

        override fun setListener(listener: VideoPlaybackEngineListener?) { this.sink = listener }
        override suspend fun load(source: VideoSource) {
            loads++
            calls += "load"
            if (failLoad) throw java.io.IOException("cannot open ${source::class.simpleName}")
            position = 0L
        }
        override fun start() { calls += "start"; running = true }
        override fun pause() { calls += "pause"; running = false }
        override fun seekTo(positionMs: Long) { calls += "seekTo($positionMs)"; position = positionMs }
        override fun setSpeed(speed: Float) { calls += "setSpeed($speed)"; this.rate = speed }
        override fun setVolume(volume: Float) { calls += "setVolume($volume)"; this.level = volume }
        override fun setLooping(looping: Boolean) { calls += "setLooping($looping)"; this.loop = looping }
        override fun durationMs(): Long = length
        override fun positionMs(): Long = position
        override fun bufferedPositionMs(): Long = length
        override fun release() { calls += "release"; releases++; running = false; position = 0L }
    }

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { withTimeout(5_000L) { block() } }

    @Test
    fun `a 4_0_0 engine drives the documented lifecycle through createVideoPlayer`() {
        val engine = ConsumerEngine()
        val player: VideoPlayer = createVideoPlayer(
            engine,
            VideoPlayerConfig(positionUpdateIntervalMs = 20L, minPlaybackSpeed = 0.5f, maxPlaybackSpeed = 2f),
            Dispatchers.Default,
        )
        assertEquals(0.5f..2f, player.playbackSpeedRange)
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        blocking { player.prepare(VideoSource.File("/tmp/x.mp4")) }
        assertEquals(VideoPlayerState.Ready(8_000L), player.stateFlow.value)
        player.setPlaybackSpeed(10f)
        assertEquals(2f, player.playbackSpeedFlow.value)
        player.setVolume(0.4f)
        player.setMuted(true)
        assertEquals(0f, engine.level)
        player.setMuted(false)
        assertEquals(0.4f, engine.level)
        player.play()
        assertTrue(player.stateFlow.value.isPlaying)
        assertEquals(2f, engine.rate)
        player.seekForward()
        assertEquals(DEFAULT_SEEK_AMOUNT_MS.coerceAtMost(8_000L), engine.position)
        player.pause()
        val paused: VideoPlayerState = player.stateFlow.value
        assertEquals(VideoPlayerState.Paused(8_000L, DEFAULT_SEEK_AMOUNT_MS.coerceAtMost(8_000L)), paused)
        assertEquals(8_000L, paused.playbackPosition)
        assertEquals(1f, paused.progress)
        player.replay()
        player.setRepeatMode(RepeatMode.One)
        assertTrue(engine.loop)
        player.setRepeatMode(RepeatMode.Off)
        engine.running = false
        engine.position = engine.length
        engine.sink?.onCompleted()
        assertEquals(VideoPlayerState.Completed(8_000L), player.stateFlow.value)
        assertEquals(8_000L, player.stateFlow.value.duration)
        assertTrue(player.stateFlow.value.isPlayable)
        player.stop()
        assertEquals(VideoPlayerState.Ready(8_000L), player.stateFlow.value)
        player.unload()
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertEquals(1, engine.releases)
        player.use { }
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        player.release()
    }

    @Test
    fun `a 4_0_0 load failure arrives as Error with the engine's own throwable and the next prepare recovers`() {
        val engine = ConsumerEngine()
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), Dispatchers.Default)
        engine.failLoad = true
        blocking { player.prepare(VideoSource.Asset("clips/a.mp4")) }
        val error: VideoPlayerState = player.stateFlow.value
        assertTrue(error is VideoPlayerState.Error && error.cause is java.io.IOException, "$error")
        assertNull(error.duration)
        assertFalse(error.isPlayable)
        engine.failLoad = false
        blocking { player.prepare(VideoSource.Remote("https://example.com/v.m3u8", mapOf("Authorization" to "Bearer s3cret"), RemoteFormat.Hls)) }
        assertEquals(VideoPlayerState.Ready(8_000L), player.stateFlow.value)
        assertTrue(engine.releases >= 1, "the failed source must be freed before the next load")
        player.release()
        player.release()
    }

    @Test
    fun `4_0_0 value types keep their documented equality printing and validation`() {
        val a = VideoSource.Remote("https://h/p", mapOf("Authorization" to "Bearer s3cret"), RemoteFormat.Progressive)
        val b = VideoSource.Remote("https://h/p", mapOf("Authorization" to "Bearer s3cret"), RemoteFormat.Progressive)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a.toString().contains("s3cret"), a.toString())
        assertTrue(a.toString().contains("Authorization"), a.toString())
        assertEquals(VideoSource.File("/x"), VideoSource.File("/x"))
        assertEquals(VideoSource.Asset("a.mp4"), VideoSource.Asset("a.mp4"))
        assertEquals(2f, VideoSize(4, 2).aspectRatio)
        assertFailsWith<IllegalArgumentException> { VideoSize(0, 1) }
        assertFailsWith<IllegalArgumentException> { VideoPlayerConfig(positionUpdateIntervalMs = 0L) }
        assertFailsWith<IllegalArgumentException> { VideoPlayerConfig(minPlaybackSpeed = 2f, maxPlaybackSpeed = 1f) }
        assertEquals(250L, VideoPlayerConfig.DEFAULT_POSITION_UPDATE_INTERVAL_MS)
        assertEquals(0.25f, VideoPlayerConfig.DEFAULT_MIN_PLAYBACK_SPEED)
        assertEquals(3.0f, VideoPlayerConfig.DEFAULT_MAX_PLAYBACK_SPEED)
        assertEquals(10_000L, DEFAULT_SEEK_AMOUNT_MS)
        assertEquals(0f, VideoPlayerState.Playing(0L, 5L).progress)
        assertEquals(0.5f, VideoPlayerState.Playing(10L, 5L).progress)
        val exhaustive: String = when (VideoPlayerState.Idle as VideoPlayerState) {
            VideoPlayerState.Idle -> "idle"
            VideoPlayerState.Preparing -> "preparing"
            is VideoPlayerState.Ready -> "ready"
            is VideoPlayerState.Playing -> "playing"
            is VideoPlayerState.Paused -> "paused"
            is VideoPlayerState.Completed -> "completed"
            is VideoPlayerState.Error -> "error"
        }
        assertEquals("idle", exhaustive)
    }

    @Test
    fun `the fake of 4_0_0 keeps its scripting surface`() {
        val fake = FakeVideoPlaybackEngine(durationMs = 3_000L)
        val player: VideoPlayer = createVideoPlayer(fake, VideoPlayerConfig(), Dispatchers.Default)
        fake.videoSizeOnLoad = VideoSize(16, 9)
        fake.loadDelayMs = 5L
        blocking { player.prepare(VideoSource.Asset("a.mp4")) }
        assertEquals(VideoSize(16, 9), player.videoSizeFlow.value)
        player.play()
        fake.reportBuffering(true)
        assertTrue(player.isBufferingFlow.value)
        fake.reportBuffering(false)
        fake.advancePositionBy(1_000L)
        fake.completePlayback()
        assertEquals(VideoPlayerState.Completed(3_000L), player.stateFlow.value)
        assertEquals(1, fake.loadedSources.size)
        assertEquals(0, fake.disposeCount)
        player.release()
        assertEquals(1, fake.disposeCount)
        assertTrue(fake.releaseCount >= 1)
    }
}
