package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.LogSink
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.createLoggerFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import platform.Foundation.timeIntervalSinceReferenceDate

/**
 * The runtime of the iOS provider, which `IosLocationProviderTest` leaves unexercised because the test runner
 * blocks the main thread and every `CLLocationManager` of the provider is created on the main queue. Here the
 * test thread IS the main thread and services the main queue itself (it spins the run loop while the provider's
 * suspend call runs on a global queue), so the provider's `dispatch_async(main)` blocks run.
 *
 * No authorization can be granted to a headless host, so what is checked is what the documentation promises
 * without authorization: `getCurrentLocation` resolves to `null` and `observeLocation` emits `null`, neither
 * throws or hangs, a cancelled request cleans up on the main queue and a new request works afterwards.
 * Waiting is by spinning the run loop until a deferred completes; the bound only fails a hang, with its own message.
 */
class IosLocationProviderRunLoopTest {

    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val warnings: MutableList<String> = mutableListOf()
    private val logger: Logger = createLoggerFactory(LogLevel.WARN, listOf(LogSink { _, _, message, _ -> synchronizedAdd(message) })).logger("loc")

    private fun synchronizedAdd(message: String) {
        // Called from the main queue only (the delegate callbacks), read after the pump returns.
        warnings += message
    }

    @AfterTest
    fun stop() {
        scope.cancel()
        pump(3)
    }

    /** Services the main queue for [rounds] short run-loop turns (cleanup blocks posted by cancelled requests). */
    private fun pump(rounds: Int) {
        repeat(rounds) { NSRunLoop.currentRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate.dateWithTimeIntervalSinceNow(0.02)) }
    }

    private fun <T> await(work: Deferred<T>, boundSeconds: Double = 60.0): T {
        // timing-hygiene-ignore: TH2 bound the clock only fails a hang with its own message, it measures nothing
        val deadline: Double = NSDate().timeIntervalSinceReferenceDate + boundSeconds
        while (!work.isCompleted) {
            // timing-hygiene-ignore: TH2 bound see above
            check(NSDate().timeIntervalSinceReferenceDate < deadline) { "the provider call did not finish within the hang bound; warnings=$warnings" }
            NSRunLoop.currentRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate.dateWithTimeIntervalSinceNow(0.05))
        }
        @Suppress("OPT_IN_USAGE")
        return work.getCompleted()
    }

    @Test
    fun `the test thread is the main thread that the provider posts to`() {
        assertTrue(NSThread.isMainThread, "the harness assumption of this class: tests run on the main thread")
    }

    @Test
    fun `without authorization getCurrentLocation resolves to null and the main queue serviced the request`() {
        val provider: LocationProvider = createLocationProvider(LocationProviderConfig(singleFixTimeoutMillis = 5_000), logger)
        val result: GeoCoordinates? = await(scope.async { provider.getCurrentLocation() })
        assertNull(result)
        println("R8-LOC getCurrentLocation ended with warnings=$warnings")
    }

    @Test
    fun `without authorization observeLocation emits a null seed and can be collected again after cancellation`() {
        val provider: LocationProvider = createLocationProvider(LocationProviderConfig(), logger)
        repeat(3) { round ->
            val first: GeoCoordinates? = await(scope.async { provider.observeLocation().first() })
            assertNull(first, "collection $round")
            pump(3)
        }
    }

    @Test
    fun `a request cancelled while it waits on the main queue ends cleanly and the next request still works`() {
        val provider: LocationProvider = createLocationProvider(LocationProviderConfig(singleFixTimeoutMillis = 3_000), logger)
        val job: Deferred<GeoCoordinates?> = scope.async { provider.getCurrentLocation() }
        pump(2)
        job.cancel()
        pump(3)
        assertTrue(job.isCancelled || job.isCompleted, "the cancelled request is still pending")
        val next: GeoCoordinates? = await(scope.async { provider.getCurrentLocation() })
        assertNull(next)
    }

    @Test
    fun `a caller timeout shorter than the single fix cap returns without leaving the provider unusable`() {
        val provider: LocationProvider = createLocationProvider(LocationProviderConfig(singleFixTimeoutMillis = 30_000), logger)
        val result: GeoCoordinates? = await(scope.async { withTimeoutOrNull(300) { provider.getCurrentLocation() } })
        assertNull(result)
        pump(3)
        val flowFirst: GeoCoordinates? = await(scope.async { provider.observeLocation().first() })
        assertEquals(null, flowFirst)
    }
}
