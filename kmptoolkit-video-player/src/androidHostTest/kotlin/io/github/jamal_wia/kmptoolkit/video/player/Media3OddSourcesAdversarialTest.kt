package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Empty, malformed and odd sources through the production source mapping and a real ExoPlayer:
 * prepare never throws, never hangs, ends in Error (there is nothing to play), and the player is
 * usable afterwards. Only sources that fail locally and at once: no network host is contacted.
 */
@OptIn(DelicateCoroutinesApi::class, ToolkitInternalApi::class)
@RunWith(AndroidJUnit4::class)
class Media3OddSourcesAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val players: MutableList<VideoPlayer> = mutableListOf()

    @After
    fun tearDown() {
        players.forEach { it.release() }
        pump(3)
    }

    private fun pump(frames: Int = 1) {
        repeat(frames) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32L)) }
    }

    private fun settle(what: String, condition: () -> Boolean) {
        val deadline: Long = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            pump()
            Thread.sleep(2L)
        }
        fail("did not settle: $what")
    }

    private val oddSources: List<VideoSource> = listOf(
        VideoSource.Asset(""),
        VideoSource.Asset("////"),
        VideoSource.Asset("a b#c?d.mp4"),
        VideoSource.Asset("../../outside.mp4"),
        VideoSource.File(""),
        VideoSource.File("/"),
        VideoSource.File("a\u0000b.mp4"),
        VideoSource.File("/nonexistent/" + "x".repeat(300) + ".mp4"),
        VideoSource.Remote(""),
        VideoSource.Remote("   "),
        VideoSource.Remote("http://"),
        VideoSource.Remote("::::"),
        VideoSource.Remote("file:///nonexistent/clip.mp4"),
        VideoSource.Remote("", format = RemoteFormat.Hls),
        VideoSource.Remote("::::", format = RemoteFormat.Progressive),
        VideoSource.Asset("\n"),
    )

    @Test
    fun `an empty or malformed source settles on Error without throwing and leaves the player usable`() {
        val player: VideoPlayer = createVideoPlayer(context).also { players += it }
        oddSources.forEachIndexed { index: Int, odd: VideoSource ->
            var thrown: Throwable? = null
            val call: Job = GlobalScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                try {
                    player.prepare(odd)
                } catch (t: Throwable) {
                    thrown = t
                }
            }
            settle("source #$index $odd: prepare returned") { call.isCompleted }
            assertNull(thrown, "source #$index $odd: prepare threw $thrown")
            val state: VideoPlayerState = player.stateFlow.value
            assertIs<VideoPlayerState.Error>(state, "source #$index $odd settled on $state")
            // Transport calls on an Error player are ignored.
            player.play()
            player.seekTo(10L)
            assertTrue(player.stateFlow.value is VideoPlayerState.Error, "source #$index: a call revived an Error player")
            assertEquals(0L, player.bufferedPositionFlow.value)
            assertNull(player.videoSizeFlow.value)
            assertTrue(!player.isBufferingFlow.value)
        }
        player.unload()
        pump(2)
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
    }

    @Test
    fun `every odd source loaded back to back by fresh players leaves nothing behind`() {
        oddSources.forEachIndexed { index: Int, odd: VideoSource ->
            val player: VideoPlayer = createVideoPlayer(context).also { players += it }
            val call: Job = GlobalScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                player.prepare(odd)
            }
            // Released at a different moment each time: before the looper ran, after one pump, after the answer.
            when (index % 3) {
                0 -> player.release()
                1 -> { pump(1); player.release() }
                else -> { settle("#$index answered") { call.isCompleted }; player.release() }
            }
            settle("#$index: the prepare returned") { call.isCompleted }
            pump(2)
            assertNull(player.media3PlayerOrNull(), "#$index: an ExoPlayer survived the release")
        }
    }
}
