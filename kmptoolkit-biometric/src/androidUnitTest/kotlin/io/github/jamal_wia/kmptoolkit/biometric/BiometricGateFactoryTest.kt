package io.github.jamal_wia.kmptoolkit.biometric

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.ActivitySubscription
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenRequest
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
 * `createBiometricGate` itself: the default path of every factory without a launcher must start the
 * screen with exactly `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NEW_DOCUMENT` (a task of its own), from
 * the application context and never from a resumed activity; `createBiometricGateWithLauncher` must
 * hand the app's launcher one request of kind [BiometricEnrollmentScreen]; and a tracker passed in
 * must be the one that hosts the prompt.
 *
 * Robolectric records starts from an activity and from the application in one queue, so a start
 * "from the activity" is proven with [RecordingActivity], which records its own starts instead.
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
        assertNull(shadowOf(application).nextStartedActivity, "exactly one screen is started")
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
        assertNull(shadowOf(application).nextStartedActivity, "exactly one screen is started")
    }

    @Test
    @Config(sdk = [29])
    fun `below API 30 the security settings start in a task of their own`() = runTest {
        registerScreen(Settings.ACTION_SECURITY_SETTINGS)

        assertEquals(BiometricEnrollmentLaunch.LAUNCHED, createBiometricGate(application).launchEnrollment())

        val started: Intent = assertNotNull(shadowOf(application).nextStartedActivity)
        assertEquals(Settings.ACTION_SECURITY_SETTINGS, started.action)
        assertEquals(SEPARATE_TASK, started.flags)
        assertNull(shadowOf(application).nextStartedActivity, "exactly one screen is started")
    }

    @Test
    fun `a resumed activity does not pull enrolment into the app's task`() = runTest {
        registerScreen(Settings.ACTION_BIOMETRIC_ENROLL)
        // Created before the activity resumes, so the gate's own tracker does see it: the 1.6.0 code
        // started enrolment from exactly that activity.
        val gate: BiometricGate = createBiometricGate(application)
        val activity: RecordingActivity = launch(RecordingActivity::class.java).setup().get()

        assertEquals(BiometricEnrollmentLaunch.LAUNCHED, gate.launchEnrollment())

        assertEquals(emptyList(), activity.starts, "nothing is started from the resumed activity")
        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
        assertNull(shadowOf(application).nextStartedActivity, "exactly one screen is started")
    }

    @Test
    fun `a device resolving no candidate reports unavailable and starts nothing`() = runTest {
        assertEquals(BiometricEnrollmentLaunch.UNAVAILABLE, createBiometricGate(application).launchEnrollment())

        assertNull(shadowOf(application).nextStartedActivity)
    }

    // --- createBiometricGate(context, activityAccess, …) ---

    @Test
    fun `the tracker overload hosts the prompt through the tracker it was given`() = runTest {
        val tracker = FakeActivityAccess(activity = null)
        val gate: BiometricGate = createBiometricGate(application, tracker)

        assertEquals(BiometricResult.NoPromptHost, gate.authenticate(PROMPT))
        assertTrue(tracker.withActivityCalls > 0, "the prompt host was asked of the app's tracker")
        assertEquals(0, tracker.releaseCalls, "the gate does not own the app's tracker")
    }

    @Test
    fun `the tracker overload honours the tracker's isTracked predicate`() = runTest {
        // A tracker that tracks nothing: a resumed FragmentActivity could host the prompt, but not
        // through this tracker. A gate with a tracker of its own would have found it.
        val tracker: ActivityAccess = createActivityAccess(application) { false }
        try {
            val gate: BiometricGate = createBiometricGate(application, tracker)
            launch(FragmentActivity::class.java).setup()

            assertEquals(BiometricResult.NoPromptHost, gate.authenticate(PROMPT))
        } finally {
            tracker.release()
        }
    }

    @Test
    fun `the tracker overload still starts enrolment in a task of its own`() = runTest {
        registerScreen(Settings.ACTION_BIOMETRIC_ENROLL)
        val activity: RecordingActivity = launch(RecordingActivity::class.java).setup().get()
        val gate: BiometricGate = createBiometricGate(application, FakeActivityAccess(activity))

        assertEquals(BiometricEnrollmentLaunch.LAUNCHED, gate.launchEnrollment())

        assertEquals(emptyList(), activity.starts, "the tracker hosts the prompt, not the enrolment start")
        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
        assertNull(shadowOf(application).nextStartedActivity, "exactly one screen is started")
    }

    @Test
    fun `the tracker overload rejects the weak tier together with the device credential`() {
        assertFailsWith<IllegalArgumentException> {
            createBiometricGate(
                application,
                FakeActivityAccess(activity = null),
                BiometricGateConfig(policy = BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL),
                BiometricGateOptions(strength = BiometricStrength.WEAK),
            )
        }
    }

    // --- createBiometricGateWithLauncher ---

    @Test
    fun `the launcher factory hands the app's launcher one request of the enrolment kind`() = runTest {
        val requests: MutableList<SystemScreenRequest> = mutableListOf()
        val gate: BiometricGate = createBiometricGateWithLauncher(
            application,
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
    fun `the launcher factory passes the options' tier to the wizard`() = runTest {
        val requests: MutableList<SystemScreenRequest> = mutableListOf()
        val gate: BiometricGate = createBiometricGateWithLauncher(
            application,
            SystemScreenLauncher { request -> requests += request; true },
            options = BiometricGateOptions(strength = BiometricStrength.WEAK),
        )

        gate.launchEnrollment()

        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_WEAK,
            requests.single().candidates.last().getIntExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, -1),
        )
    }

    @Test
    fun `the launcher factory throttles before the launcher`() = runTest {
        var calls = 0
        val gate: BiometricGate = createBiometricGateWithLauncher(application, SystemScreenLauncher { calls++; true })

        gate.launchEnrollment()

        assertEquals(BiometricEnrollmentLaunch.THROTTLED, gate.launchEnrollment())
        assertEquals(1, calls)
    }

    @Test
    fun `the launcher factory reports a launcher answering false as unavailable`() = runTest {
        var calls = 0
        val gate: BiometricGate = createBiometricGateWithLauncher(application, SystemScreenLauncher { calls++; false })

        assertEquals(BiometricEnrollmentLaunch.UNAVAILABLE, gate.launchEnrollment())
        assertEquals(1, calls, "one request, not one call per candidate")
    }

    @Test
    fun `the launcher factory reports a throwing launcher as unavailable`() = runTest {
        var calls = 0
        val gate: BiometricGate = createBiometricGateWithLauncher(
            application,
            SystemScreenLauncher { calls++; throw IllegalStateException("the app's launcher failed") },
        )

        assertEquals(BiometricEnrollmentLaunch.UNAVAILABLE, gate.launchEnrollment())
        assertEquals(1, calls)
    }

    @Test
    fun `the launcher factory with SeparateTask behaves like the default`() = runTest {
        registerScreen(Settings.ACTION_BIOMETRIC_ENROLL)
        val gate: BiometricGate = createBiometricGateWithLauncher(application, SystemScreenLauncher.SeparateTask)

        assertEquals(BiometricEnrollmentLaunch.LAUNCHED, gate.launchEnrollment())
        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
        assertNull(shadowOf(application).nextStartedActivity, "exactly one screen is started")
    }

    @Test
    fun `the launcher factory with callerTask starts enrolment from the resumed activity`() = runTest {
        val activity: RecordingActivity = launch(RecordingActivity::class.java).setup().get()
        val tracker = FakeActivityAccess(activity)
        val gate: BiometricGate = createBiometricGateWithLauncher(application, SystemScreenLauncher.callerTask(tracker))

        assertEquals(BiometricEnrollmentLaunch.LAUNCHED, gate.launchEnrollment())

        val started: Intent = activity.starts.single()
        assertEquals(0, started.flags, "on the app's own task: no task flags")
        assertTrue(started.action in ENROLMENT_ACTIONS, "started ${started.action}")
        assertNull(shadowOf(application).nextStartedActivity, "nothing else is started")
    }

    @Test
    fun `the launcher factory hosts the prompt through a tracker it was given`() = runTest {
        val tracker = FakeActivityAccess(activity = null)
        val gate: BiometricGate = createBiometricGateWithLauncher(
            application,
            SystemScreenLauncher.SeparateTask,
            activityAccess = tracker,
        )

        assertEquals(BiometricResult.NoPromptHost, gate.authenticate(PROMPT))
        assertTrue(tracker.withActivityCalls > 0, "the prompt host was asked of the app's tracker")
    }

    @Test
    fun `the launcher factory rejects the weak tier together with the device credential`() {
        assertFailsWith<IllegalArgumentException> {
            createBiometricGateWithLauncher(
                application,
                SystemScreenLauncher.SeparateTask,
                BiometricGateConfig(policy = BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL),
                BiometricGateOptions(strength = BiometricStrength.WEAK),
            )
        }
    }

    // --- overload resolution ---

    @Test
    fun `every documented call shape resolves to exactly one factory`() {
        // Compiling this test is the assertion: an ambiguous call, or one that resolves to a different
        // overload than intended, fails the build (the expected types pin which one was chosen).
        val config = BiometricGateConfig()
        val options = BiometricGateOptions()
        val tracker = FakeActivityAccess(activity = null)

        val gates: List<BiometricGate> = listOf(
            createBiometricGate(application),
            createBiometricGate(application, config),
            createBiometricGate(application, config = config),
            createBiometricGate(application, config, options),
            createBiometricGate(application, config = config, options = options),
            createBiometricGate(application, tracker),
            createBiometricGate(application, tracker, config),
            createBiometricGate(application, tracker, options = options),
            createBiometricGate(application, activityAccess = tracker, config = config, options = options),
            createBiometricGateWithLauncher(application, SystemScreenLauncher.SeparateTask),
            createBiometricGateWithLauncher(application, SystemScreenLauncher.callerTask(tracker), config, options),
            createBiometricGateWithLauncher(application, SystemScreenLauncher.SeparateTask, activityAccess = tracker),
        )
        assertEquals(12, gates.size)

        // Typed references pick one overload each; the new name has a single declaration, so even an
        // untyped reference (a DI `singleOf(::createBiometricGateWithLauncher)`) is unambiguous.
        val twoArgument: (Context, BiometricGateConfig) -> BiometricGate = ::createBiometricGate
        val threeArgument: (Context, BiometricGateConfig, BiometricGateOptions) -> BiometricGate = ::createBiometricGate
        val withTracker: (Context, ActivityAccess, BiometricGateConfig, BiometricGateOptions) -> BiometricGate =
            ::createBiometricGate
        val withLauncher = ::createBiometricGateWithLauncher
        assertNotNull(twoArgument(application, config))
        assertNotNull(threeArgument(application, config, options))
        assertNotNull(withTracker(application, tracker, config, options))
        assertNotNull(withLauncher(application, SystemScreenLauncher.SeparateTask, config, options, null))
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

    /** A real, resumable activity that records the starts made from it instead of performing them. */
    class RecordingActivity : Activity() {
        val starts: MutableList<Intent> = mutableListOf()

        override fun startActivity(intent: Intent, options: Bundle?) {
            starts += intent
        }
    }

    /** An app's tracker, as far as the gate can tell: counts how it is used. */
    private class FakeActivityAccess(private val activity: Activity?) : ActivityAccess {
        var withActivityCalls: Int = 0
        var releaseCalls: Int = 0

        override fun <R> withActivity(block: (Activity) -> R): R? {
            withActivityCalls++
            return activity?.let(block)
        }

        override fun addOnActivityResumedListener(listener: (Activity) -> Unit): ActivitySubscription =
            object : ActivitySubscription {
                override fun cancel(): Unit = Unit
            }

        override fun release() {
            releaseCalls++
        }
    }

    private companion object {
        val PROMPT = BiometricPromptText(title = "Unlock", subtitle = "Confirm it is you", cancelLabel = "Cancel")
        const val SEPARATE_TASK: Int = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT
        val ENROLMENT_ACTIONS: Set<String> = setOf(ACTION_COMBINED_BIOMETRICS_SETTINGS, Settings.ACTION_BIOMETRIC_ENROLL)
    }
}
