package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout

/**
 * D1 round 11 fix (F1). `04-api-reference.md`, "Honors cancellation": a `CancellationException` that the engine throws on
 * its own while the load is still wanted (its own timeout, say) is a failure like any other: `Error` carrying that
 * exception, and `prepare` returns normally. Virtual time and a hand-driven scheduler: no thread, no sleep.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoadCancellationOfItsOwnFixD1R11Test {

    private val source: VideoSource = VideoSource.Remote("https://example.test/a.mp4")
    private val scheduler: TestCoroutineScheduler = TestCoroutineScheduler()

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

    @Test
    fun `a load that throws a CancellationException of its own while the caller is alive is a failure not a silent cancel`() {
        // A third party engine that wraps its platform wait in withTimeout reports a slow platform with exactly this.
        val engine = ScriptedEngineD1R11()
        withPlayer(engine) { player: VideoPlayer ->
            engine.loadAction = { withTimeout(0L) { } }
            var escaped: Throwable? = null
            try {
                player.prepareNow()
            } catch (thrown: Throwable) {
                escaped = thrown
            }
            assertNull(escaped, "04: prepare does not throw on failure; the caller was never cancelled, but got $escaped")
            assertTrue(player.stateFlow.value is VideoPlayerState.Error, "04: a failed load is Error, was ${player.stateFlow.value}")
        }
    }

    @Test
    fun `the Error carries the very TimeoutCancellationException of the engine and the next prepare reaches Ready`() {
        val engine = ScriptedEngineD1R11()
        withPlayer(engine) { player: VideoPlayer ->
            var thrownByEngine: TimeoutCancellationException? = null
            engine.loadAction = {
                try {
                    withTimeout(0L) { }
                } catch (timeout: TimeoutCancellationException) {
                    thrownByEngine = timeout
                    throw timeout
                }
            }
            player.prepareNow()
            val state: VideoPlayerState = player.stateFlow.value
            assertIs<VideoPlayerState.Error>(state, "was $state")
            assertSame(checkNotNull(thrownByEngine), state.cause, "04: the engine's own exception, unchanged")
            engine.loadAction = {}
            player.prepareNow()
            assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value, "the next prepare works after the failure")
        }
    }

    @Test
    fun `a caller that is cancelled mid load still gets its CancellationException and the player returns to Idle`() {
        // The other half of the rule: only a load nobody wants any more is a cancellation.
        val engine = ScriptedEngineD1R11()
        withPlayer(engine) { player: VideoPlayer ->
            val inLoad: CompletableDeferred<Unit> = CompletableDeferred()
            engine.loadAction = {
                inLoad.complete(Unit)
                awaitCancellation()
            }
            var thrown: Throwable? = null
            runBlocking {
                withTimeout(HANG_BOUND_MS) {
                    val job: Job = launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            player.prepare(source)
                        } catch (cancellation: CancellationException) {
                            thrown = cancellation
                            throw cancellation
                        }
                    }
                    inLoad.await()
                    job.cancel()
                    job.join()
                }
            }
            assertTrue(thrown is CancellationException, "04: the cancelled caller gets the CancellationException, was $thrown")
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value, "04: a cancelled prepare returns to Idle, not Error")
        }
    }

    private companion object {
        /** A hang bound in real time, never a wait for the code under test to make progress. */
        const val HANG_BOUND_MS: Long = 10_000L
    }
}
