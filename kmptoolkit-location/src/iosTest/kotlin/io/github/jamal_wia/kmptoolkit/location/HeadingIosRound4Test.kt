@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.location

import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Group loc-heading, round 4: the iOS session's own cleanup when a failure is already in flight (finding m1 of
 * breaker round 3, checked in the provider's start and release paths).
 *
 * The rule, the same one the Android provider's undo follows: whatever fails while a manager is being started,
 * the manager is released before anything leaves; an `Error` is not absorbed (not here, not in the logger), but
 * with an inline confine the start IS the registration, and a thrown registration has no handle for the stream
 * to release, so the session releases it itself. A release that itself throws ends the call with its throwable
 * and carries the interrupted failure as suppressed.
 *
 * Single-threaded and deterministic: inline or queued confine, unconfined collector. No sleeps, no wall clock.
 */
class HeadingIosRound4Test {

    private val device = FakeHeadingDevice()
    private val logger = RecordingLogger()

    private fun provider(confine: (() -> Unit) -> Unit = inlineConfine): HeadingProvider = IosHeadingProvider(device, false, logger, confine)

    /** Collects [flow] until it ends; [ended] gets whatever the collecting coroutine caught (cancellation included). */
    private fun TestScope.collectUntilEnd(flow: Flow<Heading>, ended: MutableList<Throwable>): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                flow.collect { }
            } catch (thrown: Throwable) {
                ended += thrown
            }
        }

    @Test
    fun `an Error from startHeading with an inline confine releases the manager and leaves unchanged`() = runTest {
        val startError = AssertionError("startUpdatingHeading failed with an Error")
        device.startHeadingFailure = startError
        val ended: MutableList<Throwable> = mutableListOf()

        collectUntilEnd(provider().observeHeading(), ended).join()

        assertEquals(1, device.openCalls.load(), "control: a manager was opened")
        assertEquals(1, device.allManagers.single().releases.load(), "the manager is released exactly once before the Error leaves")
        assertTrue(device.liveManagers.isEmpty(), "no manager stays running")
        assertSame(startError, ended.single(), "the Error is not absorbed")
    }

    @Test
    fun `an Error from startHeading and then from the release ends with the release Error and carries the first as suppressed`() = runTest {
        val startError = AssertionError("startUpdatingHeading failed with an Error")
        val releaseError = LinkageError("the release failed with an Error")
        device.startHeadingFailure = startError
        device.releaseFailure = releaseError
        val ended: MutableList<Throwable> = mutableListOf()

        collectUntilEnd(provider().observeHeading(), ended).join()

        assertEquals(1, device.allManagers.single().releases.load(), "control: the release was attempted exactly once")
        assertTrue(device.liveManagers.isEmpty(), "the manager is gone from the platform whatever the release throws")
        assertSame(releaseError, ended.single(), "the release's Error ends the call")
        assertTrue(releaseError.suppressedExceptions.any { it === startError }, "the interrupted start failure is attached")
    }

    @Test
    fun `a release that throws the start failure's own instance is not suppressed into itself`() = runTest {
        val shared = LinkageError("thrown by the start and again by the release")
        device.startHeadingFailure = shared
        device.releaseFailure = shared
        val ended: MutableList<Throwable> = mutableListOf()

        collectUntilEnd(provider().observeHeading(), ended).join()

        assertEquals(1, device.allManagers.single().releases.load(), "control: the release was attempted")
        assertSame(shared, ended.single(), "the one throwable, unchanged: a self-suppression would throw IllegalArgumentException")
        assertTrue(shared.suppressedExceptions.isEmpty(), "nothing attached to itself")
    }

    @Test
    fun `with a queued confine an Error from startHeading leaves the drain after the manager was released and a later close adds nothing`() = runTest {
        val startError = AssertionError("startUpdatingHeading failed with an Error")
        device.startHeadingFailure = startError
        val queue = DeferredConfine()
        val ended: MutableList<Throwable> = mutableListOf()
        val collection: Job = collectUntilEnd(provider(queue.confine).observeHeading(), ended)
        assertEquals(1, queue.pending, "control: the creation is queued")

        val leaving: AssertionError = assertFailsWith<AssertionError> { queue.drain() }

        assertSame(startError, leaving, "the Error leaves the confine unchanged")
        assertEquals(1, device.allManagers.single().releases.load(), "the manager was released before it left")
        collection.cancelAndJoin()
        queue.drain()
        assertEquals(1, device.allManagers.single().releases.load(), "closing the collection afterwards releases nothing twice")
        assertTrue(device.liveManagers.isEmpty(), "no manager stays running")
    }

    @Test
    fun `an Error from the release of a running manager ends the cancelled collection with the cancellation attached`() = runTest {
        val releaseError = LinkageError("the release failed with an Error")
        device.releaseFailure = releaseError
        val ended: MutableList<Throwable> = mutableListOf()
        val collection: Job = collectUntilEnd(provider().observeHeading(), ended)
        assertEquals(1, device.liveManagers.size, "control: a manager is running")

        collection.cancelAndJoin()

        assertEquals(1, device.allManagers.single().releases.load(), "released exactly once")
        assertTrue(device.liveManagers.isEmpty(), "the manager is gone from the platform")
        assertSame(releaseError, ended.single(), "the Error is not hidden behind the cancellation")
        assertTrue(releaseError.suppressedExceptions.isNotEmpty(), "the cancellation that ended the collection is attached")
    }

    @Test
    fun `an exception from startHeading is still released and logged and the stream stays open`() = runTest {
        device.startHeadingFailure = IllegalStateException("startUpdatingHeading failed")
        val ended: MutableList<Throwable> = mutableListOf()

        val collection: Job = collectUntilEnd(provider().observeHeading(), ended)

        assertEquals(1, device.allManagers.single().releases.load(), "released exactly once")
        assertTrue(device.liveManagers.isEmpty(), "no manager stays running")
        assertEquals(emptyList(), ended, "an exception is absorbed: the collection stays open and silent")
        assertTrue(logger.messages.any { it.second.contains("Could not start heading updates") }, "the failure is logged")
        collection.cancelAndJoin()
        assertEquals(1, device.allManagers.single().releases.load(), "closing releases nothing twice")
    }
}
