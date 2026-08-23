package io.github.jamal_wia.kmptoolkit.flashlight.testing

import io.github.jamal_wia.kmptoolkit.flashlight.FlashPattern
import io.github.jamal_wia.kmptoolkit.flashlight.Flashlight

/**
 * A [Flashlight] double that records what it was asked to do, in order.
 *
 * Two things it lets you assert, which the real implementations do not:
 * - **Which patterns your code fires, and in what order** — `assertEquals(listOf(FlashPattern.Blink),
 *   flashlight.events)` is the whole test. A `stop` is recorded as `null`, so the timeline reads as
 *   the caller produced it.
 * - **How your code copes when there is no torch** — set [isAvailable] to `false` to play a device
 *   with no flash unit, and check that the flow it is part of still falls back correctly.
 *
 * **Recording is independent of [isAvailable].** A call is recorded even when the device has no
 * torch, because the question the recording answers is "did my code ask for this?", not "did the
 * torch light up?" — exactly the distinction `RecordingHapticFeedback` draws for haptics.
 *
 * **Not thread-safe**, deliberately: the backing list is a plain `MutableList`. Drive it from one
 * thread — or one test coroutine — and assert after the work under test has finished.
 *
 * @param isAvailable what [Flashlight.isAvailable] reports; mutable so a single instance can play a
 *   device with no flash unit mid-test.
 */
public class RecordingFlashlight(
    override var isAvailable: Boolean = true,
) : Flashlight {

    private val recorded: MutableList<FlashPattern?> = mutableListOf()

    /**
     * Every `start`/`stop` call so far, oldest first: a [FlashPattern] per `start`, `null` per
     * `stop`.
     *
     * A snapshot: the returned list does not change when more calls arrive, so holding on to it
     * across a later call is safe.
     */
    public val events: List<FlashPattern?> get() = recorded.toList()

    /** Whether a pattern is running right now — what a test asserts after a `stop`. */
    public var isBlinking: Boolean = false
        private set

    override fun start(pattern: FlashPattern) {
        recorded += pattern
        isBlinking = true
    }

    override fun stop() {
        recorded += null
        isBlinking = false
    }

    /** Drops everything recorded so far, and resets [isBlinking] — [isAvailable] is untouched. */
    public fun clear() {
        recorded.clear()
        isBlinking = false
    }
}
