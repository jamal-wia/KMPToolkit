package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.fail
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The player's promise that every transition is atomic, whichever thread it arrives on — see the
 * threading note on [VideoPlayer] and the listener rules on [VideoPlaybackEngine].
 *
 * The first group is deterministic: an engine that reports an event synchronously from inside a call
 * the player is making. The second uses real threads (`Dispatchers.Default`) against an engine whose
 * position read is deliberately slow, to hold each race window open: a poll tick, an end of media, a
 * pause and a release colliding. Every one of these could leave the old player stuck in `Playing`,
 * or free the engine twice.
 */
@OptIn(ExperimentalAtomicApi::class)
class VideoPlayerThreadingTest {

    private val source: VideoSource = VideoSource.Remote("https://example.test/clip.mp4")

    // --- Events reported from inside an engine call ---

    @Test
    fun `a failure reported from inside start is not overwritten by play`() = runTest {
        val failure = IllegalStateException("no decoder")
        val engine = RecordingVideoPlaybackEngine(duration = 10_000L)
        val hooked = HookedEngine(engine)
        val player: VideoPlayer = createVideoPlayer(
            engine = hooked,
            config = VideoPlayerConfig(positionUpdateIntervalMs = 100L),
            coroutineContext = StandardTestDispatcher(testScheduler),
        )
        try {
            player.prepare(source)
            hooked.onStart = { requireNotNull(engine.listener).onFailed(failure) }

            player.play()

            val state: VideoPlayerState = player.stateFlow.value
            assertIs<VideoPlayerState.Error>(state)
            assertSame(failure, state.cause)
            // And the polling play() started was stopped by the failure, not left running.
            engine.position = 4_000L
            advanceTimeBy(1_000L)
            assertIs<VideoPlayerState.Error>(player.stateFlow.value)
            assertEquals(0L, player.playbackPositionFlow.value)
        } finally {
            // Always: a playing player polls forever on the test scheduler otherwise.
            player.release()
        }
    }

    @Test
    fun `an end reported from inside the restart of repeat mode one lands after the restart`() = runTest {
        val engine = RecordingVideoPlaybackEngine(duration = 10_000L)
        val hooked = HookedEngine(engine)
        val player: VideoPlayer = createVideoPlayer(
            engine = hooked,
            config = VideoPlayerConfig(positionUpdateIntervalMs = 100L),
            coroutineContext = StandardTestDispatcher(testScheduler),
        )
        try {
            player.prepare(source)
            player.play()
            player.setRepeatMode(RepeatMode.One)
            // The platform posts the end as the loop wraps; restarting, it reports the end again just as
            // the listener switches repeat off — all synchronously, from inside the restart's start().
            hooked.onStart = {
                hooked.onStart = null
                player.setRepeatMode(RepeatMode.Off)
                requireNotNull(engine.listener).onCompleted()
            }

            requireNotNull(engine.listener).onCompleted()

            // The second end is applied after the restart, not inside it and then overwritten by it.
            assertEquals(VideoPlayerState.Completed(10_000L), player.stateFlow.value)
            assertEquals(10_000L, player.playbackPositionFlow.value)
            assertEquals(2, engine.started)
            assertEquals(listOf(0L), engine.seekTargets)
            // Completed stops polling: a later tick must not resurrect Playing.
            engine.position = 3_000L
            advanceTimeBy(1_000L)
            assertEquals(VideoPlayerState.Completed(10_000L), player.stateFlow.value)
        } finally {
            // Always: a playing player polls forever on the test scheduler otherwise.
            player.release()
        }
    }

    @Test
    fun `a player call made from inside a transition runs at once instead of deadlocking`() = runTest {
        val engine = RecordingVideoPlaybackEngine(duration = 10_000L)
        val hooked = HookedEngine(engine)
        val player: VideoPlayer = createVideoPlayer(
            engine = hooked,
            coroutineContext = StandardTestDispatcher(testScheduler),
        )
        try {
            player.prepare(source)
            player.play()
            // Something the pause sets off — a collector running unconfined, say — calls the player
            // back on the same thread, in the middle of the pause.
            hooked.onPause = { player.setVolume(0.5f) }

            player.pause()

            assertIs<VideoPlayerState.Paused>(player.stateFlow.value)
            assertEquals(0.5f, player.volumeFlow.value)
            assertEquals(0.5f, engine.appliedVolume)
        } finally {
            // Always: a playing player polls forever on the test scheduler otherwise.
            player.release()
        }
    }

