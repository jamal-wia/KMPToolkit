package io.github.jamal_wia.kmptoolkit.permission

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [PermissionRequestFlow] with callers that start, refresh and walk away at random moments on real
 * threads: the flow must never be left in `Requesting` once the callers are gone, and must still be
 * usable afterwards. Bounded in real time.
 */
class PermissionRequestFlowCancelStormTest {

    @Test
    fun `callers on real threads that start, refresh and are cancelled at random never leave the flow stuck in Requesting`() = runBlocking {
        val controller = ScriptedPermissionController(
            status = PermissionStatus.NotDetermined,
            requestOutcome = PermissionStatus.Denied(explainFirst = false),
        )
        val flow = PermissionRequestFlow(CommonPermission.Camera, controller)
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val requests = AtomicInteger()
        controller.whileRequesting = {
            requests.incrementAndGet()
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
            try {
                delay(1)
            } finally {
                inFlight.decrementAndGet()
            }
        }
        val stop = AtomicBoolean(false)

        val workers: List<Job> = List(6) { index ->
            launch(Dispatchers.Default) {
                val random = Random(index)
                while (!stop.get()) {
                    // A caller that gives up after a random time, as a screen that is left does.
                    withTimeoutOrNull(random.nextLong(1, 4)) {
                        if (random.nextInt(4) == 0) flow.refresh() else flow.start()
                    }
                }
            }
        }
        delay(3_000)
        stop.set(true)
        workers.forEach { withTimeout(5_000) { it.join() } }

        assertTrue(requests.get() > 100, "only ${requests.get()} requests ran, the storm did not bite")
        assertEquals(1, maxInFlight.get(), "a second request was in flight")
        assertNotEquals(PermissionFlowState.Requesting, flow.state.value, "the flow is stuck in Requesting with nobody left")
        val after: PermissionFlowState = withTimeout(5_000) { flow.start() }
        assertEquals(PermissionFlowState.Denied, after, "the flow is not usable after the storm")
    }
}
