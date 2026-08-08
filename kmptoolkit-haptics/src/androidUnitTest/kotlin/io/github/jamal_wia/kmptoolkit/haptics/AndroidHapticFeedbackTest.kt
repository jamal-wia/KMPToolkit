package io.github.jamal_wia.kmptoolkit.haptics

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the decision-making half of the Android implementation — hardware check, permission
 * failure, and the semantic type-to-vibration mapping — against a hand-written [VibratorPort].
 *
 * The framework-facing half lives in [SystemVibratorPortTest], which needs Robolectric; this file
 * deliberately does not, so these cases stay fast and independent of an SDK sandbox.
 */
class AndroidHapticFeedbackTest {

    private class FakePort(
        private val hasVibrator: Boolean = true,
        private val permitted: Boolean = true,
        private val failWith: Throwable? = null,
    ) : VibratorPort {
        val emitted: MutableList<AndroidVibration> = mutableListOf()

        override fun hasVibrator(): Boolean = hasVibrator

        override fun emit(vibration: AndroidVibration): Boolean {
            failWith?.let { throw it }
            emitted += vibration
            return permitted
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
    fun `a missing VIBRATE permission reports PERMISSION_DENIED instead of throwing`() {
        val port = FakePort(permitted = false)
        val haptics: HapticFeedback = AndroidHapticFeedback(port)

        HapticType.entries.forEach { type ->
            assertEquals(HapticResult.PERMISSION_DENIED, haptics.perform(type), "type=$type")
        }
    }

    @Test
    fun `a denied permission stays denied across calls rather than being cached as unavailable`() {
        val port = FakePort(permitted = false)
        val haptics: HapticFeedback = AndroidHapticFeedback(port)

        repeat(3) {
            assertEquals(HapticResult.PERMISSION_DENIED, haptics.perform(HapticType.SUCCESS))
        }
    }

    @Test
    fun `a denied request was still attempted rather than skipped preemptively`() {
        // The permission state is not something the module caches or pre-checks: it asks the
        // platform every time, so a manifest fixed by a later build takes effect immediately.
        val port = FakePort(permitted = false)

        AndroidHapticFeedback(port).perform(HapticType.WARNING)

        assertContentEquals(listOf(HapticType.WARNING.toVibration()), port.emitted)
    }

    @Test
    fun `no motor takes precedence over a denied permission`() {
        val port = FakePort(hasVibrator = false, permitted = false)

        assertEquals(
            HapticResult.UNAVAILABLE,
            AndroidHapticFeedback(port).perform(HapticType.LIGHT),
        )
    }

    @Test
    fun `a failure that is not a SecurityException is not swallowed`() {
        // Only the permission failure has a defined typed outcome. Anything else is a defect
        // somewhere else and hiding it behind a HapticResult would make it unfindable.
        val port = FakePort(failWith = IllegalStateException("vibrator is on fire"))

        val thrown: Throwable = runCatching {
            AndroidHapticFeedback(port).perform(HapticType.LIGHT)
        }.exceptionOrNull() ?: error("expected the failure to propagate")

        assertTrue(thrown is IllegalStateException, "was $thrown")
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
