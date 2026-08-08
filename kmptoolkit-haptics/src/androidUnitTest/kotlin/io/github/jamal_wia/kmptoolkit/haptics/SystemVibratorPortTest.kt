package io.github.jamal_wia.kmptoolkit.haptics

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSystemVibrator
import org.robolectric.shadows.ShadowVibrator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Exercises [SystemVibratorPort] against the real framework `Vibrator`, on each of the API levels
 * where this module's behavior differs: 24 (no `VibrationEffect`, `AudioAttributes` attribution),
 * 30 (`VibrationEffect` + `AudioAttributes`) and 34 (`VibratorManager` lookup, `VibrationAttributes`
 * attribution).
 *
 * Robolectric is what makes those levels testable at all — the branches read `Build.VERSION.SDK_INT`
 * directly rather than an injected level, so that Android lint can see the version guard around
 * every API-gated call. It is also the only way to make the framework *fail*: the two shadows at
 * the bottom of this file turn a real `Vibrator.vibrate` call into the exceptions a device throws.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [LEGACY_SDK], application = Application::class)
class SystemVibratorPortLegacyApiTest {

    @Test
    fun `a one-shot plays for its own duration on the pre-VibrationEffect path`() {
        // API 24/25 only has vibrate(long, AudioAttributes): the duration survives, the amplitude
        // cannot.
        val result: HapticResult = port().emit(AndroidVibration.OneShot(20L, 128))

        assertEquals(HapticResult.PERFORMED, result)
        assertEquals(20L, vibratorShadow().milliseconds)
    }

    @Test
    fun `a waveform plays its whole timeline on the pre-VibrationEffect path`() {
        val result: HapticResult =
            port().emit(AndroidVibration.Waveform(listOf(0L, 20L, 60L, 40L)))

        assertEquals(HapticResult.PERFORMED, result)
        assertEquals(120L, vibratorShadow().milliseconds)
        assertContentEquals(longArrayOf(0L, 20L, 60L, 40L), vibratorShadow().pattern)
        assertEquals(SystemVibratorPort.NO_REPEAT, vibratorShadow().repeat)
    }

    @Test
    fun `every haptic type plays for exactly its documented duration on the oldest API level`() {
        val haptics: HapticFeedback = AndroidHapticFeedback(port())

        EXPECTED_DURATIONS_MS.forEach { (type, expected) ->
            ShadowVibrator.reset()

            assertEquals(HapticResult.PERFORMED, haptics.perform(type), "type=$type")
            assertEquals(expected, vibratorShadow().milliseconds, "type=$type")
        }
    }

    @Test
    @Config(shadows = [AttributeCapturingVibratorShadow::class])
    fun `the pre-VibrationEffect one-shot is attributed as touch feedback`() {
        // Unattributed vibrations are classified USAGE_UNKNOWN, which the platform does not scale
        // by the user's touch-feedback intensity and does not silence with the touch-feedback
        // switch. Sonification is the closest AudioAttributes usage before VibrationAttributes.
        //
        // A capturing shadow is needed here, not ShadowVibrator.getAudioAttributesFromLastVibration():
        // Robolectric's legacy vibrate(uid, pkg, millis, attributes) overload discards the
        // attributes argument, so the built-in accessor stays null on this branch whether the
        // production code attributes the call or not.
        AttributeCapturingVibratorShadow.lastAudioAttributes = null

        port().emit(AndroidVibration.OneShot(20L, 128))

        assertTouchFeedback(AttributeCapturingVibratorShadow.lastAudioAttributes)
    }

    @Test
    @Config(shadows = [AttributeCapturingVibratorShadow::class])
    fun `the pre-VibrationEffect waveform is attributed as touch feedback`() {
        AttributeCapturingVibratorShadow.lastAudioAttributes = null

        port().emit(AndroidVibration.Waveform(listOf(0L, 20L, 60L, 40L)))

        assertTouchFeedback(AttributeCapturingVibratorShadow.lastAudioAttributes)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [EFFECT_SDK], application = Application::class)
class SystemVibratorPortEffectApiTest {

    @Test
    fun `a one-shot plays for its own duration through VibrationEffect`() {
        val result: HapticResult =
            port().emit(AndroidVibration.OneShot(60L, AndroidVibration.DEFAULT_AMPLITUDE))

        assertEquals(HapticResult.PERFORMED, result)
        assertEquals(60L, vibratorShadow().milliseconds)
    }

