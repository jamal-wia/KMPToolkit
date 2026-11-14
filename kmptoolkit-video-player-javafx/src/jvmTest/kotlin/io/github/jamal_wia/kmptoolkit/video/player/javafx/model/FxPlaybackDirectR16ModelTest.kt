package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrame
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.javafx.FxPlayback
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * D2 round 16, Q5: survivors of the mutant sweep (`adversarial/D2/round-16-breaker.md`) that no public-API test reaches, pinned at `FxPlayback`
 * itself (the engine drops a call once its session is closed, so only the playback sees the late call):
 *
 * - M8: a `setLooping` that reaches the playback after `dispose()` is dropped (no cycle count is written on a disposed player);
 * - M9: an end of media that JavaFX delivers after `dispose()` does nothing (no `Completed`, no native `pause()`);
 * - M2: a status that turns `PLAYING` again after a stall longer than the refresh window starts the pulse again (the frames flow on).
 */
@OptIn(ToolkitInternalApi::class)
class FxPlaybackDirectR16ModelTest {

    private class RecordingSink : FxPlayback.Sink {
        val times: MutableList<Triple<Long, Long, Long>> = CopyOnWriteArrayList()
        val order: MutableList<String> = CopyOnWriteArrayList()
        override fun onTimes(durationMs: Long, positionMs: Long, bufferedMs: Long) {
            times += Triple(durationMs, positionMs, bufferedMs)
            order += "times"
        }
        override fun onSeekApplied() = Unit
        override fun onVideoSize(size: VideoSize?) = Unit
        override fun onBuffering(isBuffering: Boolean) = Unit
        override fun onCompleted() {
            order += "completed"
        }
        override fun onFailed(cause: Throwable) {
            order += "failed"
        }
        override fun onFrame(frame: VideoFrame) = Unit
    }

    private fun FxModelScope.open(sink: FxPlayback.Sink, backend: ComposedBackend): FxPlayback {
        // timing-hygiene-ignore: TH2 bound the real-time hang bound of an open whose IO hop is real
        val deadline: Long = System.nanoTime() + BOUND_MS * NANOS_PER_MILLI
        return runBlocking {
            val opening = async { FxPlayback.open("scripted:${TestClip.ASSET}", sink, 30, 15_000L, backend, onCreated = {}) }
            while (!opening.isCompleted) {
                host.runUntilIdle()
                yield()
                // timing-hygiene-ignore: TH2 bound the real-time hang bound of an open whose IO hop is real
                if (!opening.isCompleted && System.nanoTime() - deadline > 0L) {
                    opening.cancel()
                    throw AssertionError("open did not finish within $BOUND_MS ms of real time ($host)")
                }
                Thread.onSpinWait()
            }
            opening.await()
        }
    }

    @Test
    fun aLoopingChangeThatReachesThePlaybackAfterDisposeIsDropped() = fxModel {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host))
        val playback: FxPlayback = open(RecordingSink(), backend)
        advanceBy(50)
        playback.setLooping(true)
        advanceBy(10)
        assertEquals(-1, backend.last.player.cycleCount, "a live playback takes the looping change")
        playback.dispose()
        advanceBy(50)
        val before: Int = backend.last.player.log.calls(NativeCall.SET_CYCLE_COUNT).size
        playback.setLooping(false)
        advanceBy(50)
        assertEquals(before, backend.last.player.log.calls(NativeCall.SET_CYCLE_COUNT).size, "a cycle count was written on a disposed player: ${backend.last.player.log.tail(6)}")
    }

    @Test
    fun anEndOfMediaDeliveredAfterDisposeDoesNothing() = fxModel {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host))
        val sink = RecordingSink()
        val playback: FxPlayback = open(sink, backend)
        advanceBy(50)
        playback.dispose()
        advanceBy(50)
        val pauses: Int = backend.last.player.log.calls(NativeCall.PAUSE).size
        onFx { backend.last.player.deliverEndOfMedia(ignoreClearedHandler = true) }
        advanceBy(50)
        assertEquals(emptyList(), sink.order.filter { it == "completed" }, "Completed was reported for a disposed playback: ${sink.order}")
        assertEquals(pauses, backend.last.player.log.calls(NativeCall.PAUSE).size, "a disposed native player was paused by a late end of media: ${backend.last.player.log.tail(6)}")
    }

    @Test
    fun aStatusThatTurnsPlayingAgainAfterALongStallStartsThePulseAgain() = fxModel {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host))
        val playback: FxPlayback = open(RecordingSink(), backend)
        advanceBy(50)
        playback.start()
        advanceBy(300)
        onFx { backend.last.player.forceStatus(NativeStatus.STALLED) }
        advanceBy(3_000)
        val starts: Int = backend.last.player.log.calls(NativeCall.START_PULSE).size
        val stops: Int = backend.last.player.log.calls(NativeCall.STOP_PULSE).size
        assertTrue(stops > 0, "the pulse never stopped during a 3 s stall, so the premise of the test does not hold: ${backend.last.player.log.tail(8)}")
        onFx { backend.last.player.forceStatus(NativeStatus.PLAYING) }
        advanceBy(50)
        assertTrue(backend.last.player.log.calls(NativeCall.START_PULSE).size > starts, "the pulse was not started again when the status turned PLAYING: ${backend.last.player.log.tail(8)}")
    }

    private companion object {
        const val BOUND_MS: Long = 10_000L
        const val NANOS_PER_MILLI: Long = 1_000_000L
    }
}
