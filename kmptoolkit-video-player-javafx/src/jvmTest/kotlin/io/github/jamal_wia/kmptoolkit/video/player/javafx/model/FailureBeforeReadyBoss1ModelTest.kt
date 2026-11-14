package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrame
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.javafx.FxPlayback
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoPlayerException
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
 * D2 boss 1, mutant K7 (`fail()` reporting a failure before READY to the sink as well as to the load): `FxPlayback.ready`
 * "completes once, with READY or the first error; later errors go to [FxPlayback.Sink.onFailed]". A source that fails
 * before it is ready fails the load with that error and reaches no listener as a playback failure: the core would
 * otherwise see one failure twice, once as the load's exception and once as `onFailed` while it is still preparing.
 */
@OptIn(ToolkitInternalApi::class)
class FailureBeforeReadyBoss1ModelTest {

    private class CountingSink : FxPlayback.Sink {
        val failed: MutableList<Throwable> = CopyOnWriteArrayList()
        override fun onTimes(durationMs: Long, positionMs: Long, bufferedMs: Long) = Unit
        override fun onSeekApplied() = Unit
        override fun onVideoSize(size: VideoSize?) = Unit
        override fun onBuffering(isBuffering: Boolean) = Unit
        override fun onCompleted() = Unit
        override fun onFailed(cause: Throwable) {
            failed += cause
        }
        override fun onFrame(frame: VideoFrame) = Unit
    }

    private fun failsBeforeReady(via: FailureVia) = fxModel {
        val cause = IllegalStateException("boss1 K7 failure before READY via $via")
        val backend: ComposedBackend = composeAll(ScriptedBackend(host, script = StatusScript().failAt(5.0, cause, via)))
        val sink = CountingSink()
        // timing-hygiene-ignore: TH2 bound the real-time hang bound of an open whose IO hop is real
        val deadline: Long = System.nanoTime() + BOUND_MS * 1_000_000L
        val thrown: Throwable? = runBlocking {
            val opening = async { runCatching { FxPlayback.open("scripted:${TestClip.ASSET}", sink, 30, 15_000L, backend, onCreated = {}) } }
            while (!opening.isCompleted) {
                host.runUntilIdle()
                advanceBy(1)
                yield()
                // timing-hygiene-ignore: TH2 bound the real-time hang bound of an open whose IO hop is real
                if (!opening.isCompleted && System.nanoTime() - deadline > 0L) {
                    opening.cancel()
                    throw AssertionError("open did not finish within $BOUND_MS ms of real time ($host)")
                }
                Thread.onSpinWait()
            }
            opening.await().exceptionOrNull()
        }
        advanceBy(50)
        // Coroutines may hand over a copy of the exception (stack trace recovery): compare the type and the message.
        assertEquals("${cause.javaClass.name}: ${cause.message}", thrown?.let { "${it.javaClass.name}: ${it.message}" }, "the load did not fail with the failure the source reported before READY: $thrown")
        assertEquals(emptyList(), sink.failed.toList(), "a failure that failed the load was also reported to the sink as a playback failure")
    }

    @Test
    fun aPlayerFailureBeforeReadyFailsTheLoadAndReachesNoListener() = failsBeforeReady(FailureVia.PLAYER)

    @Test
    fun aMediaFailureBeforeReadyFailsTheLoadAndReachesNoListener() = failsBeforeReady(FailureVia.MEDIA)


    /**
     * Mutant K12 (the dispose of the half-built player in `open`'s catch removed): a source that never reports READY fails
     * the load with `LoadTimedOut`, and "on failure or cancellation the half-built playback is disposed before this throws".
     */
    @Test
    fun aLoadThatTimesOutDisposesTheHalfBuiltPlayer() = fxModel {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host, script = StatusScript().neverReady()))
        // timing-hygiene-ignore: TH2 bound the real-time hang bound of an open whose IO hop is real
        val deadline: Long = System.nanoTime() + BOUND_MS * 1_000_000L
        val thrown: Throwable? = runBlocking {
            val opening = async { runCatching { FxPlayback.open("scripted:${TestClip.ASSET}", CountingSink(), 30, READY_TIMEOUT_MS, backend, onCreated = {}) } }
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
            opening.await().exceptionOrNull()
        }
        advanceBy(50)
        assertTrue(thrown is JavaFxVideoPlayerException.LoadTimedOut, "the load did not time out: $thrown")
        assertEquals(NativeStatus.DISPOSED, backend.last.player.status, "the half-built player of a timed-out load was not disposed: ${backend.last.player.log.tail(6)}")
    }

    private companion object {
        const val BOUND_MS: Long = 10_000L
        const val READY_TIMEOUT_MS: Long = 300L
    }
}
