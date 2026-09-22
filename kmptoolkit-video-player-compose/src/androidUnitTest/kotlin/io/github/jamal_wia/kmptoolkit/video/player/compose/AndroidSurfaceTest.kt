package io.github.jamal_wia.kmptoolkit.video.player.compose

import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.media3PlayerOrNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Android surface with nothing stubbed: players from the production `createVideoPlayer(context)`,
 * so a real ExoPlayer is behind them, rendered through [VideoPlayerSurface] and [VideoPlayer] into a
 * real activity. Robolectric has no decoders, so nothing plays; what is checked is the attachment —
 * the `SurfaceView`, its size, its keep-screen-on flag, and which ExoPlayer holds its surface.
 *
 * Which ExoPlayer is attached is read from the view's surface holder: an ExoPlayer attached to a
 * `SurfaceView` registers a callback on its holder, and removes it when it detaches or is released.
 */
@OptIn(ExperimentalTestApi::class, ToolkitInternalApi::class)
@RunWith(RobolectricTestRunner::class)
class AndroidSurfaceTest {

    private fun AndroidComposeUiTest<ComponentActivity>.surfaceViews(): List<SurfaceView> {
        val found: MutableList<SurfaceView> = mutableListOf()
        fun visit(view: View) {
            if (view is SurfaceView) found += view
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        runOnUiThread { visit(activity!!.window.decorView) }
        return found
    }

    private fun AndroidComposeUiTest<ComponentActivity>.singleSurfaceView(): SurfaceView =
        surfaceViews().single()

    private fun SurfaceView.holderCallbacks(): Set<SurfaceHolder.Callback> =
        shadowOf(this).fakeSurfaceHolder.callbacks.toSet()

    private fun newPlayer(test: AndroidComposeUiTest<ComponentActivity>): VideoPlayer =
        test.runOnUiThread { createVideoPlayer(test.activity!!) }

    @Test
    fun `the surface is a SurfaceView sized to the surface and attached to the player's ExoPlayer`() =
        runAndroidComposeUiTest<ComponentActivity> {
            val player: VideoPlayer = newPlayer(this)
            setContent {
                Box(Modifier.size(160.dp, 90.dp)) {
                    VideoPlayerSurface(player, Modifier.fillMaxSize().testTag("surface"))
                }
            }
            waitForIdle()

            val view: SurfaceView = singleSurfaceView()
            val size: IntSize = onNodeWithTag("surface").fetchSemanticsNode().size
            assertTrue(size.width > 0 && size.height > 0, "surface size $size")
            assertEquals(size, IntSize(view.width, view.height))
            assertNotNull(runOnUiThread { player.media3PlayerOrNull() })
            assertEquals(1, view.holderCallbacks().size, "the ExoPlayer holds the view's surface")

            runOnUiThread { player.release() }
        }

    @Test
    fun `the screen is not kept on while the player is not playing`() = runAndroidComposeUiTest<ComponentActivity> {
        val player: VideoPlayer = newPlayer(this)
        setContent {
            Box(Modifier.size(160.dp, 90.dp)) {
                VideoPlayerSurface(player, Modifier.fillMaxSize(), keepScreenOn = true)
            }
        }
        waitForIdle()

        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertFalse(singleSurfaceView().keepScreenOn)

        runOnUiThread { player.release() }
    }

    @Test
    fun `swapping the player detaches the old ExoPlayer and attaches the new one`() =
        runAndroidComposeUiTest<ComponentActivity> {
            val first: VideoPlayer = newPlayer(this)
            val second: VideoPlayer = newPlayer(this)
            var current: VideoPlayer by mutableStateOf(first)
            setContent {
                Box(Modifier.size(160.dp, 90.dp)) { VideoPlayerSurface(current, Modifier.fillMaxSize()) }
            }
            waitForIdle()
            val view: SurfaceView = singleSurfaceView()
            val firstCallbacks: Set<SurfaceHolder.Callback> = view.holderCallbacks()
            assertEquals(1, firstCallbacks.size)

            current = second
            waitForIdle()

            val secondCallbacks: Set<SurfaceHolder.Callback> = view.holderCallbacks()
            assertEquals(1, secondCallbacks.size, "exactly one ExoPlayer holds the surface")
            assertTrue(secondCallbacks.none { it in firstCallbacks }, "the first ExoPlayer still holds the surface")
            val firstExo: Player? = runOnUiThread { first.media3PlayerOrNull() }
            val secondExo: Player? = runOnUiThread { second.media3PlayerOrNull() }
            assertTrue(firstExo is ExoPlayer && secondExo is ExoPlayer && firstExo !== secondExo)

            runOnUiThread {
                first.release()
                second.release()
            }
        }

    @Test
    fun `the surface detaches when it leaves the composition and leaves the player alone`() =
        runAndroidComposeUiTest<ComponentActivity> {
            val player: VideoPlayer = newPlayer(this)
            var shown by mutableStateOf(true)
            setContent {
                Box(Modifier.size(160.dp, 90.dp)) { if (shown) VideoPlayerSurface(player, Modifier.fillMaxSize()) }
            }
            waitForIdle()
            val view: SurfaceView = singleSurfaceView()
            assertEquals(1, view.holderCallbacks().size)

            shown = false
            waitForIdle()

            assertEquals(emptySet(), view.holderCallbacks())
            assertNotNull(runOnUiThread { player.media3PlayerOrNull() }, "the surface must not release the player")
            runOnUiThread { player.release() }
        }

    @Test
    fun `the source overload leaving the composition releases its player without a crash`() =
        runAndroidComposeUiTest<ComponentActivity> {
            var created: VideoPlayer? = null
            val factory = VideoPlayerFactory { config ->
                createVideoPlayer(activity!!, config).also { created = it }
            }
            var shown by mutableStateOf(true)
            setContent {
                androidx.compose.runtime.CompositionLocalProvider(LocalVideoPlayerFactory provides factory) {
                    Box(Modifier.size(160.dp, 90.dp)) {
                        if (shown) VideoPlayer(source = MISSING_FILE, modifier = Modifier.fillMaxSize(), controls = {})
                    }
                }
            }
            waitForIdle()
            val view: SurfaceView = singleSurfaceView()
            val player: VideoPlayer = assertNotNull(created)
            assertEquals(1, view.holderCallbacks().size)

            // The player and its view go in the same pass; whichever Compose disposes first, the
            // view must not reach into a released ExoPlayer, and nothing may throw.
            shown = false
            waitForIdle()

            assertEquals(emptySet(), view.holderCallbacks())
            assertEquals(null, runOnUiThread { player.media3PlayerOrNull() }, "the player was not released")
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        }

    private companion object {
        /** A local source that fails fast: no network, no decoder needed. */
        val MISSING_FILE: VideoSource = VideoSource.File("/nonexistent/clip.mp4")
    }

    @Test
    fun `the platform factory is used when none is provided`() = runAndroidComposeUiTest<ComponentActivity> {
        var player: VideoPlayer? = null
        var shown by mutableStateOf(true)
        setContent {
            Box(Modifier.size(160.dp, 90.dp)) {
                if (shown) player = rememberVideoPlayer(source = null)
            }
        }
        waitForIdle()

        assertNotNull(runOnUiThread { assertNotNull(player).media3PlayerOrNull() }, "a Media3 player is behind it")

        shown = false
        waitForIdle()
        assertEquals(null, runOnUiThread { player!!.media3PlayerOrNull() })
    }
}
