package io.github.jamal_wia.kmptoolkit.haptics

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the decision-making half of the Android implementation — the hardware check, the
 * pass-through of the port's verdict, and the semantic type-to-vibration mapping.
 *
 * The framework-facing half, including the real exception-to-[HapticResult] translation, lives in
 * [SystemVibratorPortTest]; this file deliberately needs no Robolectric, so these cases stay fast
 * and independent of an SDK sandbox.
 */
class AndroidHapticFeedbackTest {

    private class FakePort(
        private val hasVibrator: Boolean = true,
        private val outcome: HapticResult = HapticResult.PERFORMED,
    ) : VibratorPort {
        val emitted: MutableList<AndroidVibration> = mutableListOf()

        override fun hasVibrator(): Boolean = hasVibrator

        override fun emit(vibration: AndroidVibration): HapticResult {
            emitted += vibration
            return outcome
        }
    }

    @Test
    fun `a successful request reports PERFORMED and reaches the vibrator exactly once`() {
        val port = FakePort()

        val result: HapticResult = AndroidHapticFeedback(port).perform(HapticType.MEDIUM)

        assertEquals(HapticResult.PERFORMED, result)
        assertEquals(1, port.emitted.size)
    }

    @Test
    fun `a device with no motor reports UNAVAILABLE and is never asked to vibrate`() {
        val port = FakePort(hasVibrator = false)
        val haptics: HapticFeedback = AndroidHapticFeedback(port)

        HapticType.entries.forEach { type ->
            assertEquals(HapticResult.UNAVAILABLE, haptics.perform(type), "type=$type")
        }
        assertTrue(port.emitted.isEmpty())
    }

    @Test
    fun `the port's verdict is passed through unchanged for every outcome and every type`() {
        HapticResult.entries.forEach { outcome ->
            val haptics: HapticFeedback = AndroidHapticFeedback(FakePort(outcome = outcome))

            HapticType.entries.forEach { type ->
                assertEquals(outcome, haptics.perform(type), "outcome=$outcome type=$type")
            }
        }
    }

    @Test
    fun `a missing VIBRATE permission reports PERMISSION_DENIED instead of throwing`() {
        val port = FakePort(outcome = HapticResult.PERMISSION_DENIED)
        val haptics: HapticFeedback = AndroidHapticFeedback(port)

        HapticType.entries.forEach { type ->
            assertEquals(HapticResult.PERMISSION_DENIED, haptics.perform(type), "type=$type")
        }
    }

    @Test
    fun `a denied permission stays denied across calls rather than being cached as unavailable`() {
        val port = FakePort(outcome = HapticResult.PERMISSION_DENIED)
        val haptics: HapticFeedback = AndroidHapticFeedback(port)

        repeat(3) {
            assertEquals(HapticResult.PERMISSION_DENIED, haptics.perform(HapticType.SUCCESS))
        }
    }

    @Test
    fun `a denied request was still attempted rather than skipped preemptively`() {
        // The permission state is not something the module caches or pre-checks: it asks the
        // platform every time, so a manifest fixed by a later build takes effect immediately.
        val port = FakePort(outcome = HapticResult.PERMISSION_DENIED)

        AndroidHapticFeedback(port).perform(HapticType.WARNING)

        assertContentEquals(listOf(HapticType.WARNING.toVibration()), port.emitted)
    }

    @Test
    fun `a rejected request reports FAILED and does not stop the next one from succeeding`() {
        // FAILED is transient by definition, so a failing call must not latch the instance into a
        // failed state — each call asks the platform again.
        val failing: HapticFeedback = AndroidHapticFeedback(FakePort(outcome = HapticResult.FAILED))
        val working: HapticFeedback = AndroidHapticFeedback(FakePort())

        assertEquals(HapticResult.FAILED, failing.perform(HapticType.ERROR))
        assertEquals(HapticResult.FAILED, failing.perform(HapticType.ERROR))
        assertEquals(HapticResult.PERFORMED, working.perform(HapticType.ERROR))
    }

    @Test
    fun `no motor takes precedence over any port outcome`() {
        HapticResult.entries.forEach { outcome ->
            val haptics: HapticFeedback =
                AndroidHapticFeedback(FakePort(hasVibrator = false, outcome = outcome))

            assertEquals(
                HapticResult.UNAVAILABLE,
                haptics.perform(HapticType.LIGHT),
                "outcome=$outcome",
            )
        }
    }

    @Test
    fun `each call produces its own request - nothing is coalesced`() {
        val port = FakePort()
        val haptics: HapticFeedback = AndroidHapticFeedback(port)

        repeat(3) { haptics.perform(HapticType.LIGHT) }

        assertEquals(3, port.emitted.size)
    }

    @Test
    fun `impact types map to one-shots of growing length`() {
        assertEquals(AndroidVibration.OneShot(20L, 128), HapticType.LIGHT.toVibration())
        assertEquals(
            AndroidVibration.OneShot(40L, AndroidVibration.DEFAULT_AMPLITUDE),
            HapticType.MEDIUM.toVibration(),
        )
        assertEquals(
            AndroidVibration.OneShot(60L, AndroidVibration.DEFAULT_AMPLITUDE),
            HapticType.HEAVY.toVibration(),
        )
    }

    @Test
    fun `notification types map to the documented waveforms`() {
        assertEquals(
            AndroidVibration.Waveform(listOf(0L, 20L, 60L, 40L)),
            HapticType.SUCCESS.toVibration(),
        )
        assertEquals(
            AndroidVibration.Waveform(listOf(0L, 40L, 80L, 40L)),
            HapticType.WARNING.toVibration(),
        )
        assertEquals(
            AndroidVibration.Waveform(listOf(0L, 60L, 80L, 60L, 80L, 60L)),
            HapticType.ERROR.toVibration(),
        )
    }

    @Test
    fun `every waveform starts with an off segment and alternates off-on`() {
        // The platform reads a waveform as off, on, off, on... — an odd length would end on an
        // off segment, i.e. a trailing pause nobody can feel.
        HapticType.entries
            .map { it.toVibration() }
            .filterIsInstance<AndroidVibration.Waveform>()
            .forEach { waveform ->
                assertEquals(0L, waveform.timings.first(), "waveform=$waveform")
                assertEquals(0, waveform.timings.size % 2, "waveform=$waveform")
                assertTrue(waveform.timings.drop(1).all { it > 0L }, "waveform=$waveform")
            }
    }

    @Test
    fun `no two haptic types feel the same`() {
        val vibrations: List<AndroidVibration> = HapticType.entries.map { it.toVibration() }

        assertEquals(HapticType.entries.size, vibrations.toSet().size)
    }

    @Test
    fun `the vibration handed to the port is the one the type maps to`() {
        val port = FakePort()
        val haptics: HapticFeedback = AndroidHapticFeedback(port)

        HapticType.entries.forEach { type -> haptics.perform(type) }

        assertContentEquals(HapticType.entries.map { it.toVibration() }, port.emitted)
    }
}
