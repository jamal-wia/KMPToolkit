@file:OptIn(ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.flow.Flow

/*
 * The iOS heading provider without CoreLocation: what one collection does with the events of one manager,
 * written against three small seams so it is tested without a device (the simulator has no compass and a
 * headless run cannot be authorized). The real CoreLocation half, with the strong delegate, is in
 * HeadingProvider.ios.kt.
 */

/**
 * What one collection hears from its manager, on whichever thread CoreLocation uses (the main queue). Every
 * method must return quickly and never throw into CoreLocation.
 */
internal interface IosHeadingEvents {

    /**
     * A heading update with CoreLocation's raw values: a negative [trueHeading] means it is not valid (no
     * location updates run, or not authorized), a negative [headingAccuracy] that the heading itself is
     * not valid (the compass needs calibration).
     */
    fun onHeading(magneticHeading: Double, trueHeading: Double, headingAccuracy: Double)

    /**
     * The authorization may have changed (CoreLocation also reports it once when the delegate is installed). It
     * carries no value on purpose: the collection reads [IosHeadingManager.isAuthorized] when it acts, so a status
     * that changed again before it got to act is never answered with a stale one.
     */
    fun onAuthorizationChanged()

    /** CoreLocation asks whether to show its calibration overlay; the answer is what it is told. */
    fun onCalibrationRequested(): Boolean
}

/**
 * One `CLLocationManager` and its strong delegate, serving exactly one collection. Used only inside the
 * provider's confine (the main queue), never from two threads.
 */
internal interface IosHeadingManager {

    /** Whether location is authorized (always or when in use). */
    val isAuthorized: Boolean

    /** `startUpdatingHeading`. */
    fun startHeading()

    /** `startUpdatingLocation` at `kCLLocationAccuracyThreeKilometers`: `trueHeading` is valid only while this runs. */
    fun startLocation()

    /** `stopUpdatingLocation`. */
    fun stopLocation()

    /**
     * Stops heading and location updates, sets the delegate to `nil` and drops the strong reference to it,
     * in that order, so nothing is delivered afterwards and nothing is retained. Idempotent.
     */
    fun release()
}

/** The CoreLocation facts the provider needs and the factory of managers, a seam for tests. */
internal interface IosHeadingDevice {

    /** `CLLocationManager.headingAvailable()`. */
    val isHeadingAvailable: Boolean

    /**
     * A new manager with [events] as its delegate's sink, `headingOrientation` for [orientation] and
     * `headingFilter` [headingFilterDegrees] (`0.0` means every update). Called inside the confine.
     */
    fun openManager(
        events: IosHeadingEvents,
        orientation: ScreenRotation,
        headingFilterDegrees: Double,
    ): IosHeadingManager
}

/** The newest facts of one collection; see [IosHeadingProvider]. Immutable. */
internal data class IosHeadingState(
    val magneticDegrees: Double? = null,
    val trueDegrees: Double? = null,
    val accuracyDegrees: Double? = null,
    /**
     * Location is authorized, as the collection last read it inside the confine. True north is reported only
     * while this holds, whatever `trueHeading` CoreLocation still computes from a location cached before a
     * revocation.
     */
    val authorized: Boolean = false,
    /** The last heading reported a negative accuracy: CoreLocation says the heading is not valid. */
    val accuracyInvalid: Boolean = false,
    /** CoreLocation asked to display its calibration overlay and no valid heading has arrived since. */
    val calibrationRequested: Boolean = false,
)

/**
 * The iOS [HeadingProvider]: every collection owns one manager (opened, started, released inside [confine]),
 * because `headingOrientation` and `headingFilter` are per manager, so concurrent collectors are independent.
 *
 * - The manager is created on the main queue ([confine]): CoreLocation delivers on the run loop of the creating
 *   thread. A collection cancelled before that block runs never creates one.
 * - Teardown is fenced at once and carried out on the confine: from the moment [AutoCloseable.close] returns no
 *   event reaches the collection (the manager may run a moment longer, delivering to a closed fence), then the
 *   confine stops both updates, sets the delegate to `nil` and drops the strong reference (the known weakness of
 *   the 5.0.0 provider was a manager that outlived its flow; here it cannot deliver).
 * - Location updates run on the same manager only while authorized (`trueHeading` needs them), start when
 *   authorization arrives mid-collection and stop on revocation, never without authorization.
 * - Heading values are handed to the collection through [HeadingInputs] (never blocking, newest state only);
 *   the minimum-change filter is the common one, applied on top of the manager's own `headingFilter`.
 */
internal class IosHeadingProvider(
    private val device: IosHeadingDevice,
    private val showsSystemCalibration: Boolean,
    private val logger: Logger,
    private val confine: (() -> Unit) -> Unit,
) : HeadingProvider {

    override val isAvailable: Boolean get() = device.isHeadingAvailable

    override fun observeHeading(
        minChangeDegrees: Double,
        declinationAt: GeoCoordinates?,
        screenRotation: ScreenRotation,
    ): Flow<Heading> = headingStream(
        minChangeDegrees = minChangeDegrees,
        initial = { IosHeadingState() },
        compose = ::composeHeading,
        register = { inputs: HeadingInputs<IosHeadingState> ->
            // declinationAt cannot be used on iOS (there is no declination API): true north comes from
            // location updates on the heading manager, documented.
            if (!device.isHeadingAvailable) {
                null
            } else {
                Session(inputs, minChangeDegrees, screenRotation).also { session: Session -> session.open() }
            }
        },
    )

    private fun composeHeading(state: IosHeadingState): Heading? {
        val magnetic: Double = state.magneticDegrees ?: return null
        return Heading(
            magneticDegrees = magnetic,
            trueDegrees = if (state.authorized) state.trueDegrees else null,
            accuracyDegrees = state.accuracyDegrees,
            needsCalibration = state.accuracyInvalid || state.calibrationRequested,
        )
    }

    /** One collection: its fence, its manager and its events. */
    private inner class Session(
        private val inputs: HeadingInputs<IosHeadingState>,
        private val minChangeDegrees: Double,
        private val screenRotation: ScreenRotation,
    ) : IosHeadingEvents, AutoCloseable {

        /** Set on any thread by [close]; every event and every queued step checks it first. */
        private val closed = AtomicBoolean(false)

        // Both only touched inside the confine.
        private var manager: IosHeadingManager? = null
        private var locationRunning: Boolean = false

        /** Queues the creation on the confine; a collection closed before it runs creates nothing. */
        fun open() {
            confine {
                if (closed.load()) return@confine
                val opened: IosHeadingManager = try {
                    device.openManager(this, screenRotation, minChangeDegrees)
                } catch (failure: Exception) {
                    // Nothing to release: the manager was never handed back. The stream stays silent.
                    logger.logSafely(LogLevel.WARN, failure) { "Could not open a heading manager" }
                    return@confine
                }
                manager = opened
                try {
                    opened.startHeading()
                    val authorized: Boolean = opened.isAuthorized
                    setAuthorized(authorized)
                    if (authorized) startLocation(opened)
                } catch (failure: Exception) {
                    // Release first, report after: whatever the logger does, no manager stays running.
                    releaseManager()
                    logger.logSafely(LogLevel.WARN, failure) { "Could not start heading updates" }
                }
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            confine { releaseManager() }
        }

        override fun onHeading(magneticHeading: Double, trueHeading: Double, headingAccuracy: Double) {
            if (closed.load()) return
            // A heading that has no magnetic direction is not a heading; the rest is optional by nature.
            if (!magneticHeading.isFinite() || magneticHeading < 0.0) return
            val magnetic: Double = normalizeDegrees(magneticHeading)
            val trueDegrees: Double? = if (trueHeading.isFinite() && trueHeading >= 0.0) normalizeDegrees(trueHeading) else null
            val accuracyValid: Boolean = headingAccuracy.isFinite() && headingAccuracy >= 0.0
            inputs.update { state: IosHeadingState ->
                state.copy(
                    magneticDegrees = magnetic,
                    trueDegrees = trueDegrees,
                    accuracyDegrees = if (accuracyValid) headingAccuracy else null,
                    accuracyInvalid = !accuracyValid,
                    // A valid heading means the compass is calibrated again.
                    calibrationRequested = if (accuracyValid) false else state.calibrationRequested,
                )
            }
        }

        override fun onAuthorizationChanged() {
            if (closed.load()) return
            confine {
                val current: IosHeadingManager = manager ?: return@confine
                if (closed.load()) return@confine
                try {
                    val authorized: Boolean = current.isAuthorized
                    setAuthorized(authorized)
                    if (authorized && !locationRunning) startLocation(current)
                    if (!authorized && locationRunning) stopLocation(current)
                } catch (failure: Exception) {
                    logger.logSafely(LogLevel.WARN, failure) { "Could not change the heading location updates" }
                }
            }
        }

        override fun onCalibrationRequested(): Boolean {
            if (!closed.load()) {
                inputs.update { state: IosHeadingState -> if (state.calibrationRequested) state else state.copy(calibrationRequested = true) }
            }
            return showsSystemCalibration
        }

        /**
         * Publishes what the confine just read. Losing authorization also forgets the true north of the last
         * heading, so regaining it never shows a stale one before CoreLocation delivers a fresh heading.
         */
        private fun setAuthorized(authorized: Boolean) {
            inputs.update { state: IosHeadingState ->
                when {
                    state.authorized == authorized -> state
                    authorized -> state.copy(authorized = true)
                    else -> state.copy(authorized = false, trueDegrees = null)
                }
            }
        }

        private fun startLocation(current: IosHeadingManager) {
            current.startLocation()
            locationRunning = true
        }

        private fun stopLocation(current: IosHeadingManager) {
            current.stopLocation()
            locationRunning = false
        }

        /** Inside the confine only. The reference is dropped first so a failing release cannot be repeated. */
        private fun releaseManager() {
            val current: IosHeadingManager = manager ?: return
            manager = null
            locationRunning = false
            try {
                current.release()
            } catch (failure: Exception) {
                logger.logSafely(LogLevel.WARN, failure) { "Could not release a heading manager" }
            }
        }
    }
}
