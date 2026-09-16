package io.github.jamal_wia.kmptoolkit.biometric

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * [BiometricGate.launchEnrollment] on Android, from its KDoc and [BiometricEnrollmentLauncher]'s: the
 * wizard for an unenrolled user, the management screen first for an enrolled one, the first
 * resolvable candidate wins, and a time-based throttle that a launch opening nothing still consumes
 * but does not latch.
 */
@RunWith(AndroidJUnit4::class)
class BiometricEnrollmentLauncherTest {

    private var now: Long = 10_000
    private val started = mutableListOf<Intent>()
    private val unresolvable = mutableSetOf<String>()
    private val forbidden = mutableSetOf<String>()

    private val starter = EnrollmentScreenStarter { intent ->
        if (intent.action in unresolvable) throw ActivityNotFoundException(intent.action)
        if (intent.action in forbidden) throw SecurityException(intent.action)
        started += intent
        true
    }

    private fun launcher(sdkInt: Int = Build.VERSION_CODES.VANILLA_ICE_CREAM, weak: Boolean = false) =
        BiometricEnrollmentLauncher(
            sdkInt = sdkInt,
            weakTier = weak,
            throttleMillis = 1_000,
            elapsedRealtimeMillis = { now },
            starter = starter,
        )

    @Test
    fun `an unenrolled user is sent to the enrolment wizard for the gate's tier`() {
        assertEquals(BiometricEnrollmentLaunch.LAUNCHED, launcher(weak = true).launch(enrolled = false))

        val intent: Intent = started.single()
        assertEquals(Settings.ACTION_BIOMETRIC_ENROLL, intent.action)
        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_WEAK,
            intent.getIntExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, -1),
        )
    }

    @Test
    fun `an enrolled user is sent to the management screen because the wizard would close itself`() {
        launcher().launch(enrolled = true)

        assertEquals(ACTION_COMBINED_BIOMETRICS_SETTINGS, started.single().action)
    }

    @Test
    fun `a device without the management screen falls back to the wizard`() {
        unresolvable += ACTION_COMBINED_BIOMETRICS_SETTINGS

        assertEquals(BiometricEnrollmentLaunch.LAUNCHED, launcher().launch(enrolled = true))

        assertEquals(Settings.ACTION_BIOMETRIC_ENROLL, started.single().action)
    }

    @Test
    fun `a device resolving no candidate reports unavailable`() {
        unresolvable += Settings.ACTION_BIOMETRIC_ENROLL

        assertEquals(BiometricEnrollmentLaunch.UNAVAILABLE, launcher().launch(enrolled = false))
    }

    @Test
    fun `below API 30 the security settings screen is the only candidate`() {
        launcher(sdkInt = Build.VERSION_CODES.Q).launch(enrolled = true)

        assertEquals(Settings.ACTION_SECURITY_SETTINGS, started.single().action)
    }

    @Test
    fun `a second launch inside the window is throttled and starts nothing`() {
        val launcher = launcher()

        launcher.launch(enrolled = false)
        now += 999

        assertEquals(BiometricEnrollmentLaunch.THROTTLED, launcher.launch(enrolled = false))
        assertEquals(1, started.size)
    }

    @Test
    fun `a launch at the window boundary starts again`() {
        val launcher = launcher()

        launcher.launch(enrolled = false)
        now += 1_000

        assertEquals(BiometricEnrollmentLaunch.LAUNCHED, launcher.launch(enrolled = false))
        assertEquals(2, started.size)
    }

    @Test
    fun `a launch that opened nothing consumes the window but does not latch`() {
        val launcher = launcher()
        unresolvable += Settings.ACTION_BIOMETRIC_ENROLL
        launcher.launch(enrolled = false)

        unresolvable.clear()
        assertEquals(BiometricEnrollmentLaunch.THROTTLED, launcher.launch(enrolled = false))
        now += 1_001

        assertEquals(BiometricEnrollmentLaunch.LAUNCHED, launcher.launch(enrolled = false))
    }

    @Test
    fun `the gate picks the screen from its own availability`() = runTest {
        val gate = AndroidBiometricGate(
            status = BiometricStatusPort { BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED },
            prompt = BiometricPromptPort { _, _, _ -> null },
            config = BiometricGateConfig(),
            enrollment = starter,
            sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
            elapsedRealtimeMillis = { now },
        )

        assertEquals(BiometricEnrollmentLaunch.LAUNCHED, gate.launchEnrollment())
        assertEquals(Settings.ACTION_BIOMETRIC_ENROLL, started.single().action)
    }

    @Test
    fun `a locked-out user with an enrolment is sent to the management screen not the wizard`() = runTest {
        val gate = AndroidBiometricGate(
            status = BiometricStatusPort { BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE },
            prompt = BiometricPromptPort { _, _, _ -> null },
            config = BiometricGateConfig(),
            enrollment = starter,
            sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
            elapsedRealtimeMillis = { now },
        )

        assertEquals(BiometricEnrollmentLaunch.LAUNCHED, gate.launchEnrollment())
        assertEquals(ACTION_COMBINED_BIOMETRICS_SETTINGS, started.first().action)
    }

    @Test
    fun `a protected screen falls through to the next candidate instead of crashing`() {
        forbidden += ACTION_COMBINED_BIOMETRICS_SETTINGS

        assertEquals(BiometricEnrollmentLaunch.LAUNCHED, launcher().launch(enrolled = true))
        assertEquals(Settings.ACTION_BIOMETRIC_ENROLL, started.single().action)
    }

    @Test
    fun `a zero throttle never throttles`() = runTest {
        val gate = AndroidBiometricGate(
            status = BiometricStatusPort { BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED },
            prompt = BiometricPromptPort { _, _, _ -> null },
            config = BiometricGateConfig(),
            options = BiometricGateOptions(enrollmentThrottle = 0.seconds),
            enrollment = starter,
            sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
            elapsedRealtimeMillis = { now },
        )

        gate.launchEnrollment()

        assertEquals(BiometricEnrollmentLaunch.LAUNCHED, gate.launchEnrollment())
    }

    @Test
    fun `the factory rejects the weak tier together with the device credential`() {
        val context: Context = ApplicationProvider.getApplicationContext()

        assertFailsWith<IllegalArgumentException> {
            createBiometricGate(
                context,
                BiometricGateConfig(policy = BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL),
                BiometricGateOptions(strength = BiometricStrength.WEAK),
            )
        }
    }
}