    // --- Real threads ---

    @Test
    fun `an end of media racing the poll always settles on completed`() = runTest(timeout = 120.seconds) {
        withContext(Dispatchers.Default) {
            repeat(ITERATIONS) { iteration: Int ->
                val engine = ConcurrentEngine(duration = 10_000L)
                val player: VideoPlayer = newRealTimePlayer(engine)
                player.prepare(source)
                player.play()
                // Let the poll get going, then end the media from another thread at a varying moment.
                delay((iteration % 3).milliseconds)
                launch(Dispatchers.Default) { engine.reportCompleted() }.join()

                awaitState(player) { it is VideoPlayerState.Completed }
                delay(SETTLE)
                assertEquals(VideoPlayerState.Completed(10_000L), player.stateFlow.value, "iteration $iteration")
                assertEquals(10_000L, player.playbackPositionFlow.value, "iteration $iteration")
                player.release()
            }
        }
    }

    @Test
    fun `a pause racing the poll always settles on paused at the paused position`() = runTest(timeout = 120.seconds) {
        withContext(Dispatchers.Default) {
            repeat(ITERATIONS) { iteration: Int ->
                val engine = ConcurrentEngine(duration = 10_000L)
                val player: VideoPlayer = newRealTimePlayer(engine)
                player.prepare(source)
                player.play()
                delay((iteration % 3).milliseconds)
                launch(Dispatchers.Default) { player.pause() }.join()

                // pause() returns with its transition applied, and no tick in flight overwrites it.
                val paused: VideoPlayerState = player.stateFlow.value
                assertIs<VideoPlayerState.Paused>(paused, "iteration $iteration")
                delay(SETTLE)
                assertEquals(paused, player.stateFlow.value, "iteration $iteration")
                assertEquals(paused.currentPosition, player.playbackPositionFlow.value, "iteration $iteration")
                player.release()
            }
        }
    }

    @Test
    fun `release racing transport and engine events frees the engine exactly once`() = runTest(timeout = 120.seconds) {
        withContext(Dispatchers.Default) {
            repeat(ITERATIONS) { iteration: Int ->
                val engine = ConcurrentEngine(duration = 10_000L)
                val player: VideoPlayer = newRealTimePlayer(engine)
                player.prepare(source)
                player.play()

                val stop = AtomicBoolean(false)
                val workers: List<Job> = listOf(
                    hammer(stop) { step: Int ->
                        when (step % 6) {
                            0 -> player.play()
                            1 -> player.pause()
                            2 -> player.seekTo(step * 10L)
                            3 -> player.setVolume((step % 10) / 10f)
                            4 -> player.setRepeatMode(if (step % 2 == 0) RepeatMode.One else RepeatMode.Off)
                            else -> player.replay()
                        }
                    },
                    hammer(stop) { step: Int ->
                        when (step % 4) {
                            0 -> engine.reportCompleted()
                            1 -> engine.reportBuffering(step % 8 == 1)
                            2 -> engine.reportVideoSize(VideoSize(16, 9))
                            else -> engine.reportFailed(IllegalStateException("glitch $step"))
                        }
                    },
                )
                delay((iteration % 3).milliseconds)
                // Several threads release at once; exactly one may free the engine.
                List(RELEASERS) { launch(Dispatchers.Default) { player.release() } }.joinAll()
                delay(1L)
                stop.store(true)
                workers.joinAll()

                assertEquals(1, engine.releases.load(), "iteration $iteration: engine releases")
                assertEquals(1, engine.disposals.load(), "iteration $iteration: engine disposals")
                assertEquals(0, engine.callsAfterDispose.load(), "iteration $iteration: calls after dispose")
                assertEquals(0, engine.overlappingCalls.load(), "iteration $iteration: overlapping calls")
                assertEquals(VideoPlayerState.Idle, player.stateFlow.value, "iteration $iteration")
                assertEquals(0L, player.playbackPositionFlow.value, "iteration $iteration")
                assertEquals(null, player.videoSizeFlow.value, "iteration $iteration")
                assertEquals(false, player.isBufferingFlow.value, "iteration $iteration")
            }
        }
    }

