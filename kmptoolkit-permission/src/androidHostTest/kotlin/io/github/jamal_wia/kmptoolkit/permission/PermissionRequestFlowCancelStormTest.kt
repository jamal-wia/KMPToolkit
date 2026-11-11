package io.github.jamal_wia.kmptoolkit.permission

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * [PermissionRequestFlow] with callers that start, refresh and walk away at random moments on real
 * threads: the flow must never be left in `Requesting` once the callers are gone, and must still be
 * usable afterwards. Bounded in real time.
 *
 * The workers run on a pool with one thread per worker, not on `Dispatchers.Default`: while a
 * request is in flight, `start()` and `refresh()` return without suspending, so a worker that polls
 * them holds its thread for as long as the request lasts. On a pool smaller than the worker count
 * (the default dispatcher on a 3-core CI runner) the pollers would hold every thread and starve the
 * request owner's own resumption, and the storm would measure the host's core count instead of the
 * flow. The storm is still real threads; each worker also yields after every attempt so a
 * time-sliced host hands the owner its turn promptly.
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
        val startedAt: Long = System.nanoTime()

        Executors.newFixedThreadPool(WORKERS).asCoroutineDispatcher().use { pool ->
            val workers: List<Job> = List(WORKERS) { index ->
                launch(pool) {
                    val random = Random(index)
                    while (!stop.get()) {
                        // A caller that gives up after a random time, as a screen that is left does.
                        withTimeoutOrNull(random.nextLong(1, 4)) {
                            if (random.nextInt(4) == 0) flow.refresh() else flow.start()
                        }
                        yield()
                    }
                }
            }
            // Until the work is done, not until a wall-clock time: the storm is judged by how many
            // requests it got through, and the deadline only bounds a run that cannot.
            while (requests.get() < TARGET_REQUESTS && elapsedMillis(startedAt) < DEADLINE_MILLIS) delay(10)
            stop.set(true)
            workers.forEach { withTimeout(5_000) { it.join() } }
        }

        val elapsed: Long = elapsedMillis(startedAt)
        val cores: Int = Runtime.getRuntime().availableProcessors()
        assertTrue(
            requests.get() >= TARGET_REQUESTS,
            "only ${requests.get()} of $TARGET_REQUESTS requests ran in $elapsed ms " +
                "(maxInFlight=${maxInFlight.get()}, availableProcessors=$cores), the storm did not bite",
        )
        assertEquals(
            1,
            maxInFlight.get(),
            "a second request was in flight (requests=${requests.get()}, elapsed=$elapsed ms, availableProcessors=$cores)",
        )
        assertNotEquals(
            PermissionFlowState.Requesting,
            flow.state.value,
            "the flow is stuck in Requesting with nobody left (state=${flow.state.value}, requests=${requests.get()}, elapsed=$elapsed ms)",
        )
        val after: PermissionFlowState = withTimeout(5_000) { flow.start() }
        assertEquals(
            PermissionFlowState.Denied,
            after,
            "the flow is not usable after the storm (state=${flow.state.value}, requests=${requests.get()}, elapsed=$elapsed ms)",
        )
    }

    private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    private companion object {
        const val WORKERS: Int = 6
        const val TARGET_REQUESTS: Int = 500
        const val DEADLINE_MILLIS: Long = 20_000
    }
}
