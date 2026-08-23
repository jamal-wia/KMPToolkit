package io.github.jamal_wia.kmptoolkit.accelerometer

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.CoreMotion.CMAccelerometerData
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSError
import platform.Foundation.NSOperationQueue

/**
 * Creates the iOS [Accelerometer], on top of Core Motion.
 *
 * Core Motion reports acceleration in **g**, not m/s², so each sample is scaled before it leaves
 * this implementation — consumers see one unit on every platform. No permission, entitlement, or
 * `Info.plist` entry is involved on iOS. There is also nothing to release up front: the instance
 * holds a `CMMotionManager` and starts/stops updates per collection of [Accelerometer.observe].
 *
 * @param samplingInterval how often Core Motion is asked to report, passed straight to
 *   `CMMotionManager.accelerometerUpdateInterval` as seconds. The default, 200 ms, matches the
 *   Android factory's default so both platforms report at roughly the same rate out of the box.
 */
@OptIn(ExperimentalForeignApi::class)
public fun createAccelerometer(samplingInterval: Duration = 200.milliseconds): Accelerometer =
    IosAccelerometer(samplingInterval)

@OptIn(ExperimentalForeignApi::class)
internal class IosAccelerometer(private val samplingInterval: Duration) : Accelerometer {

    private val motionManager: CMMotionManager = CMMotionManager()

    override val isAvailable: Boolean get() = motionManager.accelerometerAvailable

    override fun observe(): Flow<AccelerometerSample> = callbackFlow {
        if (!motionManager.accelerometerAvailable) {
            // No hardware: stay silent rather than invent readings — isAvailable is the flag to
            // check.
            awaitClose { }
            return@callbackFlow
        }

        motionManager.accelerometerUpdateInterval = samplingInterval.toDouble(DurationUnit.SECONDS)
        motionManager.startAccelerometerUpdatesToQueue(
            queue = NSOperationQueue.mainQueue,
            withHandler = { data: CMAccelerometerData?, _: NSError? ->
                if (data != null) {
                    data.acceleration.useContents {
                        trySend(
                            AccelerometerSample(
                                x = x.toFloat() * GRAVITY,
                                y = y.toFloat() * GRAVITY,
                                z = z.toFloat() * GRAVITY,
                            ),
                        )
                    }
                }
            },
        )

        awaitClose { motionManager.stopAccelerometerUpdates() }
    }

    private companion object {
        /** Core Motion reports g; the shared unit is m/s². */
        const val GRAVITY: Float = 9.81f
    }
}
