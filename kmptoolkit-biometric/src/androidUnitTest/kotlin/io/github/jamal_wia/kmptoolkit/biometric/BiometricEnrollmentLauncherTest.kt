package io.github.jamal_wia.kmptoolkit.biometric

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * [BiometricGate.launchEnrollment] on Android, from its KDoc and [BiometricEnrollmentLauncher]'s: the
 * wizard for an unenrolled user, the management screen first for an enrolled one, every candidate in
 * one request of kind [BiometricEnrollmentScreen] handed to the launcher once, a time-based throttle
 * that runs before the launcher — which a launch opening nothing still consumes but does not latch —
 * and `false` or a throwing launcher reported as `UNAVAILABLE`.
 *
 * Which candidate the device actually opens, and with which flags, is the launcher's business; see
 * `BiometricGateFactoryTest` for the default one.
 */
@RunWith(AndroidJUnit4::class)
class BiometricEnrollmentLauncherTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now: Long = 10_000
    private val requests: MutableList<SystemScreenRequest> = mutableListOf()
    private var answer: Boolean = true
    private var failure: Exception? = null

    private val systemScreens = SystemScreenLauncher { request ->
        requests += request
        failure?.let { throw it }
        answer
    }

    private fun launcher(sdkInt: Int = Build.VERSION_CODES.VANILLA_ICE_CREAM, weak: Boolean = false) =
        BiometricEnrollmentLauncher(
            sdkInt = sdkInt,
            weakTier = weak,
            throttleMillis = 1_000,
            elapsedRealtimeMillis = { now },
            starter = systemScreens.enrollmentStarter(context),
        )

    private fun gate(
        status: Int,
        options: BiometricGateOptions = BiometricGateOptions(),
    ): BiometricGate = AndroidBiometricGate(
        status = BiometricStatusPort { status },
        prompt = BiometricPromptPort { _, _, _ -> null },
        config = BiometricGateConfig(),
        options = options,
        enrollment = systemScreens.enrollmentStarter(context),
        sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
        elapsedRealtimeMillis = { now },
    )

    private fun SystemScreenRequest.actions(): List<String?> = candidates.map { it.action }

    @Test
    fun `an unenrolled user is sent to the enrolment wizard for the gate's tier`() {
        assertEquals(BiometricEnrollmentLaunch.LAUNCHED, launcher(weak = true).launch(enrolled = false))

        val intent: Intent = requests.single().candidates.single()
        assertEquals(Settings.ACTION_BIOMETRIC_ENROLL, intent.action)
        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_WEAK,
            intent.getIntExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, -1),
        )
    }

    @Test
    fun `a strong-tier gate asks the wizard for the strong tier`() {
        launcher().launch(enrolled = false)

        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_STRONG,
            requests.single().candidates.single().getIntExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, -1),
        )
    }

    @Test
    fun `an enrolled user gets the management screen first and the wizard as fallback in one request`() {
        launcher().launch(enrolled = true)

        assertEquals(
            listOf<String?>(ACTION_COMBINED_BIOMETRICS_SETTINGS, Settings.ACTION_BIOMETRIC_ENROLL),
            requests.single().actions(),
        )
    }

    @Test
    fun `below API 30 the security settings screen is the only candidate`() {
        launcher(sdkInt = Build.VERSION_CODES.Q).launch(enrolled = true)

        assertEquals(listOf<String?>(Settings.ACTION_SECURITY_SETTINGS), requests.single().actions())
    }

    @Test
    fun `the request names the enrolment screen and carries no launch flags`() {
        launcher().launch(enrolled = true)

        val request: SystemScreenRequest = requests.single()
        assertSame(BiometricEnrollmentScreen, request.kind)
        assertEquals(listOf(0, 0), request.candidates.map { it.flags })
        assertSame(context.applicationContext, request.applicationContext)
    }

    @Test
    fun `a launcher that opened nothing is reported as unavailable`() {
        answer = false

        assertEquals(BiometricEnrollmentLaunch.UNAVAILABLE, launcher().launch(enrolled = false))
        assertEquals(1, requests.size)
    }

    @Test
    fun `a launcher that throws is reported as unavailable rather than crashing the caller`() {
        failure = IllegalStateException("the app's launcher failed")

        assertEquals(BiometricEnrollmentLaunch.UNAVAILABLE, launcher().launch(enrolled = false))
    }

    @Test
    fun `a launcher that throws a platform start failure is reported as unavailable`() {
        failure = SecurityException("not exported")

        assertEquals(BiometricEnrollmentLaunch.UNAVAILABLE, launcher().launch(enrolled = true))
    }

    @Test
    fun `a second launch inside the window is throttled and never reaches the launcher`() {
        val launcher = launcher()

        launcher.launch(enrolled = false)
        now += 999

        assertEquals(BiometricEnrollmentLaunch.THROTTLED, launcher.launch(enrolled = false))
        assertEquals(1, requests.size)
    }

    @Test
    fun `a launch at the window boundary starts again`() {
        val launcher = launcher()

        launcher.launch(enrolled = false)
        now += 1_000

        assertEquals(BiometricEnrollmentLaunch.LAUNCHED, launcher.launch(enrolled = false))
        assertEquals(2, requests.size)
    }

    @Test
    fun `a launch that opened nothing consumes the window but does not latch`() {
        val launcher = launcher()
        answer = false
        launcher.launch(enrolled = false)

        answer = true
        assertEquals(BiometricEnrollmentLaunch.THROTTLED, launcher.launch(enrolled = false))
        assertEquals(1, requests.size)
        now += 1_001

        assertEquals(BiometricEnrollmentLaunch.LAUNCHED, launcher.launch(enrolled = false))
    }

    @Test
    fun `a launch whose launcher threw consumes the window too`() {
        val launcher = launcher()
        failure = IllegalStateException("boom")
        launcher.launch(enrolled = false)

        failure = null
        assertEquals(BiometricEnrollmentLaunch.THROTTLED, launcher.launch(enrolled = false))
    }

    @Test
    fun `the gate picks the screen from its own availability`() = runTest {
        val gate: BiometricGate = gate(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED)

        assertEquals(BiometricEnrollmentLaunch.LAUNCHED, gate.launchEnrollment())
        assertEquals(listOf<String?>(Settings.ACTION_BIOMETRIC_ENROLL), requests.single().actions())
    }

    @Test
    fun `a locked-out user with an enrolment is sent to the management screen not the wizard`() = runTest {
        val gate: BiometricGate = gate(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE)

        assertEquals(BiometricEnrollmentLaunch.LAUNCHED, gate.launchEnrollment())
        assertEquals(ACTION_COMBINED_BIOMETRICS_SETTINGS, requests.single().actions().first())
    }

    @Test
    fun `a zero throttle never throttles`() = runTest {
        val gate: BiometricGate = gate(
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricGateOptions(enrollmentThrottle = 0.seconds),
        )

        gate.launchEnrollment()

        assertEquals(BiometricEnrollmentLaunch.LAUNCHED, gate.launchEnrollment())
        assertEquals(2, requests.size)
    }

    @Test
    fun `a gate without an enrolment launcher reports unavailable`() = runTest {
        val gate = AndroidBiometricGate(
            status = BiometricStatusPort { BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED },
            prompt = BiometricPromptPort { _, _, _ -> null },
            config = BiometricGateConfig(),
        )

        assertEquals(BiometricEnrollmentLaunch.UNAVAILABLE, gate.launchEnrollment())
    }

    @Test
    fun `the factory rejects the weak tier together with the device credential`() {
        assertFailsWith<IllegalArgumentException> {
            createBiometricGate(
                context,
                BiometricGateConfig(policy = BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL),
                BiometricGateOptions(strength = BiometricStrength.WEAK),
            )
        }
    }

    @Test
    fun `the launcher overload rejects the weak tier together with the device credential too`() {
        assertFailsWith<IllegalArgumentException> {
            createBiometricGate(
                context,
                BiometricGateConfig(policy = BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL),
                BiometricGateOptions(strength = BiometricStrength.WEAK),
                systemScreens,
            )
        }
    }
}
