package io.github.jamal_wia.kmptoolkit.accelerometer.testing

import io.github.jamal_wia.kmptoolkit.accelerometer.Accelerometer
import io.github.jamal_wia.kmptoolkit.accelerometer.AccelerometerSample
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * An [Accelerometer] a test drives by hand: no device, no simulator, a scripted sequence of
 * readings instead of whatever the physical world happens to produce.
 *
 * ```kotlin
 * val accelerometer = ScriptedAccelerometer(
 *     samples = listOf(
 *         AccelerometerSample(x = 0f, y = 0f, z = 9.8f),
 *         AccelerometerSample(x = 0f, y = 0f, z = -9.8f),
 *     ),
 * )
 * val readings = accelerometer.observe().take(2).toList()
 * assertEquals(FaceDown, classify(readings.last()))
 * ```
 *
 * **Faithful to the cold contract, not to physics.** The real [Accelerometer] is cold — the sensor
 * is registered per collector and released when that collector's coroutine is cancelled — and this
 * fixture keeps that: every [observe] call replays [samples] from the start and never completes on
 * its own, so a test that forgets to bound or cancel its collection hangs, exactly as it would
 * against a real sensor that simply never stops reporting. What it does **not** model is two
 * concurrent collectors sharing one live stream — on a real device, two listeners registered at
 * different times both see whatever the hardware reports *right now*; here, each collector gets
 * its own replay of [samples] from the start. Test one collector at a time.
 *
 * [registrations] and [activeCollectors] exist for the same reason a `closeCount` exists on other
 * fixtures in this suite: to prove that code which stops collecting really does stop, instead of
 * leaking a registration the way a forgotten sensor listener would drain a real device's battery.
 *
 * **Not thread-safe**, deliberately: the backing counters are plain `Int`s. Drive it from one test
 * coroutine and assert once the work under test has finished. Making it concurrent would mean an
 * atomics dependency in an artifact whose value is being trivial.
 *
 * @param isAvailable what [Accelerometer.isAvailable] reports, and whether [observe] emits
 *   anything at all — mutable, so a test can flip a device from having no accelerometer to having
 *   one, though a real device never does that mid-process.
 * @param samples what every collection of [observe] replays, oldest first. Mutable, so a later
 *   collection can see a different script than an earlier one.
 */
public class ScriptedAccelerometer(
    public override var isAvailable: Boolean = true,
    public var samples: List<AccelerometerSample> = emptyList(),
) : Accelerometer {

    private var recordedRegistrations: Int = 0
    private var recordedActiveCollectors: Int = 0

    /**
     * How many times [observe] has been collected, whether or not the collector is still active.
     *
     * Counted per collection, not per instance — collecting the returned `Flow` twice registers
     * twice, exactly as two calls to the real factory's `observe()` would each register their own
     * platform listener.
     */
    public val registrations: Int get() = recordedRegistrations

    /**
     * How many of those registrations are still collecting right now.
     *
     * Drops back to zero once every collecting coroutine has been cancelled.
     */
    public val activeCollectors: Int get() = recordedActiveCollectors

    override fun observe(): Flow<AccelerometerSample> = flow {
        recordedRegistrations++
        if (!isAvailable) {
            // No hardware: stay silent rather than invent readings, exactly like the real
            // implementations — isAvailable is the flag a consumer is meant to check.
            awaitCancellation()
        }
        recordedActiveCollectors++
        try {
            samples.forEach { sample -> emit(sample) }
            awaitCancellation()
        } finally {
            recordedActiveCollectors--
        }
    }
}
