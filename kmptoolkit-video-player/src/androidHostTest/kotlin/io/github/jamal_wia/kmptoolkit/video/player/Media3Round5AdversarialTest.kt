package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.newEngine
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.runSuspending
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Round 5: the real Media3 engine under a re-prepare close to the end of the previous source, and
 * under platform objects that cannot be built.
 */
@OptIn(ToolkitInternalApi::class)
@RunWith(AndroidJUnit4::class)
class Media3Round5AdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sourceA: VideoSource = VideoSource.Remote("https://example.test/a.mp4")
    private val sourceB: VideoSource = VideoSource.Remote("https://example.test/b.mp4")
    private val players: MutableList<VideoPlayer> = mutableListOf()

    @After
    fun tearDown() {
        players.forEach { it.release() }
        repeat(10) { ShadowLooper.idleMainLooper() }
    }

    private fun track(player: VideoPlayer): VideoPlayer = player.also { players += it }

    @Test
    fun `a re-prepare right at the end of the old source never ends as Completed or Error`() {
        for (pumps in 0..24) {
            val player: VideoPlayer = track(
                createVideoPlayer(engine = newEngine(context), coroutineContext = Dispatchers.Unconfined),
            )
            Media3TestSupport.attachSurface(requireNotNull(player.media3PlayerOrNull()))
            runSuspending { player.prepare(sourceA) }.getOrThrow()
            player.play()
            player.seekTo(Media3TestSupport.SOURCE_DURATION_MS - 20L)
            repeat(pumps) { ShadowLooper.idleMainLooper() }
            runSuspending { player.prepare(sourceB) }.getOrThrow()
            repeat(20) { ShadowLooper.idleMainLooper() }
            val state: VideoPlayerState = player.stateFlow.value
            assertTrue(
                state is VideoPlayerState.Ready,
                "after $pumps looper passes the re-prepared player was $state",
            )
            assertEquals(false, player.isBufferingFlow.value, "after $pumps passes B shows a stall")
            assertEquals(0L, player.playbackPositionFlow.value, "after $pumps passes B's playhead moved")
            player.release()
        }
    }

    @Test
    fun `an ExoPlayer that cannot be built fails the prepare and a later prepare builds it`() {
        var failures = 1
        val engine = Media3VideoEngine(
            context = context,
            looper = android.os.Looper.getMainLooper(),
            playerFactory = { playerContext: Context, looper: android.os.Looper ->
                if (failures-- > 0) throw IllegalStateException("no decoder resources")
                androidx.media3.test.utils.TestExoPlayerBuilder(playerContext).setLooper(looper).build()
            },
            mediaSourceFactory = { _, _ -> Media3TestSupport.fakeSource() },
        )
        val player: VideoPlayer = track(createVideoPlayer(engine = engine, coroutineContext = Dispatchers.Unconfined))
        runSuspending { player.prepare(sourceA) }.getOrThrow()
        assertIs<VideoPlayerState.Error>(player.stateFlow.value)
        runSuspending { player.prepare(sourceB) }.getOrThrow()
        assertIs<VideoPlayerState.Ready>(player.stateFlow.value, "the retry was ${player.stateFlow.value}")
    }

    @Test
    fun `a media source that cannot be built fails the prepare and leaves the player usable`() {
        var failures = 2
        val engine = newEngine(
            context,
            mediaSources = { _, _ ->
                if (failures-- > 0) throw OutOfMemoryError("source buffers")
                Media3TestSupport.fakeSource()
            },
        )
        val player: VideoPlayer = track(createVideoPlayer(engine = engine, coroutineContext = Dispatchers.Unconfined))
        runSuspending { player.prepare(sourceA) }.getOrThrow()
        assertIs<VideoPlayerState.Error>(player.stateFlow.value)
        runSuspending { player.prepare(sourceA) }.getOrThrow()
        assertIs<VideoPlayerState.Error>(player.stateFlow.value)
        runSuspending { player.prepare(sourceB) }.getOrThrow()
        assertIs<VideoPlayerState.Ready>(player.stateFlow.value, "the third prepare was ${player.stateFlow.value}")
        player.play()
        repeat(5) { ShadowLooper.idleMainLooper() }
        assertIs<VideoPlayerState.Playing>(player.stateFlow.value)
    }
}
