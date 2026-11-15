package io.github.jamal_wia.kmptoolkit.location

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The coarse-only grant (docs 05-platform-notes) through the real backend at the API levels where the
 * platform's location rules moved (29: background restriction, 31: `LocationRequest` and the approximate
 * choice, 33, 34): fixes read `isApproximate == true`, and the listener books are kept: every listener a
 * request registers is removed again, after a cancelled collection and after a single-fix timeout,
 * however often the cycle repeats.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29, 31, 33, 34])
class ApproximateOnlyApiMatrixTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager: LocationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val network: String = LocationManager.NETWORK_PROVIDER

    @Suppress("DEPRECATION")
    private fun listeners(): Int = shadowOf(manager).getRequestLocationUpdateListeners().size

    private fun arrange(coarseOnly: Boolean): LocationProvider {
        val permissions: Array<String> =
            if (coarseOnly) arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
            else arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(application).grantPermissions(*permissions)
        shadowOf(manager).setProviderEnabled(network, true)
        return createLocationProvider(application)
    }

    private fun networkFix(): Location = Location(network).apply {
        latitude = 41.0
        longitude = 2.0
        time = 1_700_000_000_000L
        elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
        accuracy = 2000f
    }

    @Test
    fun `a coarse-only stream is seeded from the cache and a live fix reads approximate`() = runTest(timeout = 60.seconds) {
        val provider: LocationProvider = arrange(coarseOnly = true)
        shadowOf(manager).setLastKnownLocation(network, networkFix())
        val seen: MutableList<LocationReading> = mutableListOf()

        val job: Job = backgroundScope.launch { provider.observeLocation().collect { reading: LocationReading -> seen += reading } }
        runCurrent()
        assertEquals(true, seen.first().fixOrNull()?.isApproximate, "the seeded cached fix, coarse-only")

        shadowOf(manager).simulateLocation(networkFix())
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertEquals(true, seen.last().fixOrNull()?.isApproximate, "the live fix, coarse-only")
        job.cancelAndJoin()
    }

    @Test
    fun `a fine grant reads precise at the same levels`() = runTest(timeout = 60.seconds) {
        val provider: LocationProvider = arrange(coarseOnly = false)
        shadowOf(manager).setLastKnownLocation(network, networkFix())

        assertEquals(false, provider.getCurrentLocation().fixOrNull()?.isApproximate)
    }

    @Test
    fun `two hundred collect and cancel cycles leave no listener registered`() = runTest(timeout = 120.seconds) {
        val provider: LocationProvider = arrange(coarseOnly = true)
        repeat(200) { cycle: Int ->
            val job: Job = backgroundScope.launch { provider.observeLocation().collect { } }
            runCurrent()
            if (cycle == 0) assertEquals(true, listeners() > 0, "control: the collection registered a listener")
            job.cancelAndJoin()
        }
        assertEquals(0, listeners(), "listeners left registered")
    }

    @Test
    fun `fifty single fix timeouts leave no listener registered and answer NO_FIX`() = runTest(timeout = 120.seconds) {
        val provider: LocationProvider = arrange(coarseOnly = true)
        repeat(50) { round: Int ->
            val call = async { provider.getCurrentLocation(CurrentLocationOptions(maxAge = kotlin.time.Duration.ZERO, timeout = 20.milliseconds)) }
            runCurrent()
            assertEquals(true, call.isActive, "control: round $round is pending before the timeout")
            advanceTimeBy(20.milliseconds)
            runCurrent()
            assertEquals(LocationReading.Unavailable(LocationUnavailability.NO_FIX), call.await())
        }
        assertEquals(0, listeners(), "listeners left registered")
    }
}
