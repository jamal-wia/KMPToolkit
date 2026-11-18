@file:OptIn(ExperimentalForeignApi::class)

package io.github.jamal_wia.kmptoolkit.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.CoreLocation.kCLErrorDenied
import platform.CoreLocation.kCLErrorDomain
import platform.CoreLocation.kCLErrorLocationUnknown
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970

/**
 * FixFieldMappingIosTest and the pure rules of PLAN 4.1 / 4.2 through the mapper seam: raw CoreLocation
 * numbers in, library types out. The version-gated values arrive as `null` (the system adapter reads
 * them behind a version check); the adapter itself is checked against a real `CLLocation`.
 */
class IosFixMappingTest {

    private val clock = FakeIosClock()

    @Test
    fun `every field of 4_1 is mapped`() {
        val fix: LocationFix = assertNotNull(
            rawFix(clock, age = 2.seconds, floorLevel = 3, isSimulated = true, isProducedByAccessory = true).toFixOrNull(isApproximate = false),
        )
        assertEquals(GeoCoordinates(21.4225, 39.8262), fix.coordinates)
        assertEquals(clock.wallMillis - 2_000L, fix.timestampEpochMillis)
        assertEquals(5.0, fix.horizontalAccuracyMeters)
        assertEquals(40.0, fix.ellipsoidalAltitudeMeters)
        assertEquals(12.5, fix.mslAltitudeMeters)
        assertEquals(3.0, fix.verticalAccuracyMeters)
        assertEquals(1.5, fix.speedMetersPerSecond)
        assertEquals(0.5, fix.speedAccuracyMetersPerSecond)
        assertEquals(90.0, fix.courseDegrees)
        assertEquals(4.0, fix.courseAccuracyDegrees)
        assertEquals(true, fix.isSimulated)
        assertEquals(false, fix.isApproximate)
        assertEquals(IosFixDetails(floorLevel = 3, isProducedByAccessory = true), fix.iosDetails)
    }

    @Test
    fun `a negative or non-finite horizontal accuracy or invalid coordinates drop the fix`() {
        assertNull(rawFix(clock, horizontalAccuracy = -1.0).toFixOrNull(false))
        assertNull(rawFix(clock, horizontalAccuracy = Double.NaN).toFixOrNull(false))
        assertNull(rawFix(clock, horizontalAccuracy = Double.POSITIVE_INFINITY).toFixOrNull(false))
        assertNull(rawFix(clock, latitude = 90.5).toFixOrNull(false))
        assertNull(rawFix(clock, longitude = Double.NaN).toFixOrNull(false))
        assertNotNull(rawFix(clock, horizontalAccuracy = 0.0).toFixOrNull(false), "zero is a valid accuracy")
    }

    @Test
    fun `a negative vertical accuracy nulls itself and both altitudes`() {
        val fix: LocationFix = assertNotNull(rawFix(clock, verticalAccuracy = -1.0).toFixOrNull(false))
        assertNull(fix.verticalAccuracyMeters)
        assertNull(fix.ellipsoidalAltitudeMeters)
        assertNull(fix.mslAltitudeMeters)
        assertEquals(5.0, fix.horizontalAccuracyMeters)
    }

    @Test
    fun `negative speed and course and accuracies are null`() {
        val fix: LocationFix = assertNotNull(
            rawFix(clock, speed = -1.0, speedAccuracy = -1.0, course = -1.0, courseAccuracy = -1.0).toFixOrNull(false),
        )
        assertNull(fix.speedMetersPerSecond)
        assertNull(fix.speedAccuracyMetersPerSecond)
        assertNull(fix.courseDegrees)
        assertNull(fix.courseAccuracyDegrees)
    }

    @Test
    fun `version gated values that the system does not have stay null and a negative altitude is kept`() {
        val fix: LocationFix = assertNotNull(
            rawFix(clock, ellipsoidalAltitude = null, courseAccuracy = null, isSimulated = null, isProducedByAccessory = null, altitude = -30.0)
                .toFixOrNull(isApproximate = null),
        )
        assertNull(fix.ellipsoidalAltitudeMeters)
        assertNull(fix.courseAccuracyDegrees)
        assertNull(fix.isSimulated)
        assertNull(fix.isApproximate)
        assertEquals(-30.0, fix.mslAltitudeMeters)
        assertEquals(IosFixDetails(null, null), fix.iosDetails)
    }

