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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Everything around the controls: pausing in the background, the composition-owned player of
 * [rememberVideoPlayer] and of the `VideoPlayer(source = …)` overload, the surface's sizing and its
 * keep-screen-on request. The players are the library's own over the fake engine (see [TestPlayer]).
 * Run on Android (Robolectric) and desktop through thin subclasses.
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
        val player = TestPlayer().playing()
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { TestHost { VideoPlayer(player.player) } }
        }

        runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }

        assertEquals(listOf("pause"), player.calls)
        assertIs<VideoPlayerState.Paused>(player.state)
    }

    @Test
    fun `playback does not resume when the host starts again`() = runComposeUiTest {
        val owner = TestLifecycleOwner()
        val player = TestPlayer().playing()
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { TestHost { VideoPlayer(player.player) } }
        }

        runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }

        assertEquals(listOf("pause"), player.calls)
        assertIs<VideoPlayerState.Paused>(player.state)
    }

    @Test
    fun `a player that is not playing is left alone when the host stops`() = runComposeUiTest {
        val owner = TestLifecycleOwner()
        val player = TestPlayer().paused()
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { TestHost { VideoPlayer(player.player) } }
        }

        runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }

        assertEquals(emptyList(), player.calls)
    }

    @Test
    fun `with pauseOnBackground off the player keeps playing when the host stops`() = runComposeUiTest {
        val owner = TestLifecycleOwner()
        val player = TestPlayer().playing()
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                TestHost { VideoPlayer(player.player, pauseOnBackground = false) }
            }
        }

        runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }

        assertEquals(emptyList(), player.calls)
        assertIs<VideoPlayerState.Playing>(player.state)
    }

    @Test
    fun `VideoPlayer never releases a player it was given`() = runComposeUiTest {
        val player = TestPlayer().paused()
        var shown by mutableStateOf(true)
        setContent { TestHost { if (shown) VideoPlayer(player.player) } }

        shown = false
        waitForIdle()

        assertTrue(player.fake.hasListener, "the player was released")
        assertIs<VideoPlayerState.Paused>(player.state)
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

            val created: TestPlayer = factory.created.single()
            assertSame(created.player, remembered)
            assertSame(config, factory.configs.single())
            assertEquals(listOf(SOURCE_A), created.fake.loadedSources)
            assertEquals(VideoPlayerState.Ready(TestPlayer.DEFAULT_DURATION_MS), created.state)
            assertEquals(0, created.engine.starts, "autoPlay is off")
        }

    @Test
    fun `a new source is prepared on the same player and cancels the older prepare`() = runComposeUiTest {
        val factory = RecordingFactory { TestPlayer().apply { fake.suspendLoads = true } }
        var source: VideoSource by mutableStateOf(SOURCE_A)
        setContent { TestHost(factory = factory) { rememberVideoPlayer(source) } }
        waitForIdle()
        val player: TestPlayer = factory.created.single()
        assertEquals(listOf(SOURCE_A), player.fake.loadedSources)

        source = SOURCE_B
        waitForIdle()

        // The player runs one load at a time: B reaching the engine while A never finished means A
        // was cancelled.
        assertEquals(listOf(SOURCE_A, SOURCE_B), player.fake.loadedSources)
        assertTrue(player.fake.isLoadPending)
        assertEquals(VideoPlayerState.Preparing, player.state)

        player.fake.finishLoad()
        waitForIdle()
        assertEquals(VideoPlayerState.Ready(TestPlayer.DEFAULT_DURATION_MS), player.state)
        assertEquals(listOf(SOURCE_A, SOURCE_B), player.fake.loadedSources, "nothing loaded again")
    }

    @Test
    fun `autoPlay plays once the prepare succeeds`() = runComposeUiTest {
        val factory = RecordingFactory { TestPlayer().apply { fake.suspendLoads = true } }
        setContent { TestHost(factory = factory) { rememberVideoPlayer(SOURCE_A, autoPlay = true) } }
        waitForIdle()
        val player: TestPlayer = factory.created.single()
        assertEquals(0, player.engine.starts, "must not play before the prepare finishes")

        player.fake.finishLoad()
        waitForIdle()

        assertEquals(1, player.engine.starts)
        assertIs<VideoPlayerState.Playing>(player.state)
    }

    @Test
    fun `a superseded prepare does not start playback`() = runComposeUiTest {
        val factory = RecordingFactory { TestPlayer().apply { fake.suspendLoads = true } }
        var source: VideoSource by mutableStateOf(SOURCE_A)
        setContent { TestHost(factory = factory) { rememberVideoPlayer(source, autoPlay = true) } }
        waitForIdle()
        val player: TestPlayer = factory.created.single()

        source = SOURCE_B
        waitForIdle()
        assertEquals(listOf(SOURCE_A, SOURCE_B), player.fake.loadedSources)
        assertEquals(0, player.engine.starts, "nothing is ready yet")

        // Only B's load is still open; letting it finish is the one success autoPlay acts on.
        player.fake.finishLoad()
        waitForIdle()

        assertEquals(1, player.engine.starts, "exactly one start, for B: ${player.calls}")
        assertIs<VideoPlayerState.Playing>(player.state)
        assertEquals(listOf(SOURCE_A, SOURCE_B), player.fake.loadedSources)
    }

    @Test
    fun `a null source unloads instead of preparing`() = runComposeUiTest {
        val factory = RecordingFactory()
        var source: VideoSource? by mutableStateOf(SOURCE_A)
        setContent { TestHost(factory = factory) { rememberVideoPlayer(source) } }
        waitForIdle()
        val player: TestPlayer = factory.created.single()
        assertIs<VideoPlayerState.Ready>(player.state)

        source = null
        waitForIdle()

        assertEquals(VideoPlayerState.Idle, player.state)
        assertEquals(listOf(SOURCE_A), player.fake.loadedSources)
        assertTrue(player.fake.hasListener, "unloaded, not released")
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
            val player: TestPlayer = factory.created.single()
            assertTrue(player.fake.hasListener, "released too early")

            shown = false
            waitForIdle()

            assertFalse(player.fake.hasListener, "not released")
            assertEquals(VideoPlayerState.Idle, player.state)
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
        assertFalse(factory.created[0].fake.hasListener, "the first player was not released")
        assertTrue(factory.created[1].fake.hasListener)
        assertEquals(listOf(SOURCE_A), factory.created[1].fake.loadedSources)
        assertIs<VideoPlayerState.Ready>(factory.created[1].state)
    }

    // --- VideoPlayer(source = …) ---

    @Test
    fun `the source overload reports progress only while the duration is known`() = runComposeUiTest {
        val factory = RecordingFactory { TestPlayer(durationMs = 80_000L).apply { fake.suspendLoads = true } }
        val samples: MutableList<Pair<Long, Long>> = mutableListOf()
        setContent {
            TestHost(factory = factory) {
                VideoPlayer(source = SOURCE_A, onProgress = { position, duration -> samples += position to duration })
            }
        }
        waitForIdle()
        assertEquals(emptyList(), samples, "nothing while preparing")

        val player: TestPlayer = factory.created.single()
        player.fake.finishLoad()
        waitForIdle()
        player.player.play()
        player.movePlayhead(1_000L)
        waitForIdle()
        player.movePlayhead(2_000L)
        waitForIdle()

        assertEquals(listOf(0L to 80_000L, 1_000L to 80_000L, 2_000L to 80_000L), samples)
    }

    @Test
    fun `the source overload never reports a source of unknown length`() = runComposeUiTest {
        val factory = RecordingFactory { TestPlayer(durationMs = 0L) }
        val samples: MutableList<Pair<Long, Long>> = mutableListOf()
        setContent {
            TestHost(factory = factory) {
                VideoPlayer(source = SOURCE_A, autoPlay = true, onProgress = { p, d -> samples += p to d })
            }
        }
        waitForIdle()
        factory.created.single().movePlayhead(5_000L)
        waitForIdle()

        assertIs<VideoPlayerState.Playing>(factory.created.single().state)
        assertEquals(emptyList(), samples)
    }

    @Test
    fun `the source overload releases its player when it leaves the composition`() = runComposeUiTest {
        val factory = RecordingFactory()
        var shown by mutableStateOf(true)
        setContent { TestHost(factory = factory) { if (shown) VideoPlayer(source = SOURCE_A, autoPlay = true) } }
        waitForIdle()
        val player: TestPlayer = factory.created.single()
        assertEquals(1, player.engine.starts)

        shown = false
        waitForIdle()

        assertFalse(player.fake.hasListener, "not released")
        assertEquals(VideoPlayerState.Idle, player.state)
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
        val player = TestPlayer().apply { fake.videoSizeOnLoad = VideoSize(1920, 1080) }.prepare()
        setContent {
            TestHost {
                Column(Modifier.width(160.dp).verticalScroll(rememberScrollState())) {
                    VideoPlayerSurface(player.player, Modifier.fillMaxWidth().testTag("surface"))
                }
            }
        }

        onNodeWithTag("surface").assertWidthIsEqualTo(160.dp).assertHeightIsEqualTo(90.dp)
    }

    @Test
    fun `the surface asks to keep the screen on only while playing`() = runComposeUiTest {
        val surface = RecordingSurface()
        val player = TestPlayer().paused()
        var keepScreenOn by mutableStateOf(true)
        setContent {
            TestHost(surface = surface) {
                VideoPlayerSurface(player.player, Modifier.fillMaxSize(), keepScreenOn = keepScreenOn)
            }
        }
        waitForIdle()
        assertEquals(false, surface.lastKeepScreenOn)

        player.player.play()
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
        val player = TestPlayer().apply { fake.videoSizeOnLoad = videoSize }.prepare()
        setContent {
            TestHost(surface = surface) {
                Box(Modifier.size(100.dp)) { VideoPlayerSurface(player.player, Modifier.fillMaxSize(), scaleMode = mode) }
            }
        }

        onNodeWithTag(PICTURE_TAG, useUnmergedTree = true)
            .assertWidthIsEqualTo(expectedWidth)
            .assertHeightIsEqualTo(expectedHeight)
        assertEquals(mode, surface.lastScaleMode)
    }
}
