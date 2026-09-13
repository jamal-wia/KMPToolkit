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
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Creates the iOS [Accelerometer], on top of Core Motion.
 *
 * Core Motion reports acceleration in **g**, not m/s², so each sample is scaled before it leaves
 * this implementation — consumers see one unit on every platform. No permission, entitlement, or
 * `Info.plist` entry is involved on iOS. There is also nothing to release up front: the instance
 * holds a `CMMotionManager`, starts its updates when the first collection of
 * [Accelerometer.observe] begins, and stops them when the last one ends.
 *
 * @param samplingInterval how often Core Motion is asked to report, passed to
 *   `CMMotionManager.accelerometerUpdateInterval` as seconds. The default, 200 ms, matches the
 *   Android factory's default so both platforms report at roughly the same rate out of the box. A
 *   zero or negative interval asks for the fastest rate Core Motion offers.
 */
public fun createAccelerometer(samplingInterval: Duration = 200.milliseconds): Accelerometer =
    IosAccelerometer(samplingInterval, CoreMotionSource(), ::onMainQueue)

/**
 * The slice of `CMMotionManager` this module drives, so the fan-out above it can be tested without
 * Core Motion — which the simulator does not have.
 */
internal interface MotionSource {

    val isAvailable: Boolean

    /** Starts updates, replacing any running handler — Core Motion allows exactly one. */
    fun start(intervalSeconds: Double, onSample: (AccelerometerSample) -> Unit)

    fun stop()
}

/**
 * iOS [Accelerometer]: one Core Motion update stream per instance, fanned out to every collector.
 *
 * `CMMotionManager` supports a single update handler, and `stopAccelerometerUpdates` stops it for
 * everyone. Starting and stopping per collection therefore breaks the moment two collections
 * overlap: the second start replaces the first handler, and the first collection ending stops the
 * stream the second one is still reading. So collections register here instead; updates start
 * with the first and stop with the last, and each sample is handed to all of them.
 *
 * The collector list is only touched inside [confine] — the main queue in production, which is
 * also the queue Core Motion delivers on — so registration, removal and delivery never race.
 */
internal class IosAccelerometer(
    private val samplingInterval: Duration,
    private val source: MotionSource,
    private val confine: (() -> Unit) -> Unit,
) : Accelerometer {

    private val collectors: MutableList<(AccelerometerSample) -> Unit> = mutableListOf()

    override val isAvailable: Boolean get() = source.isAvailable

    override fun observe(): Flow<AccelerometerSample> = callbackFlow {
        if (!source.isAvailable) {
            // No hardware: stay silent rather than invent readings — isAvailable is the flag to
            // check.
            awaitClose { }
            return@callbackFlow
        }

        val collector: (AccelerometerSample) -> Unit = { sample: AccelerometerSample -> trySend(sample) }
        confine {
            collectors += collector
            if (collectors.size == 1) {
                source.start(samplingInterval.coerceAtLeast(Duration.ZERO).toDouble(DurationUnit.SECONDS)) {
                    sample: AccelerometerSample ->
                    // A snapshot: a collector may be removed while this sample is being delivered.
                    collectors.toList().forEach { deliver: (AccelerometerSample) -> Unit -> deliver(sample) }
                }
            }
        }

        awaitClose {
            confine {
                collectors -= collector
                if (collectors.isEmpty()) source.stop()
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class CoreMotionSource : MotionSource {

    private val motionManager: CMMotionManager = CMMotionManager()

    override val isAvailable: Boolean get() = motionManager.accelerometerAvailable

    override fun start(intervalSeconds: Double, onSample: (AccelerometerSample) -> Unit) {
        motionManager.accelerometerUpdateInterval = intervalSeconds
        motionManager.startAccelerometerUpdatesToQueue(
            queue = NSOperationQueue.mainQueue,
            withHandler = { data: CMAccelerometerData?, _: NSError? ->
                data?.acceleration?.useContents {
                    onSample(
                        AccelerometerSample(
                            x = x.toFloat() * GRAVITY,
                            y = y.toFloat() * GRAVITY,
                            z = z.toFloat() * GRAVITY,
                        ),
                    )
                }
            },
        )
    }

    override fun stop() {
        motionManager.stopAccelerometerUpdates()
    }

    private companion object {
        /** Core Motion reports g; the shared unit is m/s². */
        const val GRAVITY: Float = 9.81f
    }
}

private fun onMainQueue(block: () -> Unit) {
    dispatch_async(dispatch_get_main_queue(), block)
}