    private fun newRealTimePlayer(engine: ConcurrentEngine): VideoPlayer = createVideoPlayer(
        engine = engine,
        config = VideoPlayerConfig(positionUpdateIntervalMs = 1L),
        coroutineContext = Dispatchers.Default,
    )

    /** Runs [action] with an increasing step on a pool thread until [stop] is set. */
    private fun CoroutineScope.hammer(stop: AtomicBoolean, action: (Int) -> Unit): Job =
        launch(Dispatchers.Default) {
            var step = 0
            while (!stop.load() && isActive) action(step++)
        }

    private suspend fun awaitState(player: VideoPlayer, condition: (VideoPlayerState) -> Boolean) {
        val deadline = TimeSource.Monotonic.markNow() + 5.seconds
        while (!condition(player.stateFlow.value)) {
            if (deadline.hasPassedNow()) fail("stuck in ${player.stateFlow.value}")
            delay(1L)
        }
    }

    /** Delegates to a [RecordingVideoPlaybackEngine], running a hook from inside some calls. */
    private class HookedEngine(private val delegate: RecordingVideoPlaybackEngine) : VideoPlaybackEngine by delegate {
        var onStart: (() -> Unit)? = null
        var onPause: (() -> Unit)? = null

        override fun start() {
            delegate.start()
            onStart?.invoke()
        }

        override fun pause() {
            delegate.pause()
            onPause?.invoke()
        }
    }

    /**
     * A thread-safe engine for the real-thread tests. Its position read is slow on purpose — a poll
     * tick then spans a real window another thread can land in — and it counts what matters: frees,
     * calls after the final dispose, and calls that overlapped another call.
     */
    private class ConcurrentEngine(private val duration: Long) : VideoPlaybackEngine, DisposableVideoPlaybackEngine {
        private val listener: AtomicReference<VideoPlaybackEngineListener?> = AtomicReference(null)
        private val position: AtomicLong = AtomicLong(0L)
        private val inCall: AtomicInt = AtomicInt(0)
        private val disposed: AtomicBoolean = AtomicBoolean(false)

        val releases: AtomicInt = AtomicInt(0)
        val disposals: AtomicInt = AtomicInt(0)
        val callsAfterDispose: AtomicInt = AtomicInt(0)
        val overlappingCalls: AtomicInt = AtomicInt(0)

        override fun setListener(listener: VideoPlaybackEngineListener?) {
            this.listener.store(listener)
        }

        override suspend fun load(source: VideoSource) = Unit

        override fun start() = call { }

        override fun pause() = call { }

        override fun seekTo(positionMs: Long) = call { position.store(positionMs) }

        override fun setSpeed(speed: Float) = call { }

        override fun setVolume(volume: Float) = call { }

        override fun setLooping(looping: Boolean) = call { }

        override fun durationMs(): Long = duration

        override fun positionMs(): Long {
            var result = 0L
            call {
                spin()
                result = position.incrementAndFetch() % duration
            }
            return result
        }

        override fun bufferedPositionMs(): Long = 0L

        override fun release() = call { releases.incrementAndFetch() }

        override fun dispose() = call {
            disposed.store(true)
            disposals.incrementAndFetch()
        }

        fun reportCompleted() {
            listener.load()?.onCompleted()
        }

        fun reportFailed(cause: Throwable) {
            listener.load()?.onFailed(cause)
        }

        fun reportBuffering(isBuffering: Boolean) {
            listener.load()?.onBufferingChanged(isBuffering)
        }

        fun reportVideoSize(size: VideoSize) {
            listener.load()?.onVideoSizeChanged(size)
        }

        private inline fun call(block: () -> Unit) {
            if (disposed.load()) callsAfterDispose.incrementAndFetch()
            if (inCall.incrementAndFetch() > 1) overlappingCalls.incrementAndFetch()
            try {
                block()
            } finally {
                inCall.decrementAndFetch()
            }
        }

        /** About 50 µs of busy work: long enough to hold a race window open, short enough to loop. */
        private fun spin() {
            val start = TimeSource.Monotonic.markNow()
            while (start.elapsedNow() < SPIN) {
                // Busy-wait on purpose.
            }
        }
    }

    private companion object {
        const val ITERATIONS: Int = 60
        const val RELEASERS: Int = 3
        val SETTLE = 10.milliseconds
        val SPIN = 50.microseconds
    }
}
