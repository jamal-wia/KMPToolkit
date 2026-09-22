package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.SOURCE_DURATION_MS
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.launchOnMain
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.newEngine
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.offLooper
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.runMainLooperUntil
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.runSuspending
import kotlinx.coroutines.Job
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
 * The Media3 engine against the SPI contract in [VideoPlaybackEngine]'s KDoc, with a real ExoPlayer
 * (Media3's test build of it) on Robolectric's main looper.
 */
@RunWith(AndroidJUnit4::class)
class Media3VideoEngineTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source: VideoSource = VideoSource.Asset("clips/intro.mp4")
    private val listener = RecordingEngineListener()

    private var exoPlayer: ExoPlayer? = null
    private val engine: Media3VideoEngine = newEngine(context, created = { exoPlayer = it }).also {
        it.setListener(listener)
    }

    private val player: ExoPlayer get() = requireNotNull(exoPlayer) { "the engine has not built its ExoPlayer yet" }

    @After
    fun tearDown() {
        engine.release()
        engine.dispose()
        ShadowLooper.idleMainLooper()
    }

    private fun loadOrThrow() {
        runSuspending { engine.load(source) }.getOrThrow()
    }

    // --- Loading ---

    @Test
    fun `a load returns once the source is ready and leaves it paused`() {
        loadOrThrow()

        assertEquals(Player.STATE_READY, player.playbackState)
        assertFalse(player.playWhenReady)
        assertEquals(SOURCE_DURATION_MS, engine.durationMs())
    }

    @Test
    fun `the duration is answered on another thread from the cache`() {
        loadOrThrow()

        assertEquals(SOURCE_DURATION_MS, offLooper { engine.durationMs() })
        assertEquals(0L, offLooper { engine.positionMs() })
    }

    @Test
    fun `a load of a missing file throws the platform error and leaves nothing loaded`() {
        val realSources: Media3VideoEngine = newEngine(
            context,
            created = { exoPlayer = it },
            mediaSources = ::defaultMediaSource,
        ).also { it.setListener(listener) }

        val outcome: Result<Unit> = runSuspending {
            realSources.load(VideoSource.File("/definitely/not/here.mp4"))
        }

        assertIs<PlaybackException>(outcome.exceptionOrNull())
        assertEquals(0, player.mediaItemCount)
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertTrue(listener.failures.isEmpty(), "a load failure is thrown, not also reported")
        realSources.dispose()
    }

    @Test
    fun `a cancelled load leaves nothing loaded and reports nothing`() {
        val neverPrepares = FakeMediaSource().apply { setAllowPreparation(false) }
        val stalled: Media3VideoEngine = newEngine(
            context,
            created = { exoPlayer = it },
            mediaSources = { _, _ -> neverPrepares },
        ).also { it.setListener(listener) }

        val loading: Job = launchOnMain { stalled.load(source) }
        runMainLooperUntil { exoPlayer?.mediaItemCount == 1 }
        loading.cancel()
        runMainLooperUntil { loading.isCompleted }
        ShadowLooper.idleMainLooper()

        assertTrue(loading.isCancelled)
        assertEquals(0, player.mediaItemCount)
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertTrue(listener.sizes.isEmpty() && listener.buffering.isEmpty() && listener.failures.isEmpty())
        stalled.dispose()
    }

    @Test
    fun `loading again replaces the previous source on the same ExoPlayer`() {
        loadOrThrow()
        val first: ExoPlayer = player
        loadOrThrow()

        assertSame(first, player)
        assertEquals(1, player.mediaItemCount)
        assertEquals(Player.STATE_READY, player.playbackState)
    }

    // --- Events ---

    @Test
    fun `the picture size is reported with the pixel aspect ratio applied`() {
        Media3TestSupport.attachSurface(requireNotNull(engine.playerForSurface()))
        loadOrThrow()
        engine.start()
        runMainLooperUntil { listener.sizes.isNotEmpty() }

        assertEquals(VideoSize(1_500, 1_000), listener.sizes.last())
    }

    @Test
    fun `playing to the end reports completion once and drops playWhenReady`() {
        loadOrThrow()
        engine.start()
        runMainLooperUntil { listener.completions.isNotEmpty() }
        ShadowLooper.idleMainLooper()

        assertEquals(1, listener.completions.size)
        assertEquals(Player.STATE_ENDED, player.playbackState)
        assertFalse(player.playWhenReady, "a later seek must not resume playback on its own")
    }

    @Test
    fun `a looping source starts over without reporting completion`() {
        loadOrThrow()
        engine.setLooping(true)
        engine.start()
        TestPlayerRunHelper.runUntilPositionDiscontinuity(player, Player.DISCONTINUITY_REASON_AUTO_TRANSITION)

        assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode)
        assertTrue(listener.completions.isEmpty())
        assertTrue(player.isPlaying || player.playbackState == Player.STATE_BUFFERING)
    }

    @Test
    fun `a seek reports buffering and then its end`() {
        loadOrThrow()
        engine.seekTo(2_000L)
        runMainLooperUntil { listener.buffering.size >= 2 }

        assertEquals(listOf(true, false), listener.buffering)
    }

    // --- Transport and settings ---

    @Test
    fun `settings reach the ExoPlayer`() {
        loadOrThrow()
        engine.setVolume(0.3f)
        engine.setSpeed(1.5f)
        engine.setLooping(true)

        assertEquals(0.3f, player.volume)
        assertEquals(1.5f, player.playbackParameters.speed)
        assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode)

        engine.setLooping(false)
        assertEquals(Player.REPEAT_MODE_OFF, player.repeatMode)
    }

    @Test
    fun `calls from another thread are marshalled onto the looper in order`() {
        loadOrThrow()
        offLooper {
            engine.setVolume(0.1f)
            engine.setVolume(0.7f)
            engine.seekTo(1_500L)
        }
        ShadowLooper.idleMainLooper()

        assertEquals(0.7f, player.volume)
        assertEquals(1_500L, player.currentPosition)
    }

    @Test
    fun `a seek from another thread is visible to the next position read at once`() {
        loadOrThrow()
        val position: Long = offLooper {
            engine.seekTo(3_000L)
            engine.positionMs()
        }

        assertEquals(3_000L, position)
    }

    @Test
    fun `start and pause drive playWhenReady`() {
        loadOrThrow()
        engine.start()
        assertTrue(player.playWhenReady)

        engine.pause()
        assertFalse(player.playWhenReady)
    }

    @Test
    fun `transport calls before any load are harmless`() {
        engine.start()
        engine.pause()
        engine.seekTo(1_000L)
        engine.setSpeed(2f)
        engine.setVolume(0.5f)
        engine.setLooping(true)

        assertEquals(0L, engine.durationMs())
        assertEquals(0L, engine.positionMs())
        assertEquals(0L, engine.bufferedPositionMs())
    }

    // --- Release and dispose ---

    @Test
    fun `release frees the source but keeps the ExoPlayer for the next load`() {
        loadOrThrow()
        val first: ExoPlayer = player
        engine.release()
        ShadowLooper.idleMainLooper()

        assertEquals(0, player.mediaItemCount)
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertEquals(0L, offLooper { engine.durationMs() })
        assertFalse(player.isReleased)

        loadOrThrow()
        assertSame(first, player)
        assertEquals(Player.STATE_READY, player.playbackState)
    }

    @Test
    fun `release is idempotent`() {
        loadOrThrow()
        engine.release()
        engine.release()
        ShadowLooper.idleMainLooper()

        assertEquals(0, player.mediaItemCount)
    }

    @Test
    fun `nothing is reported for a source after release`() {
        loadOrThrow()
        engine.start()
        engine.release()
        ShadowLooper.idleMainLooper()
        repeat(20) { ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS) }

        assertTrue(listener.completions.isEmpty())
        assertTrue(listener.failures.isEmpty())
    }

    @Test
    fun `dispose releases the ExoPlayer and the engine then refuses to load`() {
        loadOrThrow()
        val released: ExoPlayer = player
        engine.release()
        engine.dispose()
        ShadowLooper.idleMainLooper()

        assertTrue(released.isReleased)
        assertNull(engine.playerForSurface())
        assertIs<IllegalStateException>(runSuspending { engine.load(source) }.exceptionOrNull())
    }

    @Test
    fun `the surface player is created on demand on the looper thread and stays the same`() {
        val forSurface: ExoPlayer? = engine.playerForSurface()
        loadOrThrow()

        assertSame(forSurface, player)
        assertSame(forSurface, engine.playerForSurface())
    }

    @Test
    fun `the surface player is not created off the looper thread`() {
        assertNull(offLooper { engine.playerForSurface() })
    }
}
