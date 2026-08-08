package io.github.jamal_wia.kmptoolkit.haptics.testing

import io.github.jamal_wia.kmptoolkit.haptics.HapticFeedback
import io.github.jamal_wia.kmptoolkit.haptics.HapticResult
import io.github.jamal_wia.kmptoolkit.haptics.HapticType

/**
 * A [HapticFeedback] double that records what it was asked to play and returns whatever result the
 * test wants.
 *
 * Two things it lets you assert, which the real implementations do not:
 * - **Which haptics your code fires, and in what order** — `assertEquals(listOf(HapticType.ERROR),
 *   haptics.events)` is the whole test.
 * - **How your code copes when haptics do not work** — set [result] to
 *   [HapticResult.PERMISSION_DENIED] or [HapticResult.UNAVAILABLE] and check that the flow it is
 *   part of still completes.
 *
 * **Recording is independent of [result].** A call is recorded even when the configured result says
 * the device could not play it, because the question the recording answers is "did my code ask for
 * this?", not "did the motor run?".
 *
 * **Not thread-safe**, deliberately: the backing list is a plain `MutableList`. Drive it from one
 * thread — or one test coroutine — and assert after the work under test has finished. Making it
 * concurrent would mean an atomic dependency in an artifact whose value is being trivial.
 *
 * @param result what [perform] reports back; mutable so a single instance can switch mid-test.
 */
public class RecordingHapticFeedback(
    public var result: HapticResult = HapticResult.PERFORMED,
) : HapticFeedback {

    private val recorded: MutableList<HapticType> = mutableListOf()

    /**
     * Every type passed to [perform] so far, oldest first.
     *
     * A snapshot: the returned list does not change when more calls arrive, so holding on to it
     * across a later `perform` is safe.
     */
    public val events: List<HapticType> get() = recorded.toList()

    override fun perform(type: HapticType): HapticResult {
        recorded += type
        return result
    }

    /**
     * Drops everything recorded so far, leaving [result] untouched.
     *
     * Useful to separate the arrange phase from the act phase when setup itself fires haptics.
     */
    public fun clear() {
        recorded.clear()
    }
}
