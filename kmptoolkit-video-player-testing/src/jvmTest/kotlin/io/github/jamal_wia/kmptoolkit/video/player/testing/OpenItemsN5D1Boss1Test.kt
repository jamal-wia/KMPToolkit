package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * D1 boss round 1: item N5 of `round-11-fixer3.md` (finding B4). `04-api-reference.md`: "Honors cancellation — when the
 * calling coroutine is cancelled, ... the state returns to Idle, and CancellationException propagates" and "If another
 * method throws anyway, the player still settles": a cancelled prepare whose engine's `release()` throws ends in `Idle`
 * and its caller gets the cancellation, with the engine's exception attached as suppressed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenItemsN5D1Boss1Test {

    private val rig: InlineRigD1R11 = InlineRigD1R11()
    private val next: VideoSource = VideoSource.Remote("https://example.test/b.mp4")

    @AfterTest
    fun tearDown() {
        rig.scope.cancel()
        runCatching { rig.player.release() }
    }

    @Test
    fun `N5 a cancelled prepare whose engine release throws still settles and the caller sees the cancellation`() {
        rig.prepareNow()
        var releases = 0
        // The release at the start of the next prepare works; the one on its cancel path throws.
        rig.engine.onRelease = { if (++releases == 2) throw IllegalStateException("release refused") }
        rig.engine.loadAction = { awaitCancellation() }
        var thrown: Throwable? = null
        val caller: Job = rig.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                rig.player.prepare(next)
            } catch (caught: Throwable) {
                thrown = caught
            }
        }
        assertEquals(VideoPlayerState.Preparing, rig.player.stateFlow.value)
        runBlocking { caller.cancelAndJoin() }
        assertEquals(2, releases, "the cancel path released the engine")
        assertEquals(VideoPlayerState.Idle, rig.player.stateFlow.value, "a cancelled prepare did not settle; caller got $thrown")
        assertTrue(thrown is CancellationException, "the caller received $thrown instead of the cancellation")
    }

    @Test
    fun `N5 the cancellation the caller receives carries the engine's release failure as suppressed`() {
        rig.prepareNow()
        var releases = 0
        val refused: IllegalStateException = IllegalStateException("release refused")
        rig.engine.onRelease = { if (++releases == 2) throw refused }
        rig.engine.loadAction = { awaitCancellation() }
        var thrown: Throwable? = null
        val caller: Job = rig.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                rig.player.prepare(next)
            } catch (caught: Throwable) {
                thrown = caught
            }
        }
        runBlocking { caller.cancelAndJoin() }
        val received: Throwable? = thrown
        assertTrue(received is CancellationException, "the caller received $received instead of the cancellation")
        assertTrue(
            received.suppressed.any { it === refused },
            "the engine's exception is attached to the cancellation as suppressed: ${received.suppressed.toList()}",
        )
        assertEquals(VideoPlayerState.Idle, rig.player.stateFlow.value)
        assertEquals(emptyList(), rig.escaped)
    }
}
