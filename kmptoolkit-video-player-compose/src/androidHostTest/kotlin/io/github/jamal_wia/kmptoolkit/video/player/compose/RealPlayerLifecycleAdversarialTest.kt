package io.github.jamal_wia.kmptoolkit.video.player.compose

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.media3PlayerOrNull
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The real production player and surface, released at the moments an app releases them: while the
 * surface is on screen, before it ever was, in the middle of a load, and then built again. Robolectric
 * has no decoders, so a load of a missing file fails at once; what matters is that nothing throws on
 * the main looper, no call hangs, and a later player and surface are unaffected.
 */
@OptIn(ToolkitInternalApi::class, DelicateCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RealPlayerLifecycleAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val controllers: MutableList<ActivityController<ComponentActivity>> = mutableListOf()
    private val players: MutableList<VideoPlayer> = mutableListOf()

    @After
    fun tearDown() {
        controllers.forEach { runCatching { it.pause().stop().destroy() } }
        players.forEach { it.release() }
        pump(5)
    }

    private fun newPlayer(): VideoPlayer = createVideoPlayer(context).also { players += it }

    private fun newActivity(): ActivityController<ComponentActivity> =
        Robolectric.buildActivity(ComponentActivity::class.java).setup().also { controllers += it }

    private fun show(activity: ComponentActivity, content: @Composable () -> Unit) {
        activity.setContentView(ComposeView(activity).apply { setContent(content) })
    }

    private fun pump(frames: Int = 1) {
        repeat(frames) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PUMP_MS)) }
    }

    private fun settle(what: String, condition: () -> Boolean) {
        // The engine reports a failed load from its own playback thread, in real time, while a pump
        // advances only the looper's virtual clock: wait in real time too, or a fast run of pumps
        // gives up before the thread got to answer. Still bounded, never an open-ended wait.
        val deadline: Long = System.nanoTime() + SETTLE_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            if (condition()) return
            pump()
            Thread.sleep(SETTLE_SLEEP_MS)
        }
        fail("did not settle: $what")
    }

    @Test
    fun `releasing the player while its surface is on screen leaves the screen able to tear down and a new player able to take over`() {
        val first: VideoPlayer = newPlayer()
        val controller: ActivityController<ComponentActivity> = newActivity()
        show(controller.get()) { Box(Modifier.size(160.dp, 90.dp)) { VideoPlayerSurface(first, Modifier.fillMaxSize()) } }
        pump(3)

        first.release()
        pump(3)
        assertNull(first.media3PlayerOrNull(), "a released player still hands out its ExoPlayer")
        assertEquals(VideoPlayerState.Idle, first.stateFlow.value)

        // The screen goes away over the released player, then a second activity shows a second player.
        controller.recreate()
        pump(3)
        val second: VideoPlayer = newPlayer()
        show(controller.get()) { Box(Modifier.size(160.dp, 90.dp)) { VideoPlayerSurface(second, Modifier.fillMaxSize()) } }
        pump(3)
        assertTrue(second.media3PlayerOrNull() != null, "the second player has no ExoPlayer")
        second.release()
        pump(3)
        controller.pause().stop().destroy()
        pump(3)
    }

    @Test
    fun `showing a surface for a player released before it was shown is blank and harmless`() {
        val player: VideoPlayer = newPlayer()
        player.release()
        val controller: ActivityController<ComponentActivity> = newActivity()
        show(controller.get()) { Box(Modifier.size(160.dp, 90.dp)) { VideoPlayerSurface(player, Modifier.fillMaxSize()) } }
        pump(3)
        assertNull(player.media3PlayerOrNull())
        controller.pause().stop().destroy()
        pump(3)
    }

    @Test
    fun `a real player released in the middle of a load ends the load, and fifteen rebuilt players behave the same`() {
        repeat(CYCLES) { cycle: Int ->
            val player: VideoPlayer = newPlayer()
            val call: Job = GlobalScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                player.prepare(VideoSource.File("/nonexistent/clip-$cycle.mp4"))
            }
            if (cycle % 3 == 0) pump(1)
            player.release()
            settle("cycle $cycle: the prepare returned") { call.isCompleted }
            pump(2)
            val state: VideoPlayerState = player.stateFlow.value
            assertTrue(
                state == VideoPlayerState.Idle || (state is VideoPlayerState.Error),
                "cycle $cycle: state after release mid-load was $state",
            )
            assertNull(player.media3PlayerOrNull(), "cycle $cycle: ExoPlayer survived the release")
        }
        // A player built after all of that still works: it reports the missing file as an Error.
        val fresh: VideoPlayer = newPlayer()
        val call: Job = GlobalScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            fresh.prepare(VideoSource.File("/nonexistent/final.mp4"))
        }
        settle("the fresh player's prepare returned") { call.isCompleted }
        assertTrue(fresh.stateFlow.value is VideoPlayerState.Error, "fresh player state ${fresh.stateFlow.value}")
    }

    @Test
    fun `an activity destroyed by a rotation is collectable while the player it showed lives on`() {
        val player: VideoPlayer = newPlayer()
        val controller: ActivityController<ComponentActivity> = newActivity()
        val gone: MutableList<java.lang.ref.WeakReference<ComponentActivity>> = mutableListOf()
        repeat(4) {
            show(controller.get()) { Box(Modifier.size(160.dp, 90.dp)) { VideoPlayerSurface(player, Modifier.fillMaxSize()) } }
            pump(3)
            gone += java.lang.ref.WeakReference(controller.get())
            controller.recreate()
            pump(3)
        }
        // The player stays alive and is used; the destroyed activities must not be reachable through it.
        player.setVolume(0.5f)
        repeat(GC_ROUNDS) {
            if (gone.all { it.get() == null }) return@repeat
            System.gc()
            pump(1)
        }
        val leaked: Int = gone.count { it.get() != null }
        assertEquals(0, leaked, "$leaked of ${gone.size} destroyed activities are still reachable from the live player")
        assertTrue(player.media3PlayerOrNull() != null)
    }

    private companion object {
        const val GC_ROUNDS: Int = 30
        const val CYCLES: Int = 15
        const val PUMP_MS: Long = 32L
        const val SETTLE_SLEEP_MS: Long = 2L
        const val SETTLE_TIMEOUT_NANOS: Long = 15_000_000_000L
    }
}
