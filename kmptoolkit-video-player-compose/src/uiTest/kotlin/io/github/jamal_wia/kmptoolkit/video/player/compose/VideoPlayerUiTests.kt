package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Everything around the controls: pausing in the background, the composition-owned player of
 * [rememberVideoPlayer] and of the `VideoPlayer(source = …)` overload, the surface's sizing and its
 * keep-screen-on request. Run on Android (Robolectric) and desktop through thin subclasses.
 */
@OptIn(ExperimentalTestApi::class)
abstract class VideoPlayerUiTests {

    private class TestLifecycleOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
        override val lifecycle: Lifecycle get() = registry
    }

    // --- pauseOnBackground ---

    @Test
    fun `a playing player is paused when the host stops`() = runComposeUiTest {
        val owner = TestLifecycleOwner()
        val player = FakeVideoPlayer().apply { startPlaying() }
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { TestHost { VideoPlayer(player) } }
        }

        runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }

        assertEquals(listOf("pause"), player.calls)
    }

    @Test
    fun `playback does not resume when the host starts again`() = runComposeUiTest {
        val owner = TestLifecycleOwner()
        val player = FakeVideoPlayer().apply { startPlaying() }
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { TestHost { VideoPlayer(player) } }
        }

        runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }

        assertEquals(listOf("pause"), player.calls)
    }

    @Test
    fun `a player that is not playing is left alone when the host stops`() = runComposeUiTest {
        val owner = TestLifecycleOwner()
        val player = FakeVideoPlayer().apply { startPaused() }
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { TestHost { VideoPlayer(player) } }
        }

        runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }

        assertEquals(emptyList(), player.calls)
    }

    @Test
    fun `with pauseOnBackground off the player keeps playing when the host stops`() = runComposeUiTest {
        val owner = TestLifecycleOwner()
        val player = FakeVideoPlayer().apply { startPlaying() }
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                TestHost { VideoPlayer(player, pauseOnBackground = false) }
            }
        }

        runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }

        assertEquals(emptyList(), player.calls)
    }

    @Test
    fun `VideoPlayer never releases a player it was given`() = runComposeUiTest {
        val player = FakeVideoPlayer().apply { startPaused() }
        var shown by mutableStateOf(true)
        setContent { TestHost { if (shown) VideoPlayer(player) } }

        shown = false
        waitForIdle()

        assertEquals(0, player.releaseCount)
    }

    // --- rememberVideoPlayer ---

    @Test
    fun `rememberVideoPlayer creates one player through the provided factory and prepares the source`() =
        runComposeUiTest {
            val factory = RecordingFactory()
            val config = VideoPlayerConfig(positionUpdateIntervalMs = 100L)
            var remembered: VideoPlayer? = null
            setContent { TestHost(factory = factory) { remembered = rememberVideoPlayer(SOURCE_A, config = config) } }
            waitForIdle()

            assertEquals(1, factory.created.size)
            assertSame(factory.created.single(), remembered)
            assertSame(config, factory.configs.single())
            assertEquals(listOf(SOURCE_A), factory.created.single().prepared)
            assertFalse("play" in factory.created.single().calls)
        }

    @Test
    fun `a new source is prepared on the same player and cancels the older prepare`() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val factory = RecordingFactory { FakeVideoPlayer().apply { prepareGate = gate } }
        var source: VideoSource by mutableStateOf(SOURCE_A)
        setContent { TestHost(factory = factory) { rememberVideoPlayer(source) } }
        waitForIdle()

        source = SOURCE_B
        waitForIdle()

        val player: FakeVideoPlayer = factory.created.single()
        assertEquals(listOf(SOURCE_A, SOURCE_B), player.prepared)
        assertEquals(1, player.cancelledPrepares)
    }

    @Test
    fun `autoPlay plays once the prepare succeeds`() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val factory = RecordingFactory { FakeVideoPlayer().apply { prepareGate = gate } }
        setContent { TestHost(factory = factory) { rememberVideoPlayer(SOURCE_A, autoPlay = true) } }
        waitForIdle()
        val player: FakeVideoPlayer = factory.created.single()
        assertFalse("play" in player.calls, "must not play before the prepare finishes")

        gate.complete(Unit)
        waitForIdle()

        assertEquals("play", player.calls.last())
        assertTrue(player.state.value is VideoPlayerState.Playing)
    }

    @Test
    fun `a superseded prepare does not start playback`() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val factory = RecordingFactory { FakeVideoPlayer().apply { prepareGate = gate } }
        var source: VideoSource by mutableStateOf(SOURCE_A)
        setContent { TestHost(factory = factory) { rememberVideoPlayer(source, autoPlay = true) } }
        waitForIdle()

        source = SOURCE_B
        waitForIdle()

        val player: FakeVideoPlayer = factory.created.single()
        assertEquals(listOf("prepare($SOURCE_A)", "prepare($SOURCE_B)"), player.calls)
    }

    @Test
    fun `a null source unloads instead of preparing`() = runComposeUiTest {
        val factory = RecordingFactory()
        var source: VideoSource? by mutableStateOf(SOURCE_A)
        setContent { TestHost(factory = factory) { rememberVideoPlayer(source) } }
        waitForIdle()

        source = null
        waitForIdle()

        val player: FakeVideoPlayer = factory.created.single()
        assertEquals("unload", player.calls.last())
        assertEquals(listOf(SOURCE_A), player.prepared)
    }

    @Test
    fun `the remembered player is released when it leaves the composition, and only then`() =
        runComposeUiTest {
            val factory = RecordingFactory()
            var shown by mutableStateOf(true)
            var source: VideoSource by mutableStateOf(SOURCE_A)
            setContent { TestHost(factory = factory) { if (shown) rememberVideoPlayer(source) } }
            waitForIdle()
            source = SOURCE_B
            waitForIdle()
            assertEquals(0, factory.created.single().releaseCount)

            shown = false
            waitForIdle()

            assertEquals(1, factory.created.single().releaseCount)
        }

    @Test
    fun `a re-entered composition gets a fresh player`() = runComposeUiTest {
        val factory = RecordingFactory()
        var generation by mutableStateOf(0)
        setContent { TestHost(factory = factory) { key(generation) { rememberVideoPlayer(SOURCE_A) } } }
        waitForIdle()

        generation = 1
        waitForIdle()

        assertEquals(2, factory.created.size)
        assertEquals(1, factory.created[0].releaseCount)
        assertEquals(0, factory.created[1].releaseCount)
        assertEquals(listOf(SOURCE_A), factory.created[1].prepared)
    }

    // --- VideoPlayer(source = …) ---

    @Test
    fun `the source overload reports progress only while the duration is known`() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val factory = RecordingFactory { FakeVideoPlayer(preparedDurationMs = 80_000L).apply { prepareGate = gate } }
        val samples: MutableList<Pair<Long, Long>> = mutableListOf()
        setContent {
            TestHost(factory = factory) {
                VideoPlayer(source = SOURCE_A, onProgress = { position, duration -> samples += position to duration })
            }
        }
        waitForIdle()
        assertEquals(emptyList(), samples, "nothing while preparing")

        gate.complete(Unit)
        waitForIdle()
        val player: FakeVideoPlayer = factory.created.single()
        player.startPlaying(durationMs = 80_000L, positionMs = 1_000L)
        waitForIdle()
        player.position.value = 2_000L
        waitForIdle()

        assertEquals(listOf(0L to 80_000L, 1_000L to 80_000L, 2_000L to 80_000L), samples)
    }

    @Test
    fun `the source overload releases its player when it leaves the composition`() = runComposeUiTest {
        val factory = RecordingFactory()
        var shown by mutableStateOf(true)
        setContent { TestHost(factory = factory) { if (shown) VideoPlayer(source = SOURCE_A, autoPlay = true) } }
        waitForIdle()
        val player: FakeVideoPlayer = factory.created.single()
        assertTrue("play" in player.calls)

        shown = false
        waitForIdle()

        assertEquals(1, player.releaseCount)
    }

    // --- VideoPlayerSurface ---

    @Test
    fun `fit letterboxes the picture inside the bounds`() = runComposeUiTest {
        assertPictureSize(VideoScaleMode.Fit, VideoSize(200, 100), 100.dp, 50.dp)
    }

    @Test
    fun `fill stretches the picture to the bounds`() = runComposeUiTest {
        assertPictureSize(VideoScaleMode.Fill, VideoSize(200, 100), 100.dp, 100.dp)
    }

    @Test
    fun `crop sizes the picture to cover the bounds`() = runComposeUiTest {
        assertPictureSize(VideoScaleMode.Crop, VideoSize(200, 100), 200.dp, 100.dp)
    }

    @Test
    fun `an unknown picture size fills the bounds`() = runComposeUiTest {
        assertPictureSize(VideoScaleMode.Fit, null, 100.dp, 100.dp)
    }

    @Test
    fun `the surface derives an unbounded height from the picture ratio`() = runComposeUiTest {
        val player = FakeVideoPlayer().apply { size.value = VideoSize(1920, 1080) }
        setContent {
            TestHost {
                Column(Modifier.width(160.dp).verticalScroll(rememberScrollState())) {
                    VideoPlayerSurface(player, Modifier.fillMaxWidth().testTag("surface"))
                }
            }
        }

        onNodeWithTag("surface").assertWidthIsEqualTo(160.dp).assertHeightIsEqualTo(90.dp)
    }

    @Test
    fun `the surface asks to keep the screen on only while playing`() = runComposeUiTest {
        val surface = RecordingSurface()
        val player = FakeVideoPlayer().apply { startPaused() }
        var keepScreenOn by mutableStateOf(true)
        setContent {
            TestHost(surface = surface) { VideoPlayerSurface(player, Modifier.fillMaxSize(), keepScreenOn = keepScreenOn) }
        }
        waitForIdle()
        assertEquals(false, surface.lastKeepScreenOn)

        player.startPlaying()
        waitForIdle()
        assertEquals(true, surface.lastKeepScreenOn)

        keepScreenOn = false
        waitForIdle()
        assertEquals(false, surface.lastKeepScreenOn)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.assertPictureSize(
        mode: VideoScaleMode,
        videoSize: VideoSize?,
        expectedWidth: Dp,
        expectedHeight: Dp,
    ) {
        val surface = RecordingSurface()
        val player = FakeVideoPlayer().apply { size.value = videoSize }
        setContent {
            TestHost(surface = surface) {
                Box(Modifier.size(100.dp)) { VideoPlayerSurface(player, Modifier.fillMaxSize(), scaleMode = mode) }
            }
        }

        onNodeWithTag(PICTURE_TAG, useUnmergedTree = true)
            .assertWidthIsEqualTo(expectedWidth)
            .assertHeightIsEqualTo(expectedHeight)
        assertEquals(mode, surface.lastScaleMode)
    }
}
