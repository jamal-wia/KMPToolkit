package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.SOURCE_DURATION_MS
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.newEngine
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.runMainLooperUntil
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.runSuspending
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [VideoPlaybackEngine.seekTo] says a target past the real end can reach the engine (the player
 * passes `seekForward` through while the duration is unknown, capped at `Long.MAX_VALUE / 1000`) and
 * that the engine clamps it to the end or ignores it. A target that lands on the start instead is
 * neither: it would turn "skip ahead" into "start over". Driven straight at the engine, so the
 * player's own clamp is out of the way. The end is read from the ExoPlayer after the load, not
 * hard-coded. ExoPlayer clamps a period position to `durationUs - 1` (`Timeline.getPeriodPositionUs`),
 * which reads as `end - 1` ms, so "the end" is `end - 1..end`.
 */
@RunWith(AndroidJUnit4::class)
class Media3EngineSeekBeyondEndTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var exoPlayer: ExoPlayer? = null
    private val engine: Media3VideoEngine = newEngine(context, created = { exoPlayer = it })

    @After
    fun tearDown() {
        engine.release()
        engine.dispose()
        ShadowLooper.idleMainLooper()
    }

    private fun endAndSeekedPositionAfter(target: Long): Pair<Long, Long> {
        runSuspending { engine.load(VideoSource.Asset("clips/intro.mp4")) }.getOrThrow()
        val end: Long = requireNotNull(exoPlayer).duration
        engine.seekTo(target)
        runMainLooperUntil { exoPlayer?.playbackState != Player.STATE_BUFFERING }
        ShadowLooper.idleMainLooper()
        return end to requireNotNull(exoPlayer).currentPosition
    }

    @Test
    fun `a target far past the end lands on the end`() {
        val (end: Long, position: Long) = endAndSeekedPositionAfter(SOURCE_DURATION_MS * 1_000_000L)
        assertTrue(position in (end - 1)..end, "landed on $position, end is $end")
    }

    @Test
    fun `the largest target the contract allows lands on the end, not on the start`() {
        val (end: Long, position: Long) = endAndSeekedPositionAfter(Long.MAX_VALUE / 1_000_000L)
        assertTrue(position in (end - 1)..end, "landed on $position, end is $end")
    }
}
