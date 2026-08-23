package io.github.jamal_wia.kmptoolkit.proximity.testing

import io.github.jamal_wia.kmptoolkit.proximity.ProximitySensor
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A [ProximitySensor] a test drives by hand.
 *
 * Lets you write the transitions no emulator will give you on demand — a phone going to the ear
 * mid-call, or a device whose sensor never reports because [isAvailable] is false:
 *
 * ```kotlin
 * val proximity = FakeProximitySensor()
 * val presenter = CallPresenter(proximity)
 * proximity.emit(near = true)
 * assertTrue(presenter.isScreenDimmed)
 * ```
 *
 * **Not a hardware simulation.** The real [ProximitySensor.observe] is cold — the sensor is
 * registered only while something collects, and a reading published before a collector exists is
 * lost, the way a real interrupt would be. This fixture instead replays the most recent [emit] to
 * every new collector, closer to a `StateFlow` than to the real sensor, because that is what makes
 * it possible to call [emit] before or after `observe()` is collected without racing a test
 * coroutine against a background one. A second difference: `observe()` on an unavailable real sensor
 * never emits and never completes either, so collecting it hangs forever, which is correct — nobody
 * is listening for what never arrives. Here it returns an already-completed empty flow instead, so a
 * test asserting "nothing came through" does not have to hang the way that assertion would against
 * real hardware. If a test genuinely depends on cold registration timing or that hang, it belongs
 * against the real Android implementation under Robolectric, not against this fixture.
 *
 * @param isAvailable what [ProximitySensor.isAvailable] reports. Mutable, so a test can model a
 *   device that starts without a usable sensor and then, degenerately, gains one — or the reverse.
 */
public class FakeProximitySensor(
    isAvailable: Boolean = true,
) : ProximitySensor {

    override var isAvailable: Boolean = isAvailable

    private val mutableReadings: MutableSharedFlow<Boolean> =
        MutableSharedFlow(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * How many readings [emit] has published so far.
     *
     * Counted regardless of [isAvailable] and regardless of whether anything was collecting —
     * proof of what your code *asked to publish*, not of what a collector actually saw.
     */
    public var emitCount: Int = 0
        private set

    /**
     * Publishes [near] as the next reading.
     *
     * Works regardless of [isAvailable] — [observe] is what enforces "an absent sensor never
     * emits," not this method — so a test can prove that a reading queued while unavailable
     * surfaces correctly once [isAvailable] flips back to `true`.
     */
    public fun emit(near: Boolean) {
        emitCount++
        mutableReadings.tryEmit(near)
    }

    override fun observe(): Flow<Boolean> =
        if (isAvailable) mutableReadings.asSharedFlow() else emptyFlow()
}
