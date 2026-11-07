package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.ChildHandle
import kotlinx.coroutines.ChildJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The SPI promises that [VideoPlaybackEngine.dispose] is the last call: "no method of the engine is
 * called after it". A prepare that was already past its released check when `release()` ran, and whose
 * caller is then cancelled, must not reach the engine either.
 *
 * The window between the check and the prepare registering itself is a handful of instructions, so the
 * test widens it exactly: the caller's own [Job] is a wrapper whose `attachChild` — the first thing
 * prepare does with it after the check — waits until the test has released the player and cancelled
 * the caller.
 */
@OptIn(ExperimentalAtomicApi::class, InternalCoroutinesApi::class)
@Suppress("DEPRECATION_ERROR", "DEPRECATION")
class VideoPlayerCancelledPrepareAfterReleaseAdversarialTest {

    private class CountingEngine : VideoPlaybackEngine {
        val calls: AtomicReference<List<String>> = AtomicReference(emptyList())
        val disposed: AtomicInt = AtomicInt(0)

        private fun record(call: String) {
            if (disposed.load() == 0) return
            while (true) {
                val seen: List<String> = calls.load()
                if (calls.compareAndSet(seen, seen + call)) return
            }
        }

        override fun setListener(listener: VideoPlaybackEngineListener?) = Unit
        override suspend fun load(source: VideoSource) = record("load")
        override fun start() = record("start")
        override fun pause() = record("pause")
        override fun seekTo(positionMs: Long) = record("seekTo")
        override fun setSpeed(speed: Float) = record("setSpeed")
        override fun setVolume(volume: Float) = record("setVolume")
        override fun setLooping(looping: Boolean) = record("setLooping")
        override fun durationMs(): Long = 1_000L
        override fun positionMs(): Long = 0L
        override fun bufferedPositionMs(): Long = 0L
        override fun release() = record("release")
        override fun dispose() {
            disposed.store(1)
        }
    }

    /** A caller job that holds `prepare` at its first use of the job until [proceed] is set. */
    private class HoldingJob(
        private val real: kotlinx.coroutines.CompletableJob,
        val entered: AtomicInt,
        val proceed: AtomicInt,
    ) : Job by real {
        // Delegation would answer every context lookup with `real` itself; the wrapper must be found.
        @Suppress("UNCHECKED_CAST")
        override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? =
            if (key == Job) this as E else null

        override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R = operation(initial, this)

        override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext =
            if (key == Job) EmptyCoroutineContext else this

        override fun attachChild(child: ChildJob): ChildHandle {
            entered.store(1)
            val mark = TimeSource.Monotonic.markNow()
            while (proceed.load() == 0 && mark.elapsedNow() < SPIN_LIMIT) Unit
            return real.attachChild(child)
        }
    }

    @Test
    fun `a prepare cancelled after release already ran does not call the engine after dispose`() =
        runTest(timeout = 30.seconds) {
            withContext(Dispatchers.Default) {
                withTimeout(20.seconds) {
                    val engine = CountingEngine()
                    val player: VideoPlayer = createVideoPlayer(engine, coroutineContext = Dispatchers.Default)
                    val real = Job()
                    val entered = AtomicInt(0)
                    val proceed = AtomicInt(0)
                    val caller = HoldingJob(real, entered, proceed)
                    val done = CompletableDeferred<Throwable?>()

                    val seenJob = AtomicReference<String?>(null)
                    val body: suspend () -> Unit = { seenJob.store(kotlinx.coroutines.currentCoroutineContext()[Job].toString() + " " + kotlinx.coroutines.currentCoroutineContext()[Job]?.let { it::class.simpleName }); player.prepare(VideoSource.Remote("https://example.test/a.mp4")) }
                    body.startCoroutine(Continuation(Dispatchers.Default + caller) { done.complete(it.exceptionOrNull()) })

                    var waited = 0
                    while (entered.load() == 0 && waited++ < 3_000) delay(1L)
                    assertEquals(1, entered.load(), "prepare never used the caller's job (job=${seenJob.load()} done=${done.isCompleted} ${if (done.isCompleted) done.await()?.stackTraceToString()?.take(600) else ""})")
                    // prepare is past its released check and about to register itself.
                    player.release()
                    real.cancel()
                    proceed.store(1)
                    val outcome: Throwable? = try {
                        withTimeout(5.seconds) { done.await() }
                    } catch (hang: kotlinx.coroutines.TimeoutCancellationException) {
                        throw AssertionError("prepare never returned after its caller was cancelled", hang)
                    }
                    assertTrue(outcome is kotlinx.coroutines.CancellationException, "outcome $outcome")

                    assertEquals(emptyList(), engine.calls.load(), "the engine was called after dispose")
                    assertTrue(player.stateFlow.value == VideoPlayerState.Idle, "state ${player.stateFlow.value}")
                }
            }
        }

    private companion object {
        val SPIN_LIMIT = 10.seconds
    }
}
