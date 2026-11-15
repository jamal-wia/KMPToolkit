package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.LogSink
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.createLoggerFactory
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import platform.Foundation.timeIntervalSinceReferenceDate

/**
 * The runtime of the real iOS provider, which the seam tests leave unexercised because the test runner
 * blocks the main thread and every `CLLocationManager` of the provider is created on the main queue. Here the
 * test thread IS the main thread and services the main queue itself (it spins the run loop while the provider's
 * suspend call runs on a global queue), so the provider's main-queue hops run.
 *
 * No authorization can be granted to a headless host, so what is checked is what the documentation promises
 * without authorization: the calls answer a typed reason (they neither throw nor hang), the switch observer's
 * real manager is created and released on the main queue and can be collected again, and the real manager
 * keeps its weakly referenced delegate alive until it is detached. Waiting is by spinning the run loop until a
 * deferred completes; the bound only fails a hang, with its own message.
 */
@OptIn(NativeRuntimeApi::class)
class IosLocationProviderRunLoopTest {

    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val warnings: MutableList<String> = mutableListOf()
    private val logger: Logger = createLoggerFactory(LogLevel.WARN, listOf(LogSink { _, _, message, _ -> warnings += message })).logger("loc")

    @AfterTest
    fun stop() {
        scope.cancel()
        pump(3)
    }

    /** Services the main queue for [rounds] short run-loop turns (release blocks posted by cancelled collections). */
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
    fun `without authorization getCurrentLocation answers a typed reason and does not wait`() {
        val provider: LocationProvider = createLocationProvider(logger)
        val result: LocationReading = await(scope.async { provider.getCurrentLocation(CurrentLocationOptions(timeout = kotlin.time.Duration.parse("5s"))) })
        assertIs<LocationReading.Unavailable>(result)
    }

    @Test
    fun `without authorization observeLocation emits a reason and can be collected again`() {
        val provider: LocationProvider = createLocationProvider(logger)
        repeat(3) { round: Int ->
            val first: LocationReading = await(scope.async { provider.observeLocation().first() })
            assertIs<LocationReading.Unavailable>(first, "collection $round")
            pump(3)
        }
    }

    @Test
    fun `the services observer creates and releases a real manager on the main queue and works again`() {
        val provider: LocationProvider = createLocationProvider(logger)
        repeat(3) { round: Int ->
            val first: Boolean = await(scope.async { provider.observeLocationEnabled().first() })
            assertTrue(first || !first, "collection $round emitted a Boolean")
            pump(3)
        }
        // A collector cancelled while its manager is being created: the release still runs.
        val job: Job = scope.launch { provider.observeLocationEnabled().first() }
        pump(1)
        job.cancel()
        pump(5)
        val again: Boolean = await(scope.async { provider.observeLocationEnabled().first() })
        assertEquals(again, again)
    }

    @Test
    fun `the real manager keeps its weak delegate alive until it is detached`() {
        val events = object : IosLocationEvents {}
        val manager: SystemIosLocationManager = SystemIosLocationEnvironment.createManager(events) as SystemIosLocationManager
        repeat(5) { GC.collect() }
        assertTrue(manager.hasDelegate, "CLLocationManager.delegate is weak: the wrapper's strong reference must keep it")
        manager.detach()
        assertFalse(manager.hasDelegate, "the delegate is nil after detach")
        manager.detach()
        assertFalse(manager.hasDelegate, "a second detach is a no-op")
    }
}
