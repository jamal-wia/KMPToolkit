package io.github.jamal_wia.kmptoolkit.location

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.ActivitySubscription
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenRequest
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * `openLocationSettings()` through the real factories — the task the screen lands in is what the
 * contract promises, and a unit test sees it as the flags on the started intent. Robolectric fails
 * unresolvable starts (`checkActivities`), so "the device has no such screen" is the platform's own
 * `ActivityNotFoundException`, not a simulation of it.
 */
@RunWith(AndroidJUnit4::class)
class OpenLocationSettingsTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val activityAccess: ActivityAccess = createActivityAccess(application)
    private val controllers: MutableList<ActivityController<out Activity>> = mutableListOf()
    private val logger = RecordingLogger()

    @BeforeTest
    fun setUp() {
        shadowOf(application).checkActivities(true)
    }

    @AfterTest
    fun tearDown() {
        activityAccess.release()
        controllers.forEach { controller -> controller.close() }
    }

    // --- The Context-only factory: a separate task ---

    @Test
    fun `the default factory opens location settings in a task of its own`() {
        registerLocationSettingsScreen()

        createLocationProvider(application).openLocationSettings()

        val started: Intent = assertNotNull(shadowOf(application).nextStartedActivity)
        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, started.action)
        assertEquals(SEPARATE_TASK, started.flags)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `the default factory keeps a separate task even when an activity is resumed`() {
        registerLocationSettingsScreen()
        // Created before the activity resumes: a factory that quietly tracked activities itself
        // would see this resume and launch from the activity.
        val provider: LocationProvider = createLocationProvider(application, LocationProviderConfig(), logger)
        resumedActivity()

        provider.openLocationSettings()

        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
        assertNull(shadowOf(application).nextStartedActivity)
        assertTrue(logger.warnings.isEmpty())
    }

    @Test
    fun `the default factory with named arguments still resolves to the separate task factory`() {
        registerLocationSettingsScreen()

        createLocationProvider(context = application, logger = logger).openLocationSettings()

        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `the default factory is still a single function an untyped reference can name`() {
        registerLocationSettingsScreen()
        // Compiles only while createLocationProvider has exactly one overload — what DI modules such
        // as `singleOf(::createLocationProvider)` rely on.
        val factory = ::createLocationProvider
        val withLauncher = ::createLocationProviderWithLauncher

        factory(application, LocationProviderConfig(), logger).openLocationSettings()
        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
        assertNull(shadowOf(application).nextStartedActivity)

        withLauncher(application, SystemScreenLauncher.SeparateTask, LocationProviderConfig(), logger)
            .openLocationSettings()
        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
        assertNull(shadowOf(application).nextStartedActivity)
        assertTrue(logger.warnings.isEmpty())
    }

    @Test
    fun `the default factory reports a device without the screen and does not throw`() {
        createLocationProvider(application, LocationProviderConfig(), logger).openLocationSettings()

        assertNull(shadowOf(application).nextStartedActivity)
        assertEquals(1, logger.warnings.size)
        assertEquals(listOf(COULD_NOT_OPEN), logger.messages)
    }

    // --- WithLauncher + callerTask: the caller's task ---

    @Test
    fun `callerTask opens location settings from the resumed activity without task flags`() {
        registerLocationSettingsScreen()
        val activity: RecordingActivity =
            Robolectric.buildActivity(RecordingActivity::class.java).also { controllers.add(it) }.setup().get()
        val access: ActivityAccess = FixedActivityAccess(activity)

        createLocationProviderWithLauncher(application, SystemScreenLauncher.callerTask(access), logger = logger)
            .openLocationSettings()

        val (startedFrom: Activity, started: Intent) = activity.starts.single()
        assertSame(activity, startedFrom)
        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, started.action)
        assertEquals(0, started.flags)
        assertNull(shadowOf(application).nextStartedActivity)
        assertTrue(logger.warnings.isEmpty())
    }

    @Test
    fun `callerTask uses the activityAccess it is given`() {
        registerLocationSettingsScreen()
        // A tracker that accepts no activity: the resumed one below must not be launched from.
        val nothingTracked: ActivityAccess = createActivityAccess(application) { false }
        try {
            val provider: LocationProvider =
                createLocationProviderWithLauncher(application, SystemScreenLauncher.callerTask(nothingTracked))
            resumedActivity()

            provider.openLocationSettings()

            assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
            assertNull(shadowOf(application).nextStartedActivity)
        } finally {
            nothingTracked.release()
        }
    }

    @Test
    fun `callerTask falls back to a separate task when no activity is resumed`() {
        registerLocationSettingsScreen()

        createLocationProviderWithLauncher(application, SystemScreenLauncher.callerTask(activityAccess), logger = logger)
            .openLocationSettings()

        val started: Intent = assertNotNull(shadowOf(application).nextStartedActivity)
        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, started.action)
        assertEquals(SEPARATE_TASK, started.flags)
        assertNull(shadowOf(application).nextStartedActivity)
        assertTrue(logger.warnings.isEmpty())
    }

    // --- A launcher of the app's own ---

    @Test
    fun `a custom launcher is called once per request with the location settings candidate and kind`() {
        val requests: MutableList<SystemScreenRequest> = mutableListOf()
        val provider: LocationProvider = createLocationProviderWithLauncher(
            application,
            SystemScreenLauncher { request -> requests.add(request) },
            LocationProviderConfig(),
            logger,
        )

        provider.openLocationSettings()

        val request: SystemScreenRequest = requests.single()
        assertSame(LocationSettingsScreen, request.kind)
        assertEquals(listOf<String?>(Settings.ACTION_LOCATION_SOURCE_SETTINGS), request.candidates.map { it.action })
        assertEquals(0, request.candidates.single().flags)
        assertSame(application, request.applicationContext)
        assertNull(shadowOf(application).nextStartedActivity)
        assertTrue(logger.warnings.isEmpty())

        provider.openLocationSettings()

        assertEquals(2, requests.size)
    }

    @Test
    fun `a launcher that opens nothing is reported as could not open`() {
        var calls = 0

        createLocationProviderWithLauncher(
            application,
            SystemScreenLauncher {
                calls++
                false
            },
            logger = logger,
        ).openLocationSettings()

        assertEquals(1, calls)
        assertEquals(1, logger.warnings.size)
        assertEquals(listOf(COULD_NOT_OPEN), logger.messages)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `a launcher that throws is reported as could not open with the cause and does not reach the caller`() {
        val failure = IllegalStateException("launcher bug")

        createLocationProviderWithLauncher(application, SystemScreenLauncher { throw failure }, logger = logger)
            .openLocationSettings()

        assertSame(failure, logger.warnings.single())
        assertEquals(listOf(COULD_NOT_OPEN), logger.messages)
    }

    @Test
    fun `the location settings kind names itself`() {
        assertEquals("LocationSettingsScreen", LocationSettingsScreen.toString())
    }

    // --- helpers ---

    private fun resumedActivity(): Activity =
        Robolectric.buildActivity(Activity::class.java).also { controllers.add(it) }.setup().get()

    /** An [ActivityAccess] that always answers with [activity]. */
    private class FixedActivityAccess(private val activity: Activity) : ActivityAccess {
        override fun <R> withActivity(block: (Activity) -> R): R = block(activity)
        override fun addOnActivityResumedListener(listener: (Activity) -> Unit): ActivitySubscription =
            object : ActivitySubscription {
                override fun cancel() = Unit
            }
        override fun release() = Unit
    }

    private fun registerLocationSettingsScreen() {
        val component = ComponentName("com.android.settings", "com.android.settings.LocationSettings")
        shadowOf(application.packageManager).apply {
            addActivityIfNotPresent(component)
            // startActivity resolves with CATEGORY_DEFAULT, as the platform does.
            addIntentFilterForActivity(
                component,
                IntentFilter(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply { addCategory(Intent.CATEGORY_DEFAULT) },
            )
        }
    }

    /** Records the throwable (a placeholder when there is none) and message of every warning. */
    private class RecordingLogger : Logger {
        val warnings: MutableList<Throwable> = mutableListOf()
        val messages: MutableList<String> = mutableListOf()
        override val tag: String = "test"
        override fun isLoggable(level: LogLevel): Boolean = true
        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            val text: String = message()
            if (level == LogLevel.WARN) {
                warnings.add(throwable ?: NoThrowable)
                messages.add(text)
            }
        }
    }

    private object NoThrowable : Throwable()

    private companion object {
        const val COULD_NOT_OPEN: String = "Could not open the location settings screen"
        const val SEPARATE_TASK: Int = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT
    }
}

/**
 * A real activity (built and resumed by Robolectric) that records every start made from it instead
 * of performing it — proof of the starting context that the shared Robolectric start queue cannot
 * give, since `shadowOf(activity)` and `shadowOf(application)` read the same queue.
 */
class RecordingActivity : Activity() {
    val starts: MutableList<Pair<Activity, Intent>> = mutableListOf()

    override fun startActivity(intent: Intent, options: Bundle?) {
        starts.add(this to Intent(intent))
    }
}
