package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.fakeSource
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.newEngine
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.runMainLooperUntil
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.runSuspending
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import kotlin.test.assertEquals

/**
 * Round 6 (platform differential): one scenario script, run over the real Media3 engine here and over the
 * `FakeVideoPlaybackEngine` in `kmptoolkit-video-player-testing`
 * (`VideoPlayerPlatformDifferentialR6AdversarialTest`). Both build the same trace and compare it with the same
 * literal, so a difference between the two engines in anything the player publishes is a red test in one of
 * them. A position read while ExoPlayer's test clock is running is not reproducible, so a step that leaves
 * the playhead moving records only that it is inside `0..duration`.
 */
@RunWith(AndroidJUnit4::class)
class Media3PlatformDifferentialR6AdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val trace: MutableList<String> = mutableListOf()
    private val sourceA: VideoSource = VideoSource.Remote("https://example.test/a.mp4")
    private val sourceShort: VideoSource = VideoSource.Remote("https://example.test/short.mp4")
    private val sourceBroken: VideoSource = VideoSource.Remote("https://example.test/broken.mp4")
    private val brokenCause: IllegalStateException = IllegalStateException("the source would not build")

    private val player: VideoPlayer = createVideoPlayer(
        engine = newEngine(
            context,
            mediaSources = { _: Context, source: VideoSource ->
                when (source) {
                    sourceShort -> fakeSource(durationMs = SHORT_MS)
                    sourceBroken -> throw brokenCause
                    else -> fakeSource(durationMs = LONG_MS)
                }
            },
        ),
        config = VideoPlayerConfig(positionUpdateIntervalMs = ONE_HOUR_MS),
        coroutineContext = Dispatchers.Unconfined,
    )

    @After
    fun tearDown() {
        player.release()
        ShadowLooper.idleMainLooper()
    }

    /**
     * Lets the looper run, and, unless the player is playing, until a seek's brief buffering is over. ExoPlayer's
     * test clock moves while the looper is pumped, so pumping a playing player would play the source to its end.
     */
    private fun settle() {
        repeat(10) { ShadowLooper.idleMainLooper() }
        if (player.stateFlow.value !is VideoPlayerState.Playing) runMainLooperUntil { !player.isBufferingFlow.value }
    }

    /**
     * One line of the trace. [showBuffering] is off right after a seek that nothing has waited out: Media3 buffers
     * briefly for a seek (05-platform-notes, "a seek buffers briefly too") and the fake never buffers.
     */
    private fun note(label: String, exactPosition: Boolean = true, showBuffering: Boolean = true) {
        val state: VideoPlayerState = player.stateFlow.value
        val position: String = if (exactPosition) {
            player.playbackPositionFlow.value.toString()
        } else {
            val duration: Long = state.duration ?: 0L
            if (player.playbackPositionFlow.value in 0L..duration) "in-range" else "OUT-OF-RANGE(${player.playbackPositionFlow.value} of $duration)"
        }
        val shown: String = if (exactPosition) state.toString() else state::class.simpleName.orEmpty()
        trace += "$label | $shown | pos=$position | buffering=${if (showBuffering) player.isBufferingFlow.value.toString() else "n/a"} | speed=${player.playbackSpeedFlow.value}"
    }

    private fun reachTheEnd() {
        runMainLooperUntil { player.stateFlow.value is VideoPlayerState.Completed }
        settle()
    }

    @Test
    fun `the scenario script produces the trace the fake engine produces`() {
        runSuspending { player.prepare(sourceA) }.getOrThrow()
        note("prepare")

        player.seekTo(-1L)
        settle()
        note("seekTo(-1) in Ready")
        player.seekTo(Long.MAX_VALUE)
        settle()
        note("seekTo(MAX) in Ready")
        player.seekTo(2_000L)
        settle()
        note("seekTo(2000) in Ready")
        player.seekBackward(Long.MIN_VALUE)
        settle()
        note("seekBackward(MIN) in Ready")
        player.seekForward(Long.MAX_VALUE)
        settle()
        note("seekForward(MAX) in Ready")

        player.play()
        note("play from the end of a Ready player", exactPosition = false)

        for (speed: Float in listOf(Float.NaN, 0f, -3f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1.5f, 100f)) {
            player.setPlaybackSpeed(speed)
            note("setPlaybackSpeed($speed) while Playing", exactPosition = false)
        }

        player.pause()
        settle()
        note("pause", exactPosition = false)
        player.seekTo(2_000L)
        settle()
        note("seekTo(2000) in Paused")
        player.seekForward(10_000L)
        settle()
        note("seekForward(10000) in Paused")
        player.seekBackward(Long.MAX_VALUE)
        settle()
        note("seekBackward(MAX) in Paused")
        player.seekBackward(Long.MIN_VALUE)
        settle()
        note("seekBackward(MIN) in Paused")
        player.seekTo(0L)
        player.pause()
        note("pause while Paused", showBuffering = false)

        runSuspending { player.prepare(sourceShort) }.getOrThrow()
        note("prepare of a shorter source")
        player.play()
        reachTheEnd()
        note("end of media")
        player.pause()
        settle()
        note("pause in Completed")
        player.seekForward(1_000L)
        settle()
        note("seekForward(1000) in Completed")
        player.stop()
        settle()
        note("stop")
        player.play()
        reachTheEnd()
        player.seekTo(1_000L)
        settle()
        note("seekTo(1000) in Completed")
        player.replay()
        note("replay", exactPosition = false, showBuffering = false)
        player.stop()
        settle()
        note("stop in Playing")

        player.setPlaybackSpeed(1f)
        player.play()
        runSuspending { player.prepare(sourceA) }.getOrThrow()
        note("source swap while Playing")
        player.unload()
        settle()
        note("unload")
        player.play()
        player.pause()
        player.seekTo(1_000L)
        player.seekForward(1_000L)
        player.stop()
        player.replay()
        settle()
        note("every transport call in Idle")

        runSuspending { player.prepare(sourceBroken) }.getOrThrow()
        val failed: VideoPlayerState = player.stateFlow.value
        trace += "failing prepare | ${(failed as? VideoPlayerState.Error)?.cause?.message}"
        runSuspending { player.prepare(sourceA) }.getOrThrow()
        note("prepare after a failure")
        player.release()
        settle()
        note("release")
        player.play()
        player.seekTo(500L)
        runSuspending { player.prepare(sourceA) }.getOrThrow()
        val refused: VideoPlayerState = player.stateFlow.value
        trace += "prepare after release | ${(refused as? VideoPlayerState.Error)?.cause is VideoPlayerReleasedException}"
        assertEquals(EXPECTED_TRACE, trace, "Media3 trace differs from the fake engine's")
    }

    private companion object {
        const val LONG_MS: Long = 360_000_000L
        const val SHORT_MS: Long = 2_000L
        const val ONE_HOUR_MS: Long = 3_600_000L

        val EXPECTED_TRACE: List<String> = listOf(
            "prepare | Ready(duration=360000000) | pos=0 | buffering=false | speed=1.0",
            "seekTo(-1) in Ready | Ready(duration=360000000) | pos=0 | buffering=false | speed=1.0",
            "seekTo(MAX) in Ready | Ready(duration=360000000) | pos=360000000 | buffering=false | speed=1.0",
            "seekTo(2000) in Ready | Ready(duration=360000000) | pos=2000 | buffering=false | speed=1.0",
            "seekBackward(MIN) in Ready | Ready(duration=360000000) | pos=360000000 | buffering=false | speed=1.0",
            "seekForward(MAX) in Ready | Ready(duration=360000000) | pos=360000000 | buffering=false | speed=1.0",
            "play from the end of a Ready player | Playing | pos=in-range | buffering=false | speed=1.0",
            "setPlaybackSpeed(NaN) while Playing | Playing | pos=in-range | buffering=false | speed=1.0",
            "setPlaybackSpeed(0.0) while Playing | Playing | pos=in-range | buffering=false | speed=0.25",
            "setPlaybackSpeed(-3.0) while Playing | Playing | pos=in-range | buffering=false | speed=0.25",
            "setPlaybackSpeed(Infinity) while Playing | Playing | pos=in-range | buffering=false | speed=3.0",
            "setPlaybackSpeed(-Infinity) while Playing | Playing | pos=in-range | buffering=false | speed=0.25",
            "setPlaybackSpeed(1.5) while Playing | Playing | pos=in-range | buffering=false | speed=1.5",
            "setPlaybackSpeed(100.0) while Playing | Playing | pos=in-range | buffering=false | speed=3.0",
            "pause | Paused | pos=in-range | buffering=false | speed=3.0",
            "seekTo(2000) in Paused | Paused(duration=360000000, currentPosition=2000) | pos=2000 | buffering=false | speed=3.0",
            "seekForward(10000) in Paused | Paused(duration=360000000, currentPosition=12000) | pos=12000 | buffering=false | speed=3.0",
            "seekBackward(MAX) in Paused | Paused(duration=360000000, currentPosition=0) | pos=0 | buffering=false | speed=3.0",
            "seekBackward(MIN) in Paused | Paused(duration=360000000, currentPosition=360000000) | pos=360000000 | buffering=false | speed=3.0",
            "pause while Paused | Paused(duration=360000000, currentPosition=0) | pos=0 | buffering=n/a | speed=3.0",
            "prepare of a shorter source | Ready(duration=2000) | pos=0 | buffering=false | speed=3.0",
            "end of media | Completed(duration=2000) | pos=2000 | buffering=false | speed=3.0",
            "pause in Completed | Completed(duration=2000) | pos=2000 | buffering=false | speed=3.0",
            "seekForward(1000) in Completed | Paused(duration=2000, currentPosition=2000) | pos=2000 | buffering=false | speed=3.0",
            "stop | Ready(duration=2000) | pos=0 | buffering=false | speed=3.0",
            "seekTo(1000) in Completed | Paused(duration=2000, currentPosition=1000) | pos=1000 | buffering=false | speed=3.0",
            "replay | Playing | pos=in-range | buffering=n/a | speed=3.0",
            "stop in Playing | Ready(duration=2000) | pos=0 | buffering=false | speed=3.0",
            "source swap while Playing | Ready(duration=360000000) | pos=0 | buffering=false | speed=1.0",
            "unload | Idle | pos=0 | buffering=false | speed=1.0",
            "every transport call in Idle | Idle | pos=0 | buffering=false | speed=1.0",
            "failing prepare | the source would not build",
            "prepare after a failure | Ready(duration=360000000) | pos=0 | buffering=false | speed=1.0",
            "release | Idle | pos=0 | buffering=false | speed=1.0",
            "prepare after release | true",
        )
    }
}