    @Test
    fun `a course of 360 degrees is folded into the half-open range`() {
        assertEquals(0.0, rawFix(clock, course = 360.0).toFixOrNull(false)?.courseDegrees)
        assertEquals(359.5, rawFix(clock, course = 359.5).toFixOrNull(false)?.courseDegrees)
    }

    @Test
    fun `iosDetails is null for a fix from another provider`() {
        val foreign = LocationFix(GeoCoordinates(0.0, 0.0), 0L)
        assertNull(foreign.iosDetails)
    }

    @Test
    fun `the authorization reasons of 4_2`() {
        assertEquals(LocationUnavailability.PERMISSION_MISSING, authorizationReason(kCLAuthorizationStatusNotDetermined, true))
        assertEquals(LocationUnavailability.PERMISSION_MISSING, authorizationReason(kCLAuthorizationStatusRestricted, true))
        assertEquals(LocationUnavailability.PERMISSION_MISSING, authorizationReason(kCLAuthorizationStatusDenied, true))
        assertEquals(LocationUnavailability.SERVICE_DISABLED, authorizationReason(kCLAuthorizationStatusDenied, false))
        assertEquals(LocationUnavailability.SERVICE_DISABLED, authorizationReason(kCLAuthorizationStatusAuthorizedWhenInUse, false))
        assertEquals(LocationUnavailability.SERVICE_DISABLED, authorizationReason(kCLAuthorizationStatusAuthorizedAlways, false))
        assertNull(authorizationReason(kCLAuthorizationStatusAuthorizedWhenInUse, true))
        assertNull(authorizationReason(kCLAuthorizationStatusAuthorizedAlways, true))
    }

    @Test
    fun `failures are classified by domain and code`() {
        assertEquals(IosFailureKind.LOCATION_UNKNOWN, classifyLocationFailure(kCLErrorDomain, kCLErrorLocationUnknown))
        assertEquals(IosFailureKind.DENIED, classifyLocationFailure(kCLErrorDomain, kCLErrorDenied))
        assertEquals(IosFailureKind.OTHER, classifyLocationFailure(kCLErrorDomain, 2L))
        assertEquals(IosFailureKind.OTHER, classifyLocationFailure("other.domain", kCLErrorLocationUnknown), "the code means something only in CoreLocation's domain")
        assertEquals(IosFailureKind.OTHER, classifyLocationFailure(null, kCLErrorDenied))
    }

    @Test
    fun `ages are captured at delivery and advance on the uptime and not the wall clock`() {
        val delivery = IosDelivery(
            fix = assertNotNull(rawFix(clock).toFixOrNull(false)),
            ageAtDelivery = clock.wallAgeOf(rawFix(clock, age = 3.seconds)),
            uptimeAtDelivery = clock.uptime(),
        )
        assertEquals(3.seconds, delivery.ageAt(clock.uptime()))
        clock.advance(10.seconds)
        clock.jumpWall(5.hours)
        assertEquals(13.seconds, delivery.ageAt(clock.uptime()))
        assertEquals(3.seconds, delivery.ageAt(delivery.uptimeAtDelivery - 1.hours), "an uptime that stepped back is no time")
        assertEquals(0.milliseconds, clock.wallAgeOf(rawFix(clock, age = (-5).seconds)), "a stamp from the future is zero")
    }

    @Test
    fun `the system adapter reads a real CLLocation`() {
        val stamp: NSDate = NSDate.dateWithTimeIntervalSince1970(1_700_000_000.0)
        val location = CLLocation(
            coordinate = CLLocationCoordinate2DMake(21.5, 39.25),
            altitude = 10.0,
            horizontalAccuracy = 7.0,
            verticalAccuracy = 2.0,
            course = 45.0,
            courseAccuracy = 3.0,
            speed = 2.0,
            speedAccuracy = 1.0,
            timestamp = stamp,
        )
        val raw: IosRawFix = location.toRawFix()
        assertEquals(21.5, raw.latitude)
        assertEquals(39.25, raw.longitude)
        assertEquals(1_700_000_000_000L, raw.timestampEpochMillis)
        assertEquals(7.0, raw.horizontalAccuracy)
        assertEquals(10.0, raw.altitude)
        assertEquals(45.0, raw.course)
        assertEquals(2.0, raw.speed)
        assertNull(raw.floorLevel)
        val fix: LocationFix = assertNotNull(raw.toFixOrNull(false))
        assertEquals(7.0, fix.horizontalAccuracyMeters)
    }
}
