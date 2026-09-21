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
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The shared state machine over the real Media3 engine: the path an Android app takes, end to end,
 * minus a decoder. Also covers `media3PlayerOrNull()`, the hook the Compose surface attaches by.
 */
@OptIn(ToolkitInternalApi::class)
@RunWith(AndroidJUnit4::class)
class Media3VideoPlayerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source: VideoSource = VideoSource.Remote("https://example.test/lesson.mp4")

    private var exoPlayer: ExoPlayer? = null
    private val player: VideoPlayer = createVideoPlayer(
        engine = newEngine(context, created = { exoPlayer = it }),
        coroutineContext = Dispatchers.Unconfined,
    )

    @After
    fun tearDown() {
        player.release()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `prepare play and the end of the source walk the documented states`() {
        Media3TestSupport.attachSurface(requireNotNull(player.media3PlayerOrNull()))
        runSuspending { player.prepare(source) }.getOrThrow()
        assertEquals(VideoPlayerState.Ready(SOURCE_DURATION_MS), player.stateFlow.value)

        player.play()
        assertIs<VideoPlayerState.Playing>(player.stateFlow.value)

        runMainLooperUntil { player.stateFlow.value is VideoPlayerState.Completed }
        assertEquals(VideoPlayerState.Completed(SOURCE_DURATION_MS), player.stateFlow.value)
        assertEquals(VideoSize(1_500, 1_000), player.videoSizeFlow.value)
    }

    @Test
    fun `replay after the end plays the source again`() {
        runSuspending { player.prepare(source) }.getOrThrow()
        player.play()
        runMainLooperUntil { player.stateFlow.value is VideoPlayerState.Completed }

        player.replay()
        ShadowLooper.idleMainLooper()

        assertIs<VideoPlayerState.Playing>(player.stateFlow.value)
        assertTrue(requireNotNull(exoPlayer).playWhenReady)
    }

    @Test
    fun `a seek after the end leaves the player paused and the ExoPlayer not playing`() {
        runSuspending { player.prepare(source) }.getOrThrow()
        player.play()
        runMainLooperUntil { player.stateFlow.value is VideoPlayerState.Completed }

        player.seekTo(1_000L)
        repeat(SETTLE_PASSES) { ShadowLooper.idleMainLooper() }

        assertEquals(VideoPlayerState.Paused(SOURCE_DURATION_MS, 1_000L), player.stateFlow.value)
        val exo: ExoPlayer = requireNotNull(exoPlayer)
        assertFalse(exo.playWhenReady)
        assertFalse(exo.isPlaying)
        assertEquals(1_000L, exo.currentPosition)
    }

    @Test
    fun `repeat mode one never completes`() {
        player.setRepeatMode(RepeatMode.One)
        runSuspending { player.prepare(source) }.getOrThrow()
        player.play()
        androidx.media3.test.utils.robolectric.TestPlayerRunHelper.runUntilPositionDiscontinuity(
            requireNotNull(exoPlayer),
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        )
        ShadowLooper.idleMainLooper()

        assertIs<VideoPlayerState.Playing>(player.stateFlow.value)
    }

    @Test
    fun `mute reaches the ExoPlayer as zero volume and unmute restores it`() {
        runSuspending { player.prepare(source) }.getOrThrow()
        player.setVolume(0.6f)
        player.setMuted(true)
        assertEquals(0f, requireNotNull(exoPlayer).volume)

        player.setMuted(false)
        assertEquals(0.6f, requireNotNull(exoPlayer).volume)
    }

    @Test
    fun `the surface player is the same instance across unload and prepare`() {
        val attached: Player? = player.media3PlayerOrNull()
        runSuspending { player.prepare(source) }.getOrThrow()
        player.unload()
        ShadowLooper.idleMainLooper()
        runSuspending { player.prepare(source) }.getOrThrow()

        assertSame(attached, player.media3PlayerOrNull())
        assertSame<Player?>(exoPlayer, attached)
    }

    @Test
    fun `the surface player is gone after release`() {
        runSuspending { player.prepare(source) }.getOrThrow()
        val attached: ExoPlayer = requireNotNull(exoPlayer)
        player.release()
        ShadowLooper.idleMainLooper()

        assertNull(player.media3PlayerOrNull())
        assertTrue(attached.isReleased)
    }

    @Test
    fun `a player over another engine has no Media3 player`() {
        val other: VideoPlayer = createVideoPlayer(engine = NoopEngine)

        assertNull(other.media3PlayerOrNull())
        other.release()
    }

    private object NoopEngine : VideoPlaybackEngine {
        override fun setListener(listener: VideoPlaybackEngineListener?) = Unit
        override suspend fun load(source: VideoSource) = Unit
        override fun start() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun setVolume(volume: Float) = Unit
        override fun setLooping(looping: Boolean) = Unit
        override fun durationMs(): Long = 0L
        override fun positionMs(): Long = 0L
        override fun bufferedPositionMs(): Long = 0L
        override fun release() = Unit
    }

    private companion object {
        /** Enough looper passes for a seek to settle and for playback to have started, had it been going to. */
        const val SETTLE_PASSES: Int = 5
    }
}
