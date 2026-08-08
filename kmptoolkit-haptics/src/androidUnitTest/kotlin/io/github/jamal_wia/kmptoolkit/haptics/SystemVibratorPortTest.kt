package io.github.jamal_wia.kmptoolkit.haptics

import android.app.Application
import android.content.Context
import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowVibrator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises [SystemVibratorPort] against the real framework `Vibrator`, on each of the three API
 * levels where this module's behavior differs: 24 (no `VibrationEffect`), 30 (`VibrationEffect`,
 * legacy service lookup) and 34 (`VibratorManager`).
 *
 * Robolectric is what makes those three levels testable at all — the branches read
 * `Build.VERSION.SDK_INT` directly rather than an injected level, so that Android lint can see the
 * version guard around every API-26-only call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [LEGACY_SDK], application = Application::class)
class SystemVibratorPortLegacyApiTest {

    @Test
    fun `a one-shot plays for its own duration on the pre-VibrationEffect path`() {
        // API 24/25 only has vibrate(long): the duration survives, the amplitude cannot.
        val emitted: Boolean = port().emit(AndroidVibration.OneShot(20L, 128))

        assertTrue(emitted)
        assertEquals(20L, vibratorShadow().milliseconds)
    }

    @Test
    fun `a waveform plays its whole timeline on the pre-VibrationEffect path`() {
        val emitted: Boolean = port().emit(AndroidVibration.Waveform(listOf(0L, 20L, 60L, 40L)))

        assertTrue(emitted)
        assertEquals(120L, vibratorShadow().milliseconds)
    }

    @Test
    fun `every haptic type reaches the motor on the oldest supported API level`() {
        val haptics: HapticFeedback = AndroidHapticFeedback(port())

        HapticType.entries.forEach { type ->
            assertEquals(HapticResult.PERFORMED, haptics.perform(type), "type=$type")
            assertTrue(vibratorShadow().milliseconds > 0L, "type=$type")
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [EFFECT_SDK], application = Application::class)
class SystemVibratorPortEffectApiTest {

    @Test
    fun `a one-shot plays for its own duration through VibrationEffect`() {
        val emitted: Boolean =
            port().emit(AndroidVibration.OneShot(60L, AndroidVibration.DEFAULT_AMPLITUDE))

        assertTrue(emitted)
        assertEquals(60L, vibratorShadow().milliseconds)
    }

    @Test
    fun `a waveform plays its whole timeline through VibrationEffect`() {
        val emitted: Boolean =
            port().emit(AndroidVibration.Waveform(listOf(0L, 60L, 80L, 60L, 80L, 60L)))

        assertTrue(emitted)
        assertEquals(340L, vibratorShadow().milliseconds)
    }

    @Test
    fun `perform reports UNAVAILABLE when the device says it has no vibrator`() {
        vibratorShadow().setHasVibrator(false)

        val result: HapticResult =
            AndroidHapticFeedback(port()).perform(HapticType.MEDIUM)

        assertEquals(HapticResult.UNAVAILABLE, result)
        assertEquals(0L, vibratorShadow().milliseconds)
        assertFalse(port().hasVibrator())
    }

    @Test
    fun `a device with no vibrator service is reported as having no motor`() {
        // getSystemService can return null on stripped-down builds; that is not an error, and the
        // port must answer for it rather than dereferencing null.
        val port: VibratorPort = SystemVibratorPort(vibrator = null)

        assertFalse(port.hasVibrator())
        assertFalse(port.emit(AndroidVibration.OneShot(20L, 128)))
        assertEquals(
            HapticResult.UNAVAILABLE,
            AndroidHapticFeedback(port).perform(HapticType.LIGHT),
        )
    }

    @Test
    fun `the factory builds a working instance from a plain Context`() {
        val haptics: HapticFeedback = createHapticFeedback(context())

        assertEquals(HapticResult.PERFORMED, haptics.perform(HapticType.LIGHT))
        assertEquals(20L, vibratorShadow().milliseconds)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [VIBRATOR_MANAGER_SDK], application = Application::class)
class SystemVibratorPortVibratorManagerTest {

    @Test
    fun `API 31+ resolves a usable vibrator through VibratorManager`() {
        // Identity against getSystemService(Vibrator::class.java) is not asserted: which object the
        // framework hands back is an implementation detail. That the resolved one actually drives
        // the motor is not.
        val resolved: Vibrator? = SystemVibratorPort.resolveVibrator(context())

        assertNotNull(resolved)
        assertTrue(SystemVibratorPort(resolved).emit(AndroidVibration.OneShot(60L, 128)))
        assertEquals(60L, shadowOf(resolved).milliseconds)
    }

    @Test
    fun `the observable behavior is identical to the older lookup path`() {
        val haptics: HapticFeedback = createHapticFeedback(context())

        assertEquals(HapticResult.PERFORMED, haptics.perform(HapticType.LIGHT))

        val vibrator: Vibrator = context().getSystemService(Vibrator::class.java)
        assertEquals(20L, shadowOf(vibrator).milliseconds)
    }
}

private const val LEGACY_SDK: Int = 24
private const val EFFECT_SDK: Int = 30
private const val VIBRATOR_MANAGER_SDK: Int = 34

private fun context(): Context = ApplicationProvider.getApplicationContext()

private fun port(): VibratorPort =
    SystemVibratorPort(SystemVibratorPort.resolveVibrator(context()))

private fun vibratorShadow(): ShadowVibrator {
    @Suppress("DEPRECATION")
    val vibrator: Vibrator = context().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    return shadowOf(vibrator)
}