    @Test
    fun `a waveform plays its whole timeline through VibrationEffect`() {
        val result: HapticResult =
            port().emit(AndroidVibration.Waveform(listOf(0L, 60L, 80L, 60L, 80L, 60L)))

        assertEquals(HapticResult.PERFORMED, result)
        assertEquals(340L, vibratorShadow().milliseconds)
    }

    @Test
    fun `every haptic type plays for exactly its documented duration through VibrationEffect`() {
        val haptics: HapticFeedback = AndroidHapticFeedback(port())

        EXPECTED_DURATIONS_MS.forEach { (type, expected) ->
            ShadowVibrator.reset()

            assertEquals(HapticResult.PERFORMED, haptics.perform(type), "type=$type")
            assertEquals(expected, vibratorShadow().milliseconds, "type=$type")
        }
    }

    @Test
    fun `the VibrationEffect path attributes the vibration as touch feedback`() {
        port().emit(AndroidVibration.OneShot(60L, AndroidVibration.DEFAULT_AMPLITUDE))

        assertTouchFeedback(vibratorShadow().audioAttributesFromLastVibration)
    }

    @Test
    fun `perform reports UNAVAILABLE when the device says it has no vibrator`() {
        vibratorShadow().setHasVibrator(false)

        val result: HapticResult = AndroidHapticFeedback(port()).perform(HapticType.MEDIUM)

        assertEquals(HapticResult.UNAVAILABLE, result)
        assertEquals(0L, vibratorShadow().milliseconds)
        assertFalse(port().hasVibrator())
    }

    @Test
    fun `a device with no vibrator service reports UNAVAILABLE rather than dereferencing null`() {
        // getSystemService can return null on stripped-down builds; that is not an error.
        val port: VibratorPort = SystemVibratorPort(vibrator = null)

        assertFalse(port.hasVibrator())
        assertEquals(HapticResult.UNAVAILABLE, port.emit(AndroidVibration.OneShot(20L, 128)))
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
        assertEquals(
            HapticResult.PERFORMED,
            SystemVibratorPort(resolved).emit(AndroidVibration.OneShot(60L, 128)),
        )
        assertEquals(60L, shadowOf(resolved).milliseconds)
    }

    @Test
    fun `the observable behavior is identical to the older lookup path`() {
        val haptics: HapticFeedback = createHapticFeedback(context())

        assertEquals(HapticResult.PERFORMED, haptics.perform(HapticType.LIGHT))

        val vibrator: Vibrator = context().getSystemService(Vibrator::class.java)
        assertEquals(20L, shadowOf(vibrator).milliseconds)
    }

    @Test
    fun `every haptic type plays for exactly its documented duration on API 33+`() {
        val haptics: HapticFeedback = createHapticFeedback(context())
        val vibrator: Vibrator = context().getSystemService(Vibrator::class.java)

        EXPECTED_DURATIONS_MS.forEach { (type, expected) ->
            ShadowVibrator.reset()

            assertEquals(HapticResult.PERFORMED, haptics.perform(type), "type=$type")
            assertEquals(expected, shadowOf(vibrator).milliseconds, "type=$type")
        }
    }

