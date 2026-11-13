package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * D1 round 11 (breaker ip), Q1: a third party engine that reports numbers the contract rules out. The core promises the
 * published playhead stays inside `0..duration` however the engine's reading runs (backwards, past the end, negative,
 * `Long.MIN_VALUE`, `Long.MAX_VALUE`) and that a negative buffered reading is published as `0`; both are held here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostileEngineNumbersD1R11AdversarialTest {

    private val source: VideoSource = VideoSource.Remote("https://example.test/a.mp4")
    private val scheduler = TestCoroutineScheduler()

    private fun withPlayer(engine: ScriptedEngineD1R11, body: (VideoPlayer) -> Unit) {
        val player: VideoPlayer = createVideoPlayer(
            engine,
            VideoPlayerConfig(positionUpdateIntervalMs = 100L),
            StandardTestDispatcher(scheduler),
        )
        try {
            body(player)
        } finally {
            player.release()
        }
    }

    private fun VideoPlayer.prepareNow() {
        val player: VideoPlayer = this
        runBlocking { player.prepare(source) }
    }

    private fun tick() {
        scheduler.advanceTimeBy(150L)
        scheduler.runCurrent()
    }

    @Test
    fun `a playhead that runs backwards or above the length is published inside zero to the length`() {
        val engine = ScriptedEngineD1R11()
        withPlayer(engine) { player: VideoPlayer ->
            player.prepareNow()
            player.play()
            for (reading: Long in listOf(5_000L, 1_000L, 0L, -1L, Long.MIN_VALUE, 9_999L, 10_000L, 10_001L, Long.MAX_VALUE, 3L)) {
                engine.position = reading
                tick()
                val shown: Long = player.playbackPositionFlow.value
                assertTrue(shown in 0L..10_000L, "04: playhead published within 0..duration, engine read $reading, published $shown")
            }
        }
    }

    @Test
    fun `a negative buffered reading is published as zero and a buffered reading above the length is not invented away`() {
        val engine = ScriptedEngineD1R11()
        withPlayer(engine) { player: VideoPlayer ->
            engine.buffered = -7L
            player.prepareNow()
            assertEquals(0L, player.bufferedPositionFlow.value, "the load snapshot floors a negative reading at 0")
            player.play()
            engine.buffered = Long.MIN_VALUE
            tick()
            assertEquals(0L, player.bufferedPositionFlow.value, "the poll floors a negative reading at 0")
            engine.buffered = 50_000L // above the 10 s length: the contract only says what is buffered ahead, nothing caps it
            tick()
            assertEquals(50_000L, player.bufferedPositionFlow.value)
            player.pause()
            engine.buffered = -1L
            player.play()
            player.pause()
            assertEquals(0L, player.bufferedPositionFlow.value, "pause floors a negative reading at 0")
        }
    }
}
