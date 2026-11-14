package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 5, real JavaFX: a seek back to 0 made just as the end of the source is reached. The end handler
 * pauses the JavaFX player and reports completion; a seek still queued behind it makes the engine report
 * the seek's target as the playhead, so the player drops the completion as stale, and the seek then
 * lands on a paused JavaFX player. Whatever the order, the engine must end up either completed or
 * playing, never paused at 0 with nothing reported.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxSeekAtTheEndR5AdversarialTest {

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
    }

    @Test
    fun `a seek to the start made as the end is reached never leaves the engine paused and silent`() {
        val stuck: MutableList<String> = mutableListOf()
        val random = Random(5)
        repeat(16) { trial: Int ->
            val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
            val listener = RecordingListener()
            engine.setListener(listener)
            try {
                runBlocking { withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) } }
                engine.setSpeed(8f)
                engine.start()
                val trigger: Long = random.nextLong(2_300L, 3_000L)
                val deadline: Long = System.nanoTime() + 6_000L * 1_000_000L
                while (System.nanoTime() < deadline && engine.positionMs() < trigger &&
                    !listener.events.contains(EngineEvent.Completed)
                ) {
                    Thread.sleep(1L)
                }
                engine.seekTo(0L)
                // Either the seek landed before the end (the source plays again and ends again at 8x) or
                // after it (completed). Wait for a completion that is newer than the seek.
                val settle: Long = System.nanoTime() + 3_500L * 1_000_000L
                var status = ""
                while (System.nanoTime() < settle && !listener.events.contains(EngineEvent.Completed)) {
                    Thread.sleep(25L)
                }
                status = runBlocking { engine.inspectPlayback() }?.status ?: "none"
                if (!listener.events.contains(EngineEvent.Completed) && status != "PLAYING") {
                    stuck += "trial $trial: no completion after 3.5 s, status $status, position ${engine.positionMs()}, trigger $trigger"
                }
            } finally {
                engine.release()
            }
        }
        assertEquals(emptyList(), stuck)
    }
}
