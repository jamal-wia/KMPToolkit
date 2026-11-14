package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.SOURCE_DURATION_MS
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.newEngine
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.runSuspending
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper

/**
 * D1 round 14, question 2 on the REAL Media3 engine (Robolectric, test ExoPlayer): what ExoPlayer is actually told for
 * every extreme number the public API accepts. The sentences are those of `04-api-reference.md`: speed "clamps into
 * minPlaybackSpeed..maxPlaybackSpeed; NaN is ignored", volume "clamps into 0f..1f; NaN is ignored", seeking "clamped to
 * 0..duration", amounts "saturating instead of overflowing" and "a negative amountMs moves the other way". The expected
 * seek target is computed with BigInteger, not read off the implementation.
 */
@RunWith(AndroidJUnit4::class)
class Media3NumericExtremesD1R14AdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source: VideoSource = VideoSource.Remote("https://example.test/lesson.mp4")
    private var exo: ExoPlayer? = null
    private val player: VideoPlayer = createVideoPlayer(
        engine = newEngine(context, created = { exo = it }),
        coroutineContext = Dispatchers.Unconfined,
    )

    @After
    fun tearDown() {
        player.release()
        ShadowLooper.idleMainLooper()
    }

    private fun settle() = repeat(10) { ShadowLooper.idleMainLooper() }

    private val floats: List<Float> = listOf(
        Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -0f, Float.MIN_VALUE, 1e-30f, -1e-30f,
        Float.MAX_VALUE, -Float.MAX_VALUE, 0.25f, 3f, 3.0000002f, 0.24999999f, 0.5f, 1f, 1.0000001f, 2f,
    )

    @Test
    fun `ExoPlayer is told a speed inside the range and a volume inside zero to one for every float`() {
        runSuspending { player.prepare(source) }.getOrThrow()
        player.play()
        settle()
        val exoPlayer: ExoPlayer = checkNotNull(exo)
        for (value in floats) {
            player.setPlaybackSpeed(value)
            player.setVolume(value)
            settle()
            val speed: Float = exoPlayer.playbackParameters.speed
            assertTrue(speed.isFinite() && speed in 0.25f..3.0f, "ExoPlayer speed $speed after setPlaybackSpeed($value)")
            assertEquals(player.playbackSpeedFlow.value, speed, "ExoPlayer speed against the flow after $value")
            val volume: Float = exoPlayer.volume
            assertTrue(volume in 0f..1f, "ExoPlayer volume $volume after setVolume($value)")
            // Numeric equality: a signed zero of the flow is a separate finding (r14-F1), not what this line is about.
            assertTrue(player.volumeFlow.value == volume, "ExoPlayer volume $volume against the flow ${player.volumeFlow.value} after $value")
        }
        player.setMuted(true)
        player.setVolume(0.8f)
        settle()
        assertEquals(0f, exoPlayer.volume, "muted: the platform hears zero")
        player.setMuted(false)
        settle()
        assertEquals(0.8f, exoPlayer.volume, "unmuting restores the volume")
    }

    @Test
    fun `seeking by every extreme amount lands where exact arithmetic puts it and never moves ExoPlayer outside the source`() {
        runSuspending { player.prepare(source) }.getOrThrow()
        player.play()
        settle()
        player.pause()
        val exoPlayer: ExoPlayer = checkNotNull(exo)
        val amounts: List<Long> = listOf(
            0L, 1L, -1L, 1_000L, Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1L, Long.MIN_VALUE + 1L, 1L shl 62, -(1L shl 62),
        )
        var cells: Int = 0
        for (start in listOf(0L, 1L, 2_500L, SOURCE_DURATION_MS - 1L)) for (amount in amounts) for (forward in listOf(true, false)) {
            player.seekTo(start)
            settle()
            val before: Long = player.playbackPositionFlow.value
            assertEquals(start, before, "the setup seek to $start")
            if (forward) player.seekForward(amount) else player.seekBackward(amount)
            settle()
            val signed: BigInteger = BigInteger.valueOf(amount).let { if (forward) it else it.negate() }
            val expected: Long = BigInteger.valueOf(start).add(signed).max(BigInteger.ZERO).min(BigInteger.valueOf(SOURCE_DURATION_MS)).toLong()
            val label = "start=$start amount=$amount forward=$forward"
            assertEquals(expected, player.playbackPositionFlow.value, "published position: $label")
            assertTrue(exoPlayer.currentPosition in 0L..SOURCE_DURATION_MS, "ExoPlayer at ${exoPlayer.currentPosition}: $label")
            assertTrue(
                player.stateFlow.value is VideoPlayerState.Paused || player.stateFlow.value is VideoPlayerState.Completed,
                "state ${player.stateFlow.value}: $label",
            )
            cells++
        }
        assertTrue(cells > 70, "only $cells cells")
        // Playback can go on after all of that.
        player.seekTo(0L)
        settle()
        player.play()
        settle()
        assertTrue(player.stateFlow.value is VideoPlayerState.Playing, "state ${player.stateFlow.value}")
    }
}
