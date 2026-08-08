package io.github.jamal_wia.kmptoolkit.haptics

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The iOS implementation reports nothing back about whether a device buzzed, so the only claims
 * worth testing are the ones the contract actually makes — and the risky one is threading: UIKit's
 * feedback generators must be touched on the main thread, and `perform` promises callers they need
 * not care which thread they are on.
 *
 * These tests therefore call `perform` from a background queue and then drain the main runloop, so
 * the generator work really executes. A test that only calls `perform` and asserts on its return
 * value would pass against a stub that does nothing.
 */
@OptIn(ExperimentalForeignApi::class)
class IosHapticFeedbackTest {

    @Test
    fun `a call from a background thread is accepted and its UIKit work runs on the main thread`() {
        // dispatch_async, not dispatch_sync: dispatch_sync runs the block on the *calling* thread,
        // so it would never leave main and the test would assert nothing about threading.
        val haptics: HapticFeedback = createHapticFeedback()
        val ranOffMain = AtomicInt(0)
        val finished = AtomicInt(0)
        val result = AtomicReference<HapticResult?>(null)

        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0uL)) {
            if (!NSThread.isMainThread) ranOffMain.value = 1
            result.value = haptics.perform(HapticType.SUCCESS)
            finished.value = 1
        }

        // Pumping the main runloop while waiting is what lets the block perform queued actually
        // run: the generator is created, prepared and fired on the main thread here. Reaching the
        // assertions means UIKit was touched on the right thread, which on a device would
        // otherwise fail only at runtime.
        assertTrue(waitUntil { finished.value == 1 }, "the background call never completed")
        assertEquals(1, ranOffMain.value, "the call under test must originate off the main thread")
        assertEquals(HapticResult.PERFORMED, result.value)
        assertTrue(drainMainQueue(), "the queued main-thread work did not run")
    }

    @Test
    fun `every haptic type survives the round trip through the main queue`() {
        val haptics: HapticFeedback = createHapticFeedback()

        HapticType.entries.forEach { type ->
            assertEquals(HapticResult.PERFORMED, haptics.perform(type), "type=$type")
        }

        assertTrue(drainMainQueue(), "the queued main-thread work did not run")
    }

    @Test
    fun `perform returns before the queued work has run`() {
        // Documented contract: PERFORMED means "handed to UIKit", not "already felt". A caller
        // must not use the return as a timing signal.
        val haptics: HapticFeedback = createHapticFeedback()
        var ranOnMain = false

        val result: HapticResult = haptics.perform(HapticType.LIGHT)
        dispatchOnMain { ranOnMain = true }

        assertEquals(HapticResult.PERFORMED, result)
        assertFalse(ranOnMain, "work queued on the main queue must not have run yet")
        drainMainQueue()
        assertTrue(ranOnMain)
    }

    @Test
    fun `repeated calls keep succeeding`() {
        val haptics: HapticFeedback = createHapticFeedback()

        repeat(5) { assertEquals(HapticResult.PERFORMED, haptics.perform(HapticType.SUCCESS)) }

        assertTrue(drainMainQueue())
    }

    @Test
    fun `the no-op implementation stays distinguishable from the real one`() {
        assertEquals(HapticResult.UNAVAILABLE, noOpHapticFeedback().perform(HapticType.LIGHT))
        assertEquals(HapticResult.PERFORMED, createHapticFeedback().perform(HapticType.LIGHT))

        drainMainQueue()
    }
}

/**
 * Runs the main runloop briefly so that blocks queued on the main queue execute, and reports
 * whether a sentinel block queued behind them actually ran.
 */
@OptIn(ExperimentalForeignApi::class)
private fun drainMainQueue(): Boolean {
    var drained = false
    dispatchOnMain { drained = true }
    NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(DRAIN_SECONDS))
    return drained
}

@OptIn(ExperimentalForeignApi::class)
private fun dispatchOnMain(block: () -> Unit) {
    dispatch_async(dispatch_get_main_queue()) { block() }
}

/**
 * Pumps the main runloop in short slices until [condition] holds, so that work queued on the main
 * queue by another thread gets a chance to run. Returns `false` if it never held.
 */
private fun waitUntil(condition: () -> Boolean): Boolean {
    var elapsed = 0.0
    while (elapsed < WAIT_TIMEOUT_SECONDS) {
        if (condition()) return true
        NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(SLICE_SECONDS))
        elapsed += SLICE_SECONDS
    }
    return condition()
}

private const val DRAIN_SECONDS: Double = 0.2
private const val SLICE_SECONDS: Double = 0.02
private const val WAIT_TIMEOUT_SECONDS: Double = 5.0