    @Test
    fun `API 33+ attributes the vibration as touch feedback through VibrationAttributes`() {
        // VibrationAttributes replaced AudioAttributes as the way to classify a vibration; USAGE_TOUCH
        // is what ties it to the user's touch-feedback switch and intensity slider.
        val haptics: HapticFeedback = createHapticFeedback(context())
        val vibrator: Vibrator = context().getSystemService(Vibrator::class.java)

        haptics.perform(HapticType.LIGHT)

        val attributes: Any? = shadowOf(vibrator).vibrationAttributesFromLastVibration
        assertNotNull(attributes)
        assertEquals(VibrationAttributes.USAGE_TOUCH, (attributes as VibrationAttributes).usage)
    }
}

/**
 * The failure paths, driven through the real `Vibrator` by shadows that throw what a device throws.
 *
 * Without these, every permission assertion in the suite would sit downstream of the translation —
 * deleting the `catch` in [SystemVibratorPort] would leave the suite green.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [EFFECT_SDK], application = Application::class)
class SystemVibratorPortFailureTest {

    @Test
    @Config(shadows = [SecurityFailingVibratorShadow::class])
    fun `a SecurityException from the framework becomes PERMISSION_DENIED`() {
        val haptics: HapticFeedback = createHapticFeedback(context())

        HapticType.entries.forEach { type ->
            assertEquals(HapticResult.PERMISSION_DENIED, haptics.perform(type), "type=$type")
        }
    }

    @Test
    @Config(shadows = [RejectingVibratorShadow::class])
    fun `any other RuntimeException from the framework becomes FAILED`() {
        val haptics: HapticFeedback = createHapticFeedback(context())

        HapticType.entries.forEach { type ->
            assertEquals(HapticResult.FAILED, haptics.perform(type), "type=$type")
        }
    }

    @Test
    @Config(shadows = [RejectingVibratorShadow::class])
    fun `a failing vibrator is reported rather than thrown out of perform`() {
        // The point of the whole translation: a decorative tap must not take down the caller.
        val result: HapticResult = createHapticFeedback(context()).perform(HapticType.SUCCESS)

        assertEquals(HapticResult.FAILED, result)
    }
}

/**
 * Captures the `AudioAttributes` handed to the pre-`VibrationEffect` overloads, which Robolectric's
 * own shadow drops on the floor.
 */
@Implements(className = "android.os.SystemVibrator", isInAndroidSdk = false)
class AttributeCapturingVibratorShadow : ShadowSystemVibrator() {

    @Implementation
    override fun vibrate(
        uid: Int,
        opPkg: String?,
        milliseconds: Long,
        attributes: AudioAttributes?,
    ) {
        lastAudioAttributes = attributes
        super.vibrate(uid, opPkg, milliseconds, attributes)
    }

    @Implementation
    override fun vibrate(
        uid: Int,
        opPkg: String?,
        pattern: LongArray?,
        repeat: Int,
        attributes: AudioAttributes?,
    ) {
        lastAudioAttributes = attributes
        super.vibrate(uid, opPkg, pattern, repeat, attributes)
    }

    companion object {
        /** Static because Robolectric owns shadow instantiation; reset it per test. */
        @JvmStatic
        var lastAudioAttributes: AudioAttributes? = null
    }
}

/** Throws what a device throws when the app never declared `android.permission.VIBRATE`. */
@Implements(className = "android.os.SystemVibrator", isInAndroidSdk = false)
class SecurityFailingVibratorShadow : ShadowSystemVibrator() {

    @Implementation
    override fun vibrate(
        uid: Int,
        opPkg: String?,
        effect: VibrationEffect?,
        reason: String?,
        attributes: AudioAttributes?,
    ): Unit = throw SecurityException("Requires VIBRATE permission")
}

/** Stands in for a vibrator service that refuses the effect, or whose binder has died. */
@Implements(className = "android.os.SystemVibrator", isInAndroidSdk = false)
class RejectingVibratorShadow : ShadowSystemVibrator() {

    @Implementation
    override fun vibrate(
        uid: Int,
        opPkg: String?,
        effect: VibrationEffect?,
        reason: String?,
        attributes: AudioAttributes?,
    ): Unit = throw IllegalArgumentException("effect rejected by the vibrator service")
}

private const val LEGACY_SDK: Int = 24
private const val EFFECT_SDK: Int = 30
private const val VIBRATOR_MANAGER_SDK: Int = 34

/** The durations `docs/kmptoolkit-haptics/05-platform-notes.md` promises, per type. */
private val EXPECTED_DURATIONS_MS: Map<HapticType, Long> = mapOf(
    HapticType.LIGHT to 20L,
    HapticType.MEDIUM to 40L,
    HapticType.HEAVY to 60L,
    HapticType.SUCCESS to 120L,
    HapticType.WARNING to 160L,
    HapticType.ERROR to 340L,
)

private fun context(): Context = ApplicationProvider.getApplicationContext()

private fun port(): VibratorPort =
    SystemVibratorPort(SystemVibratorPort.resolveVibrator(context()))

/** The attribution every request must carry before `VibrationAttributes` existed. */
private fun assertTouchFeedback(attributes: AudioAttributes?) {
    assertNotNull(attributes, "the vibration was not attributed at all")
    assertEquals(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, attributes.usage)
    assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, attributes.contentType)
}

private fun vibratorShadow(): ShadowVibrator {
    @Suppress("DEPRECATION")
    val vibrator: Vibrator = context().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    return shadowOf(vibrator)
}
