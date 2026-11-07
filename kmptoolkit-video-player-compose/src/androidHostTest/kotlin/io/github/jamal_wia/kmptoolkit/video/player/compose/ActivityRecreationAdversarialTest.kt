package io.github.jamal_wia.kmptoolkit.video.player.compose

import android.content.Context
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.media3PlayerOrNull
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Configuration change: a player held outside the activity (a view model, in an app) while the
 * activity that renders it is destroyed and rebuilt, twenty times. The real production player and
 * surface are used, with nothing stubbed; Robolectric has no decoders, so what is checked is the
 * attachment: which `SurfaceView` the ExoPlayer holds, that the destroyed activities' views let go,
 * that the player is never released by the surface, and that a composition-owned player is
 * released once per composition that left.
 *
 * The activity is driven with `ActivityController.recreate()`, which destroys the instance and
 * builds a new one; the content is set again in the new instance, as an app's `onCreate` does.
 * Which ExoPlayer holds a view is read from its fake surface holder, as `AndroidSurfaceTest` does.
 * Caveat: Robolectric keeps saved state in process memory, so this is not a cold restore.
 */
@OptIn(ToolkitInternalApi::class)
@RunWith(RobolectricTestRunner::class)
class ActivityRecreationAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val controllers: MutableList<ActivityController<ComponentActivity>> = mutableListOf()
    private val players: MutableList<VideoPlayer> = mutableListOf()

    @After
    fun tearDown() {
        controllers.forEach { runCatching { it.pause().stop().destroy() } }
        players.forEach { it.release() }
        pump(5)
    }

    private fun newActivity(): ActivityController<ComponentActivity> =
        Robolectric.buildActivity(ComponentActivity::class.java).setup().also { controllers += it }

    private fun newPlayer(): VideoPlayer = createVideoPlayer(context).also { players += it }

    private fun show(activity: ComponentActivity, content: @Composable () -> Unit) {
        activity.setContentView(ComposeView(activity).apply { setContent(content) })
    }

    private fun pump(frames: Int = 1) {
        repeat(frames) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PUMP_MS)) }
    }

    private fun settle(what: String, condition: () -> Boolean) {
        repeat(MAX_PUMPS) {
            if (condition()) return
            pump()
        }
        fail("did not settle: $what")
    }

    private fun surfaceViews(activity: ComponentActivity): List<SurfaceView> {
        val found: MutableList<SurfaceView> = mutableListOf()
        fun visit(view: View) {
            if (view is SurfaceView) found += view
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(activity.window.decorView)
        return found
    }

    private fun SurfaceView.holderCallbacks(): Set<SurfaceHolder.Callback> =
        shadowOf(this).fakeSurfaceHolder.callbacks.toSet()

    private fun ComponentActivity.surfaceHoldingAPlayer(): Boolean =
        surfaceViews(this).let { views: List<SurfaceView> -> views.size == 1 && views.single().holderCallbacks().size == 1 }

    @Test
    fun `a player held outside the activity stays alive and attached to the newest surface across 20 recreations`() {
        val player: VideoPlayer = newPlayer()
        val controller: ActivityController<ComponentActivity> = newActivity()
        var previous: SurfaceView? = null

        repeat(RECREATIONS) { cycle: Int ->
            val activity: ComponentActivity = controller.get()
            show(activity) {
                Box(Modifier.size(160.dp, 90.dp)) { VideoPlayerSurface(player, Modifier.fillMaxSize(), keepScreenOn = true) }
            }
            settle("cycle $cycle: the new surface holds the ExoPlayer") { activity.surfaceHoldingAPlayer() }

            val view: SurfaceView = surfaceViews(activity).single()
            assertEquals(1, view.holderCallbacks().size, "cycle $cycle: exactly one ExoPlayer holds the newest surface")
            assertNotNull(player.media3PlayerOrNull(), "cycle $cycle: the player was released")
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value, "cycle $cycle: player state")
            assertFalse(view.keepScreenOn, "cycle $cycle: keep-screen-on while the player is not playing")
            previous?.let {
                assertEquals(
                    emptySet(),
                    it.holderCallbacks(),
                    "cycle $cycle: the destroyed activity's surface still holds the ExoPlayer",
                )
            }
            previous = view
            controller.recreate()
            pump(2)
        }

        // The last activity goes too: nothing holds the player's surface, and the player lives on.
        controller.pause().stop().destroy()
        pump(3)
        assertEquals(emptySet(), requireNotNull(previous).holderCallbacks(), "the last surface still holds the ExoPlayer")
        assertNotNull(player.media3PlayerOrNull(), "the surface released the player it did not own")
    }

    @Test
    fun `a new activity attached before the old one is destroyed keeps the surface when the old one goes`() {
        val player: VideoPlayer = newPlayer()
        val old: ActivityController<ComponentActivity> = newActivity()
        show(old.get()) { Box(Modifier.size(160.dp, 90.dp)) { VideoPlayerSurface(player, Modifier.fillMaxSize()) } }
        settle("old surface holds the ExoPlayer") { old.get().surfaceHoldingAPlayer() }
        val oldView: SurfaceView = surfaceViews(old.get()).single()

        val replacement: ActivityController<ComponentActivity> = newActivity()
        show(replacement.get()) { Box(Modifier.size(160.dp, 90.dp)) { VideoPlayerSurface(player, Modifier.fillMaxSize()) } }
        settle("new surface holds the ExoPlayer") { replacement.get().surfaceHoldingAPlayer() }
        val newView: SurfaceView = surfaceViews(replacement.get()).single()

        old.pause().stop().destroy()
        pump(3)

        assertEquals(emptySet(), oldView.holderCallbacks(), "the destroyed activity's surface still holds the ExoPlayer")
        assertEquals(1, newView.holderCallbacks().size, "the old activity's teardown took the ExoPlayer off the new surface")
        assertNotNull(player.media3PlayerOrNull(), "the player was released")
    }

    @Test
    fun `fifty swaps between two players leave the surface with exactly the current one and release neither`() {
        val first: VideoPlayer = newPlayer()
        val second: VideoPlayer = newPlayer()
        val activity: ComponentActivity = newActivity().get()
        var current: VideoPlayer by mutableStateOf(first)
        show(activity) { Box(Modifier.size(160.dp, 90.dp)) { VideoPlayerSurface(current, Modifier.fillMaxSize()) } }
        settle("first player attached") { activity.surfaceHoldingAPlayer() }
        val view: SurfaceView = surfaceViews(activity).single()
        val firstHolder: SurfaceHolder.Callback = view.holderCallbacks().single()

        current = second
        settle("second player attached") { view.holderCallbacks().let { it.size == 1 && it.single() !== firstHolder } }
        val secondHolder: SurfaceHolder.Callback = view.holderCallbacks().single()

        repeat(SWAPS) { swap: Int ->
            val expected: SurfaceHolder.Callback = if (swap % 2 == 0) firstHolder else secondHolder
            current = if (swap % 2 == 0) first else second
            settle("swap $swap: the surface holds the current player") { view.holderCallbacks() == setOf(expected) }
            assertEquals(1, view.holderCallbacks().size, "swap $swap: more than one ExoPlayer holds the surface")
        }

        assertNotNull(first.media3PlayerOrNull(), "the surface released the first player")
        assertNotNull(second.media3PlayerOrNull(), "the surface released the second player")
    }

    @Test
    fun `a composition-owned player is released once for every composition that left across 20 recreations`() {
        val factory = RecordingFactory()
        val controller: ActivityController<ComponentActivity> = newActivity()

        repeat(RECREATIONS) { cycle: Int ->
            show(controller.get()) {
                CompositionLocalProvider(LocalVideoPlayerFactory provides factory) {
                    rememberVideoPlayer(SOURCE_A, autoPlay = true)
                }
            }
            settle("cycle $cycle: the player of this composition plays") {
                factory.created.size == cycle + 1 && factory.created[cycle].state is VideoPlayerState.Playing
            }
            if (cycle > 0) {
                val old: TestPlayer = factory.created[cycle - 1]
                assertFalse(old.fake.hasListener, "cycle $cycle: the previous composition's player was not released")
            }
            controller.recreate()
            pump(2)
        }
        controller.pause().stop().destroy()
        pump(3)

        assertEquals(RECREATIONS, factory.created.size, "exactly one player per composition")
        factory.created.forEachIndexed { i: Int, created: TestPlayer ->
            assertFalse(created.fake.hasListener, "player $i was not released")
            assertEquals(1, created.fake.disposeCount, "player $i: dispose count")
            assertEquals(listOf(SOURCE_A), created.fake.loadedSources, "player $i: loads")
            assertEquals(1, created.engine.starts, "player $i: starts")
            assertEquals(VideoPlayerState.Idle, created.state, "player $i: state after release")
        }
    }

    @Test
    fun `fifty real stop and start cycles pause each time and never resume playback`() {
        val player: TestPlayer = TestPlayer().playing()
        val controller: ActivityController<ComponentActivity> = newActivity()
        show(controller.get()) { VideoPlayer(player.player) }
        pump(3)

        repeat(BACKGROUNDINGS) { cycle: Int ->
            if (cycle > 0) player.player.play()
            controller.pause().stop()
            pump(2)
            assertTrue(player.state is VideoPlayerState.Paused, "cycle $cycle: not paused by the stop: ${player.state} ${player.calls} observers=${(controller.get().lifecycle as androidx.lifecycle.LifecycleRegistry).observerCount} lifecycle=${controller.get().lifecycle.currentState}")
            controller.restart().start().resume()
            pump(2)
            assertTrue(player.state is VideoPlayerState.Paused, "cycle $cycle: resumed by the start")
        }

        assertEquals(BACKGROUNDINGS - 1, player.engine.starts, "only the test's own play() calls may start playback")
        assertEquals(BACKGROUNDINGS, player.calls.count { it == "pause" })

        // After a recreation the new activity's composition pauses again, and the old one is gone.
        controller.recreate()
        show(controller.get()) { VideoPlayer(player.player) }
        pump(3)
        player.player.play()
        controller.pause().stop()
        pump(2)
        assertEquals(BACKGROUNDINGS + 1, player.calls.count { it == "pause" }, "one pause per stop, none doubled")
        player.player.release()
    }

    private companion object {
        const val RECREATIONS: Int = 20
        const val SWAPS: Int = 50
        const val BACKGROUNDINGS: Int = 50
        const val PUMP_MS: Long = 32L
        const val MAX_PUMPS: Int = 100
    }
}
