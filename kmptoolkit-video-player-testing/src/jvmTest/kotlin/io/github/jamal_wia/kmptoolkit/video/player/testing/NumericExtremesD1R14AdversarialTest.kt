package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.duration
import io.github.jamal_wia.kmptoolkit.video.player.progress
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * D1 round 14, question 2 (numeric and boundary extremes through the public API), each derived from a sentence of
 * `03-guide.md` / `04-api-reference.md`, with the expectation computed independently (BigInteger, a plain range check),
 * never from the implementation:
 *  - speed: "Clamps into minPlaybackSpeed..maxPlaybackSpeed; NaN is ignored. Reaches the platform immediately while Playing";
 *  - volume: "Clamps into 0f..1f; NaN is ignored ... (as 0f while muted)";
 *  - seeking: "seekTo(position + amountMs), saturating instead of overflowing ... floored at 0 and capped at
 *    Long.MAX_VALUE / 1_000_000 ... A negative amountMs moves the other way; Long.MIN_VALUE saturates to the end";
 *  - `progress` is "never NaN", clamped to 0f..1f; `VideoSize` dimensions must be positive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NumericExtremesD1R14AdversarialTest {

    private val source: VideoSource = VideoSource.File("/x.mp4")

    private class Rig(config: VideoPlayerConfig = VideoPlayerConfig(positionUpdateIntervalMs = 100L)) {
        val scheduler: TestCoroutineScheduler = TestCoroutineScheduler()
        val engine: ScriptedEngineD1R11 = ScriptedEngineD1R11()
        val escaped: MutableList<Throwable> = mutableListOf()
        val player: VideoPlayer = createVideoPlayer(
            engine,
            config,
            StandardTestDispatcher(scheduler) + CoroutineExceptionHandler { _, thrown -> escaped += thrown },
        )

        fun prepared(): Rig = apply { runBlocking { player.prepare(VideoSource.File("/x.mp4")) } }
    }

    private val floats: List<Float> = listOf(
        Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -0f, Float.MIN_VALUE, -Float.MIN_VALUE, 1e-30f,
        -1e-30f, Float.MAX_VALUE, -Float.MAX_VALUE, 0.25f, 3f, 3.0000002f, 0.24999999f, 0.5f, 1f, 1.0000001f, 2f, 100f,
    )

    // --- speed ---

    private fun speedMatrix(config: VideoPlayerConfig) {
        val lo: Float = config.minPlaybackSpeed
        val hi: Float = config.maxPlaybackSpeed
        for (value in floats) {
            val rig = Rig(config).prepared()
            val before: Float = rig.player.playbackSpeedFlow.value
            rig.player.play()
            val callsBefore: Int = rig.engine.speeds.size
            rig.player.setPlaybackSpeed(value)
            val flow: Float = rig.player.playbackSpeedFlow.value
            if (value.isNaN()) {
                assertEquals(before, flow, "NaN must be ignored")
                assertEquals(callsBefore, rig.engine.speeds.size, "NaN reached the platform")
            } else {
                val expected: Float = if (value < lo) lo else if (value > hi) hi else value
                assertEquals(expected, flow, "speed $value, config $lo..$hi")
                assertTrue(flow in lo..hi, "flow $flow outside $lo..$hi for $value")
                assertEquals(flow, rig.engine.speeds.last(), "the platform did not get the speed in effect for $value")
            }
            for (heard in rig.engine.speeds) assertTrue(heard.isFinite() && heard in lo..hi, "platform heard $heard, config $lo..$hi")
            // Paused: a new value reaches the platform only on the next play().
            rig.player.pause()
            val calls: Int = rig.engine.speeds.size
            rig.player.setPlaybackSpeed(hi)
            assertEquals(calls, rig.engine.speeds.size, "a paused player pushed a speed")
            rig.player.play()
            assertEquals(hi, rig.engine.speeds.last())
            rig.player.release()
            assertEquals(emptyList(), rig.escaped)
        }
    }

    @Test
    fun `every float is clamped or ignored for speed with the default range`() = speedMatrix(VideoPlayerConfig())

    @Test
    fun `every float is clamped or ignored for speed with a one point range`() = speedMatrix(VideoPlayerConfig(minPlaybackSpeed = 2f, maxPlaybackSpeed = 2f))

    @Test
    fun `every float is clamped or ignored for speed with a range of tiny and huge bounds`() =
        speedMatrix(VideoPlayerConfig(minPlaybackSpeed = Float.MIN_VALUE, maxPlaybackSpeed = Float.MAX_VALUE))

    // --- volume ---

    @Test
    fun `every float is clamped or ignored for volume and the platform hears zero while muted`() {
        for (value in floats) for (muted in listOf(false, true)) {
            val rig = Rig().prepared()
            rig.player.setMuted(muted)
            val before: Float = rig.player.volumeFlow.value
            rig.player.setVolume(value)
            val flow: Float = rig.player.volumeFlow.value
            if (value.isNaN()) {
                assertEquals(before, flow, "NaN must be ignored")
            } else {
                val expected: Float = if (value < 0f) 0f else if (value > 1f) 1f else value
                assertEquals(expected, flow, "volume $value")
                assertTrue(flow in 0f..1f, "flow $flow outside 0..1")
            }
            val heard: Float = rig.engine.volumes.last()
            assertTrue(heard in 0f..1f, "the platform heard $heard for $value")
            assertEquals(if (muted) 0f else flow, heard, "muted=$muted value=$value")
            for (all in rig.engine.volumes) assertTrue(all.isFinite() && all in 0f..1f, "platform heard $all")
            rig.player.setMuted(false)
            assertEquals(flow, rig.engine.volumes.last(), "unmuting restores the volume in effect")
            rig.player.release()
        }
    }

    // --- seek arithmetic against an independent model ---

    private val engineCap: BigInteger = BigInteger.valueOf(Long.MAX_VALUE / 1_000_000L)

    private fun expectedTarget(position: Long, amount: BigInteger, duration: Long): Long {
        val raw: BigInteger = BigInteger.valueOf(position).add(amount)
        val hi: BigInteger = if (duration > 0L) BigInteger.valueOf(duration).min(engineCap) else engineCap
        return raw.max(BigInteger.ZERO).min(hi).toLong()
    }

    @Test
    fun `seekForward and seekBackward match exact arithmetic for extreme durations positions and amounts`() {
        val durations: List<Long> = listOf(0L, 1L, 10_000L, Long.MAX_VALUE / 1_000_000L + 5L, Long.MAX_VALUE)
        val amounts: List<Long> = listOf(
            0L, 1L, -1L, 10_000L, Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1L, Long.MIN_VALUE + 1L, 1L shl 62, -(1L shl 62),
        )
        var cells: Int = 0
        for (duration in durations) {
            val positions: List<Long> = listOf(0L, 1L, duration / 2L, maxOf(0L, duration - 1L), duration, Long.MAX_VALUE / 2L)
            for (position in positions.distinct()) for (amount in amounts) for (forward in listOf(true, false)) {
                val rig = Rig().apply { engine.duration = duration }.prepared()
                rig.player.play()
                rig.player.pause()
                rig.engine.position = position
                val seeks: Int = rig.engine.seekTargets.size
                if (forward) rig.player.seekForward(amount) else rig.player.seekBackward(amount)
                val signed: BigInteger = BigInteger.valueOf(amount).let { if (forward) it else it.negate() }
                val expected: Long = expectedTarget(position, signed, duration)
                val label = "duration=$duration position=$position amount=$amount forward=$forward"
                assertEquals(seeks + 1, rig.engine.seekTargets.size, "one engine seek: $label")
                assertEquals(expected, rig.engine.seekTargets.last(), "engine target: $label")
                assertEquals(expected, rig.player.playbackPositionFlow.value, "published position: $label")
                val state: VideoPlayerState = rig.player.stateFlow.value
                assertTrue(state is VideoPlayerState.Paused, "state $state: $label")
                assertEquals(expected, state.currentPosition, "state position: $label")
                for (target in rig.engine.seekTargets) {
                    assertTrue(target in 0L..(Long.MAX_VALUE / 1_000_000L), "target $target outside the engine range: $label")
                    if (duration > 0L) assertTrue(target <= duration, "target $target past duration: $label")
                }
                assertTrue(state.progress in 0f..1f && !state.progress.isNaN(), "progress ${state.progress}: $label")
                rig.player.release()
                cells++
            }
        }
        assertTrue(cells > 400, "only $cells cells ran")
    }

    @Test
    fun `an absolute seek clamps every long to the engine range`() {
        for (duration in listOf(0L, 1L, 7_000L, Long.MAX_VALUE)) for (target in listOf(Long.MIN_VALUE, -1L, 0L, 1L, 6_999L, 7_000L, 7_001L, Long.MAX_VALUE)) {
            val rig = Rig().apply { engine.duration = duration }.prepared()
            rig.player.seekTo(target)
            val hi: BigInteger = if (duration > 0L) BigInteger.valueOf(duration).min(engineCap) else BigInteger.ZERO
            val expected: Long = BigInteger.valueOf(target).max(BigInteger.ZERO).min(hi).toLong()
            assertEquals(expected, rig.engine.seekTargets.last(), "duration=$duration target=$target")
            assertEquals(expected, rig.player.playbackPositionFlow.value, "duration=$duration target=$target")
            assertTrue(rig.player.stateFlow.value is VideoPlayerState.Ready, "a Ready player stays Ready")
            rig.player.release()
        }
    }

    // --- values the API hands out ---

    @Test
    fun `progress is never NaN and stays in range for extreme states`() {
        val extremes: List<Long> = listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)
        for (d in extremes) for (p in extremes) {
            for (state in listOf(
                VideoPlayerState.Playing(d, p), VideoPlayerState.Paused(d, p), VideoPlayerState.Completed(d),
                VideoPlayerState.Ready(d),
            )) {
                val progress: Float = state.progress
                assertTrue(!progress.isNaN() && progress in 0f..1f, "progress $progress for $state")
            }
        }
        assertEquals(1f, VideoPlayerState.Playing(Long.MAX_VALUE, Long.MAX_VALUE).progress)
        assertEquals(0f, VideoPlayerState.Playing(Long.MAX_VALUE, 0L).progress)
        assertEquals(0f, VideoPlayerState.Ready(Long.MAX_VALUE).progress)
    }

    @Test
    fun `a picture size needs both dimensions positive and an extreme one has a finite aspect ratio`() {
        for ((w, h) in listOf(0 to 1, 1 to 0, -1 to 1, 1 to -1, 0 to 0, Int.MIN_VALUE to Int.MIN_VALUE, Int.MIN_VALUE to 1)) {
            assertFailsWith<IllegalArgumentException>("$w x $h") { VideoSize(w, h) }
        }
        for ((w, h) in listOf(Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE, Int.MAX_VALUE to Int.MAX_VALUE, 1 to 1)) {
            val ratio: Float = VideoSize(w, h).aspectRatio
            assertTrue(ratio.isFinite() && ratio > 0f, "aspect ratio $ratio of $w x $h")
        }
    }

    @Test
    fun `a config with a non finite or non positive number is refused and the largest interval works`() {
        for (bad in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException>("interval $bad") { VideoPlayerConfig(positionUpdateIntervalMs = bad) }
        }
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f, -0f, Float.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("min $bad") { VideoPlayerConfig(minPlaybackSpeed = bad) }
        }
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0.1f)) {
            assertFailsWith<IllegalArgumentException>("max $bad") { VideoPlayerConfig(minPlaybackSpeed = 0.25f, maxPlaybackSpeed = bad) }
        }
        val rig = Rig(VideoPlayerConfig(positionUpdateIntervalMs = Long.MAX_VALUE)).prepared()
        rig.player.play()
        rig.scheduler.advanceTimeBy(1_000_000L)
        rig.scheduler.runCurrent()
        assertTrue(rig.player.stateFlow.value is VideoPlayerState.Playing)
        rig.player.release()
        assertEquals(emptyList(), rig.escaped)
    }

    @Test
    fun `a duration of Long MAX plays caps the shown position and a play from the end starts over`() {
        val rig = Rig().apply { engine.duration = Long.MAX_VALUE }.prepared()
        assertEquals(Long.MAX_VALUE, rig.player.stateFlow.value.duration)
        rig.player.play()
        rig.engine.position = Long.MAX_VALUE
        rig.scheduler.advanceTimeBy(150L)
        rig.scheduler.runCurrent()
        val playing = rig.player.stateFlow.value as VideoPlayerState.Playing
        assertEquals(Long.MAX_VALUE, playing.currentPosition)
        assertEquals(1f, playing.progress)
        rig.player.pause()
        rig.player.play()
        // The playhead sat on the end: the documented rewind.
        assertEquals(0L, rig.engine.seekTargets.last(), "log ${rig.engine.log}")
        rig.player.release()
        assertEquals(emptyList(), rig.escaped)
        if (rig.engine.callsAfterDispose().isNotEmpty()) fail("calls after dispose")
    }
}
