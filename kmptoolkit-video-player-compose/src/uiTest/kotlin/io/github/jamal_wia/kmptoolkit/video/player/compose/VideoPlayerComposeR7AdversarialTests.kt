package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Round 7 (breaker hf), Q1 of unit D1: the Compose layer under state hoisting misuse (two composables
 * bound to one player, a player replaced during composition, a released player passed in), key and
 * configuration changes, saved-state restoration, a layout direction flip under the finger, and
 * disposal. Everything is derived from docs/kmptoolkit-video-player-compose (03-guide "Who owns the
 * player", "Configuration and factories are read once", "Keep screen on, background, lifecycle";
 * 04-api-reference). Run on Android (Robolectric) and desktop through thin subclasses.
 */
@OptIn(ExperimentalTestApi::class)
abstract class VideoPlayerComposeR7AdversarialTests {

    private class HostLifecycle : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun `two player composables bound to one player both pause it at stop and neither releases it`() =
        runComposeUiTest {
            val host = HostLifecycle()
            val test: TestPlayer = TestPlayer().playing()
            var first by mutableStateOf(true)
            var second by mutableStateOf(true)
            val baseline: Int = host.registry.observerCount
            setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides host) {
                    TestHost {
                        if (first) VideoPlayer(test.player, controlsAutoHideDelayMs = NEVER_HIDE_MS)
                        if (second) VideoPlayer(test.player, controlsAutoHideDelayMs = NEVER_HIDE_MS)
                    }
                }
            }
            waitForIdle()
            assertTrue(host.registry.observerCount >= baseline + 2, "each composable observes the lifecycle")

            runOnIdle { host.registry.currentState = Lifecycle.State.CREATED }
            assertIs<VideoPlayerState.Paused>(test.state)
            assertTrue(test.calls.count { it == "pause" } >= 1)
            runOnIdle { host.registry.currentState = Lifecycle.State.RESUMED }

            // One leaves: the other still reacts and the player is untouched.
            first = false
            waitForIdle()
            test.player.play()
            runOnIdle { host.registry.currentState = Lifecycle.State.CREATED }
            assertIs<VideoPlayerState.Paused>(test.state)
            runOnIdle { host.registry.currentState = Lifecycle.State.RESUMED }

            second = false
            waitForIdle()
            assertEquals(baseline, host.registry.observerCount, "an observer outlived both composables")
            assertEquals(0, test.fake.disposeCount, "a composable released a player it was given")
            assertTrue(test.fake.hasListener)
            test.player.play()
            assertIs<VideoPlayerState.Playing>(test.state)
            test.player.release()
        }

    @Test
    fun `a player replaced during composition moves the lifecycle effect and the controls to the new player`() =
        runComposeUiTest {
            val host = HostLifecycle()
            val a: TestPlayer = TestPlayer().playing()
            val b: TestPlayer = TestPlayer().playing()
            var current: VideoPlayer by mutableStateOf(a.player)
            val baseline: Int = host.registry.observerCount
            setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides host) {
                    TestHost { VideoPlayer(current, controlsAutoHideDelayMs = NEVER_HIDE_MS) { DefaultVideoControls(labels = LABELS) } }
                }
            }
            waitForIdle()
            val composed: Int = host.registry.observerCount

            current = b.player
            waitForIdle()
            assertEquals(composed, host.registry.observerCount, "the old player's observer was not replaced")

            // The control now drives B, not A.
            onNodeWithContentDescription(requireNotNull(LABELS.pause)).performClick()
            waitForIdle()
            assertIs<VideoPlayerState.Paused>(b.state)
            assertIs<VideoPlayerState.Playing>(a.state, "the old player is still driven by the screen")

            // Background stop acts on B (playing again) and leaves A alone.
            b.player.play()
            runOnIdle { host.registry.currentState = Lifecycle.State.CREATED }
            assertIs<VideoPlayerState.Paused>(b.state)
            assertIs<VideoPlayerState.Playing>(a.state, "the old player was paused by a screen that no longer shows it")
            assertEquals(0, a.fake.disposeCount)
            assertEquals(0, b.fake.disposeCount)
            runOnIdle { host.registry.currentState = Lifecycle.State.RESUMED }
            assertTrue(host.registry.observerCount >= baseline)
            a.player.release()
            b.player.release()
        }

    @Test
    fun `swapping to a released player and back is harmless and the live player keeps working`() =
        runComposeUiTest {
            val live: TestPlayer = TestPlayer().paused()
            val dead: TestPlayer = TestPlayer().prepare()
            dead.player.release()
            var current: VideoPlayer by mutableStateOf(live.player)
            setContent {
                TestHost { VideoPlayer(current, controlsAutoHideDelayMs = NEVER_HIDE_MS) { DefaultVideoControls(labels = LABELS) } }
            }
            waitForIdle()

            current = dead.player
            waitForIdle()
            assertEquals(VideoPlayerState.Idle, dead.state)
            // Nothing is playable: the controls are disabled, a click is refused, and nothing throws.
            onNodeWithContentDescription(requireNotNull(LABELS.play)).performClick()
            waitForIdle()
            assertEquals(VideoPlayerState.Idle, dead.state)
            assertEquals(1, dead.fake.disposeCount, "a released player was disposed again")

            current = live.player
            waitForIdle()
            onNodeWithContentDescription(requireNotNull(LABELS.play)).performClick()
            waitForIdle()
            assertIs<VideoPlayerState.Playing>(live.state)
            assertEquals(0, live.fake.disposeCount)
            live.player.release()
        }

    @Test
    fun `a config key recreates the owned player and releases the old one while an unkeyed change is ignored`() =
        runComposeUiTest {
            val factory = RecordingFactory()
            var config: VideoPlayerConfig by mutableStateOf(VideoPlayerConfig(positionUpdateIntervalMs = 250L))
            var keyed by mutableStateOf(true)
            setContent {
                TestHost(factory = factory) {
                    if (keyed) {
                        key(config) { VideoPlayer(source = SOURCE_A, config = config, controlsAutoHideDelayMs = NEVER_HIDE_MS) }
                    } else {
                        VideoPlayer(source = SOURCE_A, config = config, controlsAutoHideDelayMs = NEVER_HIDE_MS)
                    }
                }
            }
            waitForIdle()
            assertEquals(1, factory.created.size)

            config = VideoPlayerConfig(positionUpdateIntervalMs = 100L)
            waitForIdle()
            assertEquals(2, factory.created.size, "a new key must build a new player")
            assertEquals(100L, factory.configs.last().positionUpdateIntervalMs)
            assertEquals(1, factory.created[0].fake.disposeCount, "the player of the old key was not released")
            assertEquals(VideoPlayerState.Idle, factory.created[0].state)
            assertEquals(SOURCE_A, factory.created[1].fake.loadedSources.single())
            assertEquals(0, factory.created[1].fake.disposeCount)

            // Without the key the same change is ignored, as the guide says.
            keyed = false
            waitForIdle()
            val afterUnkey: Int = factory.created.size
            config = VideoPlayerConfig(positionUpdateIntervalMs = 500L)
            waitForIdle()
            assertEquals(afterUnkey, factory.created.size, "an unkeyed config change must not recreate the player")
            factory.created.last().player.release()
        }

    @Test
    fun `a factory provided later is not consulted by a player that already exists`() = runComposeUiTest {
        val first = RecordingFactory()
        val second = RecordingFactory()
        var which by mutableStateOf(first)
        setContent {
            TestHost(factory = which) { rememberVideoPlayer(SOURCE_A) }
        }
        waitForIdle()
        assertEquals(1, first.created.size)

        which = second
        waitForIdle()
        assertEquals(0, second.created.size, "the guide says the factory is read on first composition only")
        assertEquals(0, first.created.single().fake.disposeCount)
        first.created.single().player.release()
    }

    @Test
    fun `onProgress follows the latest lambda and stops when the composition leaves`() = runComposeUiTest {
        val factory = RecordingFactory()
        val old: MutableList<Long> = mutableListOf()
        val new: MutableList<Long> = mutableListOf()
        var callback: (Long, Long) -> Unit by mutableStateOf({ p: Long, _: Long -> old += p })
        var shown by mutableStateOf(true)
        setContent {
            TestHost(factory = factory) {
                if (shown) VideoPlayer(source = SOURCE_A, onProgress = callback, controlsAutoHideDelayMs = NEVER_HIDE_MS)
            }
        }
        waitForIdle()
        val test: TestPlayer = factory.created.single()
        test.player.seekTo(1_000L)
        waitForIdle()
        assertTrue(1_000L in old, "old lambda: $old")

        callback = { p: Long, _: Long -> new += p }
        waitForIdle()
        test.player.seekTo(2_000L)
        waitForIdle()
        assertTrue(2_000L in new, "new lambda: $new")
        assertFalse(2_000L in old, "the stale lambda was still called: $old")

        shown = false
        waitForIdle()
        val before: Int = new.size
        test.fake.advancePositionTo(3_000L)
        waitForIdle()
        assertEquals(before, new.size, "a callback arrived after the composition left")
        assertEquals(1, test.fake.disposeCount)
    }

    @Test
    fun `a screen that swaps to a playing player shows the controls and hides them again after the delay`() =
        runComposeUiTest {
            val a: TestPlayer = TestPlayer().paused()
            val b: TestPlayer = TestPlayer().playing()
            var current: VideoPlayer by mutableStateOf(a.player)
            mainClock.autoAdvance = false
            setContent {
                TestHost { VideoPlayer(current, controlsAutoHideDelayMs = 3_000L) { DefaultVideoControls(labels = LABELS) } }
            }
            mainClock.advanceTimeByFrame()
            onNodeWithContentDescription(requireNotNull(LABELS.play)).assertExists()

            current = b.player
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            onNodeWithContentDescription(requireNotNull(LABELS.pause)).assertExists()
            mainClock.advanceTimeBy(3_000L + 1_000L)
            onNodeWithContentDescription(requireNotNull(LABELS.pause)).assertDoesNotExist()
            assertSame(b.player, current)
            a.player.release()
            b.player.release()
        }
}
