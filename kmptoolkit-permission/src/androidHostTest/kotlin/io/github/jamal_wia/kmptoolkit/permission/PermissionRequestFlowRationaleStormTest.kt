package io.github.jamal_wia.kmptoolkit.permission

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The rationale transitions of [PermissionRequestFlow] from real threads: an acknowledge and a dismiss
 * of the same rationale racing, with starts feeding the next rationale. Bounded in real time.
 */
class PermissionRequestFlowRationaleStormTest {

    private fun spinFor(nanos: Long) {
        val end: Long = System.nanoTime() + nanos
        while (System.nanoTime() < end) Thread.onSpinWait()
    }

    @Test
    fun `acknowledging and dismissing a rationale from real threads never leave a request in flight under another state`() = runBlocking {
        val controller = ScriptedPermissionController(
            status = PermissionStatus.Denied(explainFirst = true),
            requestOutcome = PermissionStatus.Denied(explainFirst = true),
        )
        val flow = PermissionRequestFlow(CommonPermission.Camera, controller)
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val stateMoved = AtomicInteger()
        val requests = AtomicInteger()
        controller.whileRequesting = {
            requests.incrementAndGet()
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
            spinFor(40_000L)
            if (flow.state.value != PermissionFlowState.Requesting) stateMoved.incrementAndGet()
            inFlight.decrementAndGet()
        }
        val stop = AtomicBoolean(false)

        val workers: List<Job> =
            List(1) { launch(Dispatchers.Default) { while (!stop.get()) flow.start() } } +
                List(2) { launch(Dispatchers.Default) { while (!stop.get()) flow.rationaleAcknowledged() } } +
                List(3) { launch(Dispatchers.Default) { while (!stop.get()) flow.rationaleDismissed() } }
        delay(3_000)
        stop.set(true)
        workers.forEach { withTimeout(5_000) { it.join() } }

        assertTrue(requests.get() > 100, "only ${requests.get()} requests ran, the storm did not bite")
        assertEquals(1, maxInFlight.get(), "a second request was started while one was in flight")
        assertEquals(0, stateMoved.get(), "the state left Requesting under a running request ${stateMoved.get()} times")
    }
}
