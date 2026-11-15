package io.github.jamal_wia.kmptoolkit.location

import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith

/**
 * Defect class: a collector that is not served for a while. While every worker of `Dispatchers.Default`
 * is blocked (on a latch the test holds, with a real-time bound) fixes keep arriving from a plain
 * thread; when the workers come back the collector must end on the newest fix whatever the delay was
 * (the stream is conflated, a slow collector never ends on a stale position), and a collection
 * cancelled while it is starved must still release its listeners and its service subscription once its
 * dispatcher runs again. Each round is a re-init on the same backend: the previous collection must have
 * released everything first. Waits are flow values and latches with a bound, never a sleep.
 */
@RunWith(AndroidJUnit4::class)
class ObserveLocationStarvedCollectorTest {

    private val gps: String = LocationManager.GPS_PROVIDER

    /** HIGH_ACCURACY registers gps and network: two listeners and one service subscription per collection. */
    private val twoProviders = LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY)

    /** The fake plus a hook that fires after the service subscription (the last step of a registration). */
    private class SubscribingBackend(val fake: FakeLocationBackend) : LocationBackend by fake {
        @Volatile var onSubscribed: () -> Unit = {}

        override fun observeServiceChanges(onChange: () -> Unit): () -> Unit {
            val unsubscribe: () -> Unit = fake.observeServiceChanges(onChange)
            onSubscribed()
            return unsubscribe
        }
    }

    private fun providerOver(backend: LocationBackend): LocationProvider = AndroidLocationProvider(
        context = ApplicationProvider.getApplicationContext<Context>(),
        logger = NoopLogger,
        systemScreenLauncher = SystemScreenLauncher.SeparateTask,
        backend = backend,
    )

    private fun LocationReading.latitudeOrNull(): Double? = fixOrNull()?.coordinates?.latitude

    /**
     * Blocks every worker of `Dispatchers.Default` until [release] is counted down (each blocker waits at
     * most a minute, so a failed test cannot leave the pool blocked), and returns once all are blocked.
     */
    private suspend fun CoroutineScope.starveDefault(release: CountDownLatch): List<Job> {
        val workers: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val blocked = CountDownLatch(workers)
        val blockers: List<Job> = List(workers) {
            launch(Dispatchers.Default) {
                blocked.countDown()
                release.await(60, TimeUnit.SECONDS)
            }
        }
        withContext(Dispatchers.IO) {
            assertTrue(blocked.await(20, TimeUnit.SECONDS), "the default dispatcher could not be starved")
        }
        return blockers
    }

    @Test
    fun `a collector whose dispatcher was starved ends on the newest fix once it is served`() = runBlocking {
        withTimeout(HANG_BOUND_MS) {
            val hooked = SubscribingBackend(FakeLocationBackend())
            val backend: FakeLocationBackend = hooked.fake
            val provider: LocationProvider = providerOver(hooked)
            repeat(ROUNDS) { round: Int ->
                assertEquals(0, backend.registeredCount, "round $round: the previous collection left a listener")
                assertEquals(0, backend.subscriberCount, "round $round: the previous collection left a subscription")
                val subscribed = CountDownLatch(1)
                hooked.onSubscribed = { subscribed.countDown() }
                val firstSeen = CompletableDeferred<Unit>()
                val gate = CompletableDeferred<Unit>()
                val last = MutableStateFlow<Double?>(null)
                val job = launch(Dispatchers.Default) {
                    provider.observeLocation(twoProviders).collect { reading: LocationReading ->
                        last.value = reading.latitudeOrNull()
                        // The first value (the seed) is taken, then the collector stays busy until the gate opens.
                        if (firstSeen.complete(Unit)) gate.await()
                    }
                }
                firstSeen.await()
                withContext(Dispatchers.IO) {
                    assertTrue(subscribed.await(20, TimeUnit.SECONDS), "round $round: the collection never finished registering")
                }
                assertEquals(2, backend.registeredCount, "round $round: control, the collection holds gps and network")
                assertEquals(1, backend.subscriberCount, "round $round: control, the collection holds its subscription")

                val release = CountDownLatch(1)
                var blockers: List<Job> = emptyList()
                try {
                    blockers = starveDefault(release)
                    // 400 fixes arrive from a plain thread (the platform) while nothing on Default can run.
                    val platformThread: Thread = thread { repeat(FIXES) { i: Int -> backend.deliver(gps, backend.locationAt(gps, i * STEP)) } }
                    platformThread.join(20_000)
                    assertTrue(!platformThread.isAlive, "round $round: the delivering thread hung")
                    gate.complete(Unit)
                } finally {
                    release.countDown()
                }
                blockers.joinAll()

                val reached: Double? = withTimeoutOrNull(20_000) { last.first { it == NEWEST } }
                assertNotNull(reached, "round $round: the newest fix never reached the collector, it ended on ${last.value}")
                job.cancelAndJoin()
                assertEquals(0, backend.registeredCount, "round $round: a listener is left")
                assertEquals(0, backend.subscriberCount, "round $round: a subscription is left")
                assertEquals(NEWEST, last.value, "round $round: ended on a stale position")
            }
        }
    }

    @Test
    fun `a collection cancelled while its dispatcher is starved releases everything once it runs again`() = runBlocking {
        withTimeout(HANG_BOUND_MS) {
            repeat(ROUNDS) { round: Int ->
                val hooked = SubscribingBackend(FakeLocationBackend())
                val backend: FakeLocationBackend = hooked.fake
                val subscribed = CountDownLatch(1)
                hooked.onSubscribed = { subscribed.countDown() }
                val seeded = CompletableDeferred<Unit>()
                val job = launch(Dispatchers.Default) {
                    providerOver(hooked).observeLocation(twoProviders).collect { seeded.complete(Unit) }
                }
                seeded.await()
                withContext(Dispatchers.IO) {
                    assertTrue(subscribed.await(20, TimeUnit.SECONDS), "round $round: the collection never finished registering")
                }
                assertEquals(2, backend.registeredCount, "round $round: control, the collection holds gps and network")
                assertEquals(1, backend.subscriberCount, "round $round: control, the collection holds its subscription")

                val release = CountDownLatch(1)
                var blockers: List<Job> = emptyList()
                try {
                    blockers = starveDefault(release)
                    job.cancel()
                } finally {
                    release.countDown()
                }
                blockers.joinAll()
                job.join()

                assertEquals(0, backend.registeredCount, "round $round: a listener leaked through the starved cancel")
                assertEquals(0, backend.subscriberCount, "round $round: a subscription leaked through the starved cancel")

                // Re-init on the same backend after the starved cancel: a new collection is registered and served.
                val last = MutableStateFlow<Double?>(null)
                val subscribedAgain = CountDownLatch(1)
                hooked.onSubscribed = { subscribedAgain.countDown() }
                val again = launch(Dispatchers.Default) {
                    providerOver(hooked).observeLocation(twoProviders).collect { reading: LocationReading -> last.value = reading.latitudeOrNull() }
                }
                withContext(Dispatchers.IO) {
                    assertTrue(subscribedAgain.await(20, TimeUnit.SECONDS), "round $round: the new collection never finished registering")
                }
                backend.deliver(gps, backend.locationAt(gps, 7.0))
                assertNotNull(withTimeoutOrNull(20_000) { last.first { it == 7.0 } }, "round $round: the new collection was not served")
                again.cancelAndJoin()
                assertEquals(0, backend.registeredCount, "round $round: the new collection left a listener")
                assertEquals(0, backend.subscriberCount, "round $round: the new collection left a subscription")
            }
        }
    }

    private companion object {
        const val ROUNDS: Int = 5
        const val FIXES: Int = 400
        const val STEP: Double = 0.2
        val NEWEST: Double = (FIXES - 1) * STEP

        /** A hang bound only: nothing waits on it, a healthy run is far below it. */
        const val HANG_BOUND_MS: Long = 120_000
    }
}
