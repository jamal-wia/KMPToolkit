package io.github.jamal_wia.kmptoolkit.location

import android.location.LocationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Group loc-android (ticket 5.1.0), correction round 3, breaker finding m1: `minUpdateDistanceMeters` is a
 * finite, non-negative Double, so a value above the Float range is valid, but as a Float it is Infinity and
 * the platform request builder rejects it. The platform only needs "far"; the common throttle applies the
 * exact Double. Virtual time, a scriptable backend, no sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class MinUpdateDistanceCapTest {

    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER

    @Test
    fun `a valid minUpdateDistanceMeters above the Float range reaches the platform request as the largest Float`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(gps, network))
            val job: Job = backgroundScope.launch {
                newProvider(fake).observeLocation(
                    LocationUpdatesOptions(accuracy = LocationAccuracy.HIGH_ACCURACY, minUpdateDistanceMeters = 1.0e39),
                ).collect { }
            }
            runCurrent()

            val requests: List<Float> = fake.registered().map { registration: FakeLocationBackend.Registration -> registration.request.minDistanceMeters }
            assertTrue(requests.isNotEmpty(), "control: the stream registered")
            assertTrue(requests.all { distance: Float -> distance == Float.MAX_VALUE }, "the request carries the largest finite Float: $requests")
            job.cancelAndJoin()
            assertEquals(0, fake.registeredCount)
        }
}
