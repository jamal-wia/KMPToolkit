package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrame
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.javafx.FxPlayback
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * D2 round 15, Q5: survivors of the mutant sweep that no public-API test can reach, pinned at `FxPlayback` itself (the engine drops a
 * call once its session is closed, so only the playback sees the late call).
 *
 * - FX4: a volume that reaches the playback after `dispose()` is dropped (the native half of a disposed player is gone; the guard of
 *   `setVolume` is the same one every other setter has).
 */
@OptIn(ToolkitInternalApi::class)
class FxPlaybackDirectR15ModelTest {

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
    fun aVolumeThatReachesThePlaybackAfterDisposeIsDropped() = fxModel {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host))
        val playback: FxPlayback = open(RecordingSink(), backend)
        advanceBy(50)
        playback.setVolume(0.4f)
        advanceBy(10)
        assertEquals(0.4f.toDouble(), backend.last.player.volume, "a live playback takes a volume")
        playback.dispose()
        advanceBy(50)
        val before: Int = backend.last.player.log.calls(NativeCall.SET_VOLUME).size
        playback.setVolume(0.9f)
        advanceBy(50)
        assertEquals(before, backend.last.player.log.calls(NativeCall.SET_VOLUME).size, "a volume was written on a disposed player: ${backend.last.player.log.tail(6)}")
    }

    private companion object {
        const val BOUND_MS: Long = 10_000L
        const val NANOS_PER_MILLI: Long = 1_000_000L
    }
}
