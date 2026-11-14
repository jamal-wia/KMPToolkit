package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.isPlayable
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * D1 round 9 (breaker hz), Q4: counts, never wall time. A thousand player lifetimes over the fake engine leave
 * no engine reading after release (a leaked position poll would keep reading the playhead), no listener and
 * exactly one dispose each; and the position poll of a playing player allocates a bounded number of bytes per
 * tick on the thread that drives it (the allocation counter of the thread, as the downloader budget tests use).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerBudgetR9AdversarialTest {

    private val source: VideoSource = VideoSource.Remote("https://example.test/a.mp4")

    /** Counts every read of the playhead: the one thing only a running position poll does. */
    private class CountingEngine(val fake: FakeVideoPlaybackEngine) : VideoPlaybackEngine by fake {
        var positionReads: Int = 0

        override fun positionMs(): Long {
            positionReads++
            return fake.positionMs()
        }
    }

    @Test
    fun `a thousand lifetimes leave no poll no listener and one dispose each`() {
        val scheduler = TestCoroutineScheduler()
        val engines: MutableList<CountingEngine> = mutableListOf()
        repeat(LIFETIMES) { index: Int ->
            val engine = CountingEngine(FakeVideoPlaybackEngine(60_000L))
            engines += engine
            val player: VideoPlayer = createVideoPlayer(
                engine,
                VideoPlayerConfig(positionUpdateIntervalMs = 100L),
                StandardTestDispatcher(scheduler),
            )
            try {
                runBlocking { player.prepare(source) }
                assertTrue(player.stateFlow.value.isPlayable, "lifetime $index did not load")
                // Every third lifetime plays for a while, so the poll is really running when the release comes.
                if (index % 3 == 0) {
                    player.play()
                    scheduler.advanceTimeBy(350L)
                    scheduler.runCurrent()
                }
            } finally {
                player.release()
            }
        }
        // Time passes far beyond any poll interval with every player already released.
        val readsAtRelease: List<Int> = engines.map { it.positionReads }
        scheduler.advanceTimeBy(600_000L)
        scheduler.runCurrent()

        engines.forEachIndexed { index: Int, engine: CountingEngine ->
            assertEquals(readsAtRelease[index], engine.positionReads, "engine $index was polled after its release")
            assertEquals(1, engine.fake.disposeCount, "engine $index dispose count")
            assertFalse(engine.fake.hasListener, "engine $index kept its listener")
            assertFalse(engine.fake.isPlaying, "engine $index still plays")
        }
    }

    @Test
    fun `the position poll of a playing player allocates a bounded number of bytes per tick`() {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assertTrue(threads.isThreadAllocatedMemorySupported, "this JVM has no per-thread allocation counter")
        threads.isThreadAllocatedMemoryEnabled = true
        val scheduler = TestCoroutineScheduler()
        val engine = FakeVideoPlaybackEngine(Long.MAX_VALUE / 4)
        val player: VideoPlayer = createVideoPlayer(
            engine,
            VideoPlayerConfig(positionUpdateIntervalMs = 100L),
            StandardTestDispatcher(scheduler),
        )
        try {
            runBlocking { player.prepare(source) }
            player.play()
            // The position moves on every tick, as playback does, so every tick publishes a new state.
            fun tick(count: Int) {
                repeat(count) {
                    engine.advancePositionBy(100L)
                    scheduler.advanceTimeBy(100L)
                    scheduler.runCurrent()
                }
            }
            tick(WARMUP_TICKS)
            val before: Long = threads.currentThreadAllocatedBytes
            tick(MEASURED_TICKS)
            val after: Long = threads.currentThreadAllocatedBytes
            val perTick: Long = (after - before) / MEASURED_TICKS
            println("R9 allocation per tick on the state path: $perTick bytes")
            assertTrue(player.stateFlow.value is VideoPlayerState.Playing, "the player stopped playing")
            assertTrue(player.playbackPositionFlow.value > 0L, "no tick reached the player")
            assertTrue(perTick < MAX_BYTES_PER_TICK, "$perTick bytes per tick, bound $MAX_BYTES_PER_TICK")
        } finally {
            player.release()
        }
    }

    private companion object {
        const val LIFETIMES: Int = 1_000
        const val WARMUP_TICKS: Int = 3_000
        const val MEASURED_TICKS: Int = 2_000
        const val MAX_BYTES_PER_TICK: Long = 4_096L
    }
}
