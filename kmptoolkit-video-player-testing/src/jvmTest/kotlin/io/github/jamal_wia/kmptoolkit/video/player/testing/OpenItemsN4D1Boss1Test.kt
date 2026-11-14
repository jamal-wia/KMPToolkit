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
import kotlinx.coroutines.launch

/**
 * D1 boss round 1: item N4 of `round-11-fixer3.md`. `04-api-reference.md`: a load that a newer prepare replaces is
 * cancelled and the replaced call returns normally, unless its own caller was cancelled as well: then
 * `CancellationException` propagates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenItemsN4D1Boss1Test {

    private val rig: InlineRigD1R11 = InlineRigD1R11()
    private val next: VideoSource = VideoSource.Remote("https://example.test/b.mp4")

    @AfterTest
    fun tearDown() {
        rig.scope.cancel()
        runCatching { rig.player.release() }
    }

    @Test
    fun `N4 a replaced prepare whose own caller is cancelled too does not return normally`() {
        rig.prepareNow()
        rig.engine.loadAction = { awaitCancellation() }
        var outcome = "running"
        val queued = kotlinx.coroutines.CoroutineScope(Job() + kotlinx.coroutines.test.StandardTestDispatcher(rig.scheduler))
        val older: Job = queued.launch {
            outcome = try {
                rig.player.prepare(next)
                "returned"
            } catch (caught: CancellationException) {
                "cancelled"
            }
        }
        rig.scheduler.runCurrent()
        assertEquals(VideoPlayerState.Preparing, rig.player.stateFlow.value)
        rig.engine.loadAction = {}
        // A newer prepare replaces the older one; the older one's resumption is queued, and its caller is cancelled
        // before it runs.
        val newer: Job = rig.scope.launch(start = CoroutineStart.UNDISPATCHED) { rig.player.prepare(rig.source) }
        older.cancel()
        rig.scheduler.runCurrent()
        rig.scheduler.runCurrent()
        assertTrue(older.isCompleted && newer.isCompleted, "older ${older.isCompleted}, newer ${newer.isCompleted}")
        // The documented sentence (04: unless its own caller was cancelled as well): the replaced call threw.
        assertEquals("cancelled", outcome)
        assertEquals(VideoPlayerState.Ready(10_000L), rig.player.stateFlow.value)
    }
}
