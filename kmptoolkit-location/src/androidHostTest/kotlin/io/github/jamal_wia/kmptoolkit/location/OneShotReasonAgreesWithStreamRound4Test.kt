package io.github.jamal_wia.kmptoolkit.location

import android.location.LocationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Group loc-android (ticket 5.1.0), correction round 4 (M2, M3): the one-shot reason must agree with the stream when
 * every source the call asked refused the caller while another candidate of the row is only switched off
 * (PLAN D8 one mapper, D16, D19, 4.2: PERMISSION_MISSING only when every candidate refused). Virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class OneShotReasonAgreesWithStreamRound4Test {

    private val gps: String = LocationManager.GPS_PROVIDER
    private val network: String = LocationManager.NETWORK_PROVIDER
    private val passive: String = LocationManager.PASSIVE_PROVIDER
    private val serviceDisabled: LocationReading = LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED)
    private val permissionMissing: LocationReading = LocationReading.Unavailable(LocationUnavailability.PERMISSION_MISSING)

    // ---------------------------------------------------------------------------------------------
    // M2 / M3: one reason for the stream and the one-shot
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a one-shot on a coarse grant answers SERVICE_DISABLED in every accuracy when gps refuses and the network is off`() =
        runTest(timeout = 60.seconds) {
            for (accuracy: LocationAccuracy in LocationAccuracy.entries) {
                val fake = FakeLocationBackend(providers = setOf(gps, network, passive)).apply {
                    fine = false
                    coarse = true
                    refuse(gps)
                    refuse(passive)
                }
                fake.setProviderEnabled(network, false)
                val startedAt: Long = currentTime
                val reading: LocationReading = newProvider(fake).getCurrentLocation(
                    CurrentLocationOptions(accuracy = accuracy, maxAge = Duration.ZERO, timeout = 30.seconds),
                )
                assertEquals(serviceDisabled, reading, "$accuracy")
                assertEquals(startedAt, currentTime, "$accuracy: a known answer at once")
                assertEquals(0, fake.registeredCount, "$accuracy: a listener was left")
                assertEquals(0, fake.subscriberCount, "$accuracy: a subscription was left")
            }
        }

    @Test
    fun `a one-shot whose only present candidate refuses still answers PERMISSION_MISSING`() =
        runTest(timeout = 60.seconds) {
            // Every candidate of the row refused and none is merely off: the grant is what is missing.
            val fake = FakeLocationBackend(providers = setOf(gps)).apply { refuse(gps) }
            val reading: LocationReading = newProvider(fake).getCurrentLocation(
                CurrentLocationOptions(accuracy = LocationAccuracy.HIGH_ACCURACY, maxAge = Duration.ZERO, timeout = 30.seconds),
            )
            assertEquals(permissionMissing, reading)
            assertEquals(0L, currentTime)
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }

    @Test
    fun `an explicit source that refuses answers PERMISSION_MISSING and is not hidden by an off network`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(gps, network)).apply {
                fine = false
                coarse = true
                refuse(gps)
            }
            fake.setProviderEnabled(network, false)
            val reading: LocationReading = newProvider(fake).getCurrentLocation(
                CurrentLocationOptions(
                    accuracy = LocationAccuracy.HIGH_ACCURACY,
                    maxAge = Duration.ZERO,
                    timeout = 30.seconds,
                    platform = AndroidCurrentLocationOptions(source = AndroidLocationSource.GPS),
                ),
            )
            assertEquals(permissionMissing, reading, "one candidate, and it refused: the grant is missing")
            assertEquals(0, fake.registeredCount)
            assertEquals(0, fake.subscriberCount)
        }

    @Test
    fun `a one-shot that turned SERVICE_DISABLED serves the network on the next call once it is back`() =
        runTest(timeout = 60.seconds) {
            val fake = FakeLocationBackend(providers = setOf(network, passive)).apply {
                fine = false
                coarse = true
                refuse(passive)
            }
            fake.setProviderEnabled(network, false)
            val options = CurrentLocationOptions(accuracy = LocationAccuracy.LOW_POWER, maxAge = Duration.ZERO, timeout = 30.seconds)
            assertEquals(serviceDisabled, newProvider(fake).getCurrentLocation(options))

            fake.setProviderEnabled(network, true)
            fake.setCached(network, fake.locationAt(network, 41.0, ageMillis = 0))
            val reading: LocationReading = newProvider(fake).getCurrentLocation(options.copy(maxAge = 10.seconds))
            assertEquals(GeoCoordinates(41.0, 2.0), reading.fixOrNull()?.coordinates, "$reading")
            assertEquals(0, fake.registeredCount)
        }
}
