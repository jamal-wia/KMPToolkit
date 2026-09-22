package io.github.jamal_wia.kmptoolkit.biometric

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenRequest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * `launchEnrollment` through the real factories, down to the intent the platform receives.
 *
 * The 1.6.0 bug lived in the factory's own start lambda — bare `FLAG_ACTIVITY_NEW_TASK`, which joins
 * a Settings task left in the background — and no test reached it. So these go through
 * `createBiometricGate` itself: the default path of every existing factory must start the screen with
 * exactly `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NEW_DOCUMENT` (a task of its own), and the launcher
 * overload must hand the app's launcher one request of kind [BiometricEnrollmentScreen].
 *
 * Robolectric is told to fail unresolvable starts (`checkActivities`), so "the device has no such
 * screen" is the platform's own `ActivityNotFoundException`. The screens are registered per test, so
 * which of them the device resolves is the test's decision; the tests do not depend on what
 * Robolectric's `BiometricManager` reports as enrolled.
 */
@RunWith(AndroidJUnit4::class)
class BiometricGateFactoryTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val controllers: MutableList<ActivityController<out Activity>> = mutableListOf()

    @BeforeTest
    fun setUp() {
        shadowOf(application).checkActivities(true)
    }

    @AfterTest
    fun tearDown() {
        controllers.forEach { controller -> controller.close() }
    }

    @Test
    fun `the two-argument factory starts enrolment in a task of its own`() = runTest {
        registerScreen(ACTION_COMBINED_BIOMETRICS_SETTINGS)
        registerScreen(Settings.ACTION_BIOMETRIC_ENROLL)

        assertEquals(BiometricEnrollmentLaunch.LAUNCHED, createBiometricGate(application).launchEnrollment())

        val started: Intent = assertNotNull(shadowOf(application).nextStartedActivity)
        assertTrue(started.action in ENROLMENT_ACTIONS, "started ${started.action}")
        assertEquals(SEPARATE_TASK, started.flags)
        assertNull(shadowOf(application).nextStartedActivity, "exactly one screen is started")
    }

    @Test
    fun `the three-argument factory starts enrolment in a task of its own`() = runTest {
        registerScreen(ACTION_COMBINED_BIOMETRICS_SETTINGS)
        registerScreen(Settings.ACTION_BIOMETRIC_ENROLL)

        val gate: BiometricGate = createBiometricGate(application, BiometricGateConfig(), BiometricGateOptions())
        assertEquals(BiometricEnrollmentLaunch.LAUNCHED, gate.launchEnrollment())

        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
    }

    @Test
    fun `the wizard fallback also starts in a task of its own`() = runTest {
        // Only the wizard resolves: whether the management screen was tried first or not, the screen
        // that opens is the wizard, and it must carry the same flags.
        registerScreen(Settings.ACTION_BIOMETRIC_ENROLL)

        assertEquals(BiometricEnrollmentLaunch.LAUNCHED, createBiometricGate(application).launchEnrollment())

        val started: Intent = assertNotNull(shadowOf(application).nextStartedActivity)
        assertEquals(Settings.ACTION_BIOMETRIC_ENROLL, started.action)
        assertEquals(SEPARATE_TASK, started.flags)
    }

    @Test
    @Config(sdk = [29])
    fun `below API 30 the security settings start in a task of their own`() = runTest {
        registerScreen(Settings.ACTION_SECURITY_SETTINGS)

        assertEquals(BiometricEnrollmentLaunch.LAUNCHED, createBiometricGate(application).launchEnrollment())

        val started: Intent = assertNotNull(shadowOf(application).nextStartedActivity)
        assertEquals(Settings.ACTION_SECURITY_SETTINGS, started.action)
        assertEquals(SEPARATE_TASK, started.flags)
    }

    @Test
    fun `a resumed activity does not pull enrolment into the app's task`() = runTest {
        registerScreen(Settings.ACTION_BIOMETRIC_ENROLL)
        val gate: BiometricGate = createBiometricGate(application)
        launch(Activity::class.java).setup() // resumed: the 1.6.0 code started enrolment from it

        gate.launchEnrollment()

        // Robolectric records starts from any context in one queue, so the flags are the evidence: a
        // caller-task start would carry none, and the 1.6.0 start from this activity carried NEW_TASK
        // alone.
        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
        assertNull(shadowOf(application).nextStartedActivity, "exactly one screen is started")
    }

    @Test
    fun `a device resolving no candidate reports unavailable and starts nothing`() = runTest {
        assertEquals(BiometricEnrollmentLaunch.UNAVAILABLE, createBiometricGate(application).launchEnrollment())

        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `the launcher overload hands the app's launcher one request of the enrolment kind`() = runTest {
        val requests: MutableList<SystemScreenRequest> = mutableListOf()
        val gate: BiometricGate = createBiometricGate(
            application,
            BiometricGateConfig(),
            BiometricGateOptions(),
            SystemScreenLauncher { request -> requests += request; true },
        )

        assertEquals(BiometricEnrollmentLaunch.LAUNCHED, gate.launchEnrollment())

        val request: SystemScreenRequest = requests.single()
        assertSame(BiometricEnrollmentScreen, request.kind)
        assertSame(application, request.applicationContext)
        assertTrue(request.candidates.all { it.flags == 0 }, "candidates carry no launch flags")
        // The wizard is the last candidate on API 30+ whether or not something is enrolled.
        val wizard: Intent = request.candidates.last()
        assertEquals(Settings.ACTION_BIOMETRIC_ENROLL, wizard.action)
        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_STRONG,
            wizard.getIntExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, -1),
        )
        assertNull(shadowOf(application).nextStartedActivity, "the app's launcher, not the library, starts")
    }

    @Test
    fun `the launcher overload throttles before the launcher`() = runTest {
        var calls = 0
        val gate: BiometricGate = createBiometricGate(
            application,
            BiometricGateConfig(),
            BiometricGateOptions(),
            SystemScreenLauncher { calls++; true },
        )

        gate.launchEnrollment()

        assertEquals(BiometricEnrollmentLaunch.THROTTLED, gate.launchEnrollment())
        assertEquals(1, calls)
    }

    @Test
    fun `the launcher overload reports a launcher answering false as unavailable`() = runTest {
        val gate: BiometricGate = createBiometricGate(
            application,
            BiometricGateConfig(),
            BiometricGateOptions(),
            SystemScreenLauncher { false },
        )

        assertEquals(BiometricEnrollmentLaunch.UNAVAILABLE, gate.launchEnrollment())
    }

    @Test
    fun `the launcher overload reports a throwing launcher as unavailable`() = runTest {
        val gate: BiometricGate = createBiometricGate(
            application,
            BiometricGateConfig(),
            BiometricGateOptions(),
            SystemScreenLauncher { throw IllegalStateException("the app's launcher failed") },
        )

        assertEquals(BiometricEnrollmentLaunch.UNAVAILABLE, gate.launchEnrollment())
    }

    @Test
    fun `the launcher overload with SeparateTask behaves like the default`() = runTest {
        registerScreen(Settings.ACTION_BIOMETRIC_ENROLL)
        val gate: BiometricGate = createBiometricGate(
            application,
            BiometricGateConfig(),
            BiometricGateOptions(),
            SystemScreenLauncher.SeparateTask,
        )

        assertEquals(BiometricEnrollmentLaunch.LAUNCHED, gate.launchEnrollment())
        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
    }

    @Test
    fun `the enrolment kind names itself`() {
        assertEquals("BiometricEnrollmentScreen", BiometricEnrollmentScreen.toString())
    }

    private fun <A : Activity> launch(type: Class<A>): ActivityController<A> =
        Robolectric.buildActivity(type).also { controllers.add(it) }

    private fun registerScreen(action: String) {
        val component = ComponentName("com.android.settings", "com.android.settings.$action")
        shadowOf(application.packageManager).apply {
            addActivityIfNotPresent(component)
            // startActivity resolves with CATEGORY_DEFAULT, as the platform does.
            addIntentFilterForActivity(component, IntentFilter(action).apply { addCategory(Intent.CATEGORY_DEFAULT) })
        }
    }

    private companion object {
        const val SEPARATE_TASK: Int = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT
        val ENROLMENT_ACTIONS: Set<String> = setOf(ACTION_COMBINED_BIOMETRICS_SETTINGS, Settings.ACTION_BIOMETRIC_ENROLL)
    }
}
