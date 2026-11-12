package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 9, the two round 8 survivors J2 and J12 of `FxPlayback`: J2 is the `seek(player.currentTime)` that `setSpeed`
 * makes on a fresh play only when no native seek was posted since (a mutant that seeks every time puts a stale
 * `currentTime` over the seek the caller made), J12 is the `seekedSincePlay` mark of the replay after the end (a mutant
 * that forgets it makes the resume seek to `currentTime` straight after `seek(startTime)`). Both differ only when
 * `currentTime` still reads the position from before the posted seek, which round 8 saw once under load. So the
 * scenarios run under CPU load (busy threads), many times, and check what the caller was promised: a seek or a replay
 * is not undone by the speed that follows it.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxStaleReadUnderLoadR9AdversarialTest {

    private inline fun <T> underLoad(block: () -> T): T {
        val stop = AtomicBoolean(false)
        val workers: List<Thread> = (0 until (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)).map {
            Thread {
                var x = 1L
                while (!stop.get()) { x = x * 6364136223846793005L + 1442695040888963407L; if (x == 42L) Thread.yield() }
            }.apply { isDaemon = true; start() }
        }
        try {
            return block()
        } finally {
            stop.set(true)
            workers.forEach { it.join(2_000L) }
        }
    }

    @Test
    fun `a seek made just after a play and followed by a speed is not undone by the speed`() {
        assumeJavaFxMedia()
        val failures: MutableList<String> = mutableListOf()
        underLoad {
            repeat(TRIALS) { trial: Int ->
                val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
                try {
                    runBlocking { withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) } }
                    engine.start()
                    Thread.sleep(80L + (trial % 5) * 40L)
                    engine.seekTo(2_000L)
                    engine.setSpeed(2f)
                    val until: Long = System.nanoTime() + 1_500_000_000L
                    var lowest: Long = Long.MAX_VALUE
                    while (System.nanoTime() < until) {
                        lowest = minOf(lowest, engine.positionMs())
                        Thread.sleep(2L)
                    }
                    if (lowest < 1_500L) failures += "trial $trial: the playhead read $lowest after a seek to 2000 and a speed of 2"
                } finally {
                    engine.release()
                }
            }
        }
        assertEquals(emptyList(), failures, "of $TRIALS trials under load")
    }

    @Test
    fun `a replay after the end at a speed starts from the start and does not return to the end`() {
        assumeJavaFxMedia()
        val failures: MutableList<String> = mutableListOf()
        underLoad {
            repeat(TRIALS) { trial: Int ->
                val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
                val listener = RecordingListener()
                engine.setListener(listener)
                try {
                    runBlocking { withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) } }
                    engine.setSpeed(4f)
                    engine.start()
                    awaitTrue(timeoutMs = 8_000L, message = { "trial $trial: never completed at 4x" }) { listener.events.any { it is EngineEvent.Completed } }
                    val completions: Int = listener.events.count { it is EngineEvent.Completed }
                    engine.seekTo(0L)
                    engine.setSpeed(2f)
                    engine.start()
                    val until: Long = System.nanoTime() + 900_000_000L
                    var highest: Long = 0L
                    while (System.nanoTime() < until) {
                        highest = maxOf(highest, engine.positionMs())
                        Thread.sleep(2L)
                    }
                    if (highest > 2_300L || listener.events.count { it is EngineEvent.Completed } > completions) {
                        failures += "trial $trial: 900 ms after a replay at 2x the playhead reached $highest (completions ${listener.events.count { it is EngineEvent.Completed }} vs $completions)"
                    }
                } finally {
                    engine.release()
                }
            }
        }
        assertEquals(emptyList(), failures, "of $TRIALS trials under load")
    }

    private companion object {
        val TRIALS: Int = System.getenv("KMPTOOLKIT_R9_TRIALS")?.toIntOrNull() ?: 6
    }
}
