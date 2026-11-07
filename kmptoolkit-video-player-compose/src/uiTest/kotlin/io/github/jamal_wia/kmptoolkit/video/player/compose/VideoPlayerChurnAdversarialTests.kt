package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Lifecycle churn: compositions that come and go as fast as frames allow, and a host that moves to
 * the background and back a hundred times. Nothing may leak (a player left unreleased, an observer
 * left registered) and nothing may act on a player after its composition released it. Run on
 * Android (Robolectric) and desktop through thin subclasses.
 */
@OptIn(ExperimentalTestApi::class)
abstract class VideoPlayerChurnAdversarialTests {

    private class TestLifecycleOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
        override val lifecycle: Lifecycle get() = registry
    }

    /** Counts what reaches the engine after the player disposed it — nothing may. */
    private class LateCallCountingEngine(private val inner: RecordingEngine) : VideoPlaybackEngine by inner {
        var disposed: Boolean = false
        var lateCalls: Int = 0

        private fun late() {
            if (disposed) lateCalls++
        }

        override suspend fun load(source: VideoSource) {
            late()
            inner.load(source)
        }

        override fun start() {
            late()
            inner.start()
        }

        override fun pause() {
            late()
            inner.pause()
        }

        override fun seekTo(positionMs: Long) {
            late()
            inner.seekTo(positionMs)
        }

        override fun setVolume(volume: Float) {
            late()
            inner.setVolume(volume)
        }

        override fun setSpeed(speed: Float) {
            late()
            inner.setSpeed(speed)
        }

        override fun setLooping(looping: Boolean) {
            late()
            inner.setLooping(looping)
        }

        override fun dispose() {
            disposed = true
            inner.dispose()
        }
    }

    // --- Composition churn ---

    @Test
    fun `fifty fast toggles of a self-owned player release every player once and never touch one after release`() =
        runComposeUiTest {
            val engines: MutableList<LateCallCountingEngine> = mutableListOf()
            var index = 0
            val factory = RecordingFactory {
                val slow: Boolean = index++ % 2 == 1
                TestPlayer(wrap = { LateCallCountingEngine(it).also(engines::add) }).apply {
                    fake.suspendLoads = slow
                }
            }
            var shown by mutableStateOf(false)
            setContent {
                TestHost(factory = factory) { if (shown) VideoPlayer(source = SOURCE_A, autoPlay = true) }
            }
            waitForIdle()

            // One frame per toggle, never a full idle: each player is composed, its effects start,
            // and the composition is torn down again before they have all finished.
            mainClock.autoAdvance = false
            repeat(TOGGLES) {
                shown = true
                Snapshot.sendApplyNotifications()
                mainClock.advanceTimeByFrame()
                shown = false
                Snapshot.sendApplyNotifications()
                mainClock.advanceTimeByFrame()
            }
            mainClock.autoAdvance = true
            waitForIdle()

            assertEquals(TOGGLES, factory.created.size, "one player per time the composition was entered")
            factory.created.forEachIndexed { i: Int, created: TestPlayer ->
                assertFalse(created.fake.hasListener, "player $i was not released")
                assertEquals(1, created.fake.disposeCount, "player $i: dispose count")
                assertEquals(VideoPlayerState.Idle, created.state, "player $i: state after release")
                assertTrue(created.fake.loadedSources.size <= 1, "player $i prepared ${created.fake.loadedSources}")
            }
            engines.forEachIndexed { i: Int, engine: LateCallCountingEngine ->
                assertEquals(0, engine.lateCalls, "player $i: engine calls after dispose")
            }
        }

    @Test
    fun `fifty fast source swaps on one remembered player leave one load open and autoplay only the last source`() =
        runComposeUiTest {
            val factory = RecordingFactory { TestPlayer().apply { fake.suspendLoads = true } }
            var source: VideoSource by mutableStateOf(SOURCE_A)
            setContent { TestHost(factory = factory) { rememberVideoPlayer(source, autoPlay = true) } }
            waitForIdle()

            mainClock.autoAdvance = false
            repeat(TOGGLES) { i: Int ->
                source = VideoSource.Remote("https://example.test/swap-$i.mp4")
                Snapshot.sendApplyNotifications()
                mainClock.advanceTimeByFrame()
            }
            mainClock.autoAdvance = true
            waitForIdle()
            val player: TestPlayer = factory.created.single()
            assertTrue(player.fake.loadedSources.size > 2, "the swaps never reached the player: ${player.fake.loadedSources}")
            assertEquals(0, player.engine.starts, "a superseded prepare started playback: ${player.calls}")
            assertTrue(player.fake.isLoadPending, "the newest load must be the one still open")

            player.fake.finishLoad()
            waitForIdle()

            assertEquals(1, player.engine.starts, "exactly one start, for the last source: ${player.calls}")
            assertIs<VideoPlayerState.Playing>(player.state)
        }

    // --- Background / foreground churn ---

    @Test
    fun `a hundred stop and start cycles pause each time, never resume, and leave no observer after leaving`() =
        runComposeUiTest {
            val owner = TestLifecycleOwner()
            val player = TestPlayer().playing()
            var shown by mutableStateOf(true)
            val baseline: Int = owner.registry.observerCount
            setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    TestHost { if (shown) VideoPlayer(player.player) }
                }
            }
            waitForIdle()
            val composed: Int = owner.registry.observerCount
            assertTrue(composed > baseline, "the effect registered no observer, so the test measures nothing")

            repeat(CYCLES) { i: Int ->
                if (i > 0) player.player.play()
                runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
                assertIs<VideoPlayerState.Paused>(player.state, "cycle $i: not paused by the stop")
                runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
                assertIs<VideoPlayerState.Paused>(player.state, "cycle $i: resumed by the start")
                assertEquals(composed, owner.registry.observerCount, "cycle $i: observers accumulated")
            }
            assertEquals(CYCLES - 1, player.engine.starts, "only the test's own play() calls may start playback")
            assertEquals(CYCLES, player.calls.count { it == "pause" })

            shown = false
            waitForIdle()
            assertEquals(baseline, owner.registry.observerCount, "an observer outlived the composition")

            // Out of the composition the effect is gone: a stop no longer pauses the player.
            player.player.play()
            runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
            assertIs<VideoPlayerState.Playing>(player.state)
            player.player.release()
        }

    @Test
    fun `a player released by its owner while composed stays inert across fifty recompositions and playhead moves`() =
        runComposeUiTest {
            val player = TestPlayer().playing()
            var tick by mutableStateOf(0)
            setContent {
                TestHost { VideoPlayer(player.player, keepScreenOn = tick % 2 == 0) }
            }
            waitForIdle()

            player.player.release()
            repeat(TOGGLES) { i: Int ->
                tick = i
                player.movePlayhead(1_000L + i)
                waitForIdle()
            }

            assertEquals(VideoPlayerState.Idle, player.state)
            assertEquals(1, player.fake.disposeCount, "dispose count")
            assertFalse(player.fake.hasListener)
        }

    private companion object {
        const val TOGGLES: Int = 50
        const val CYCLES: Int = 100
    }
}
