package io.github.jamal_wia.kmptoolkit.location

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
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
    private val controllers: MutableList<ActivityController<Activity>> = mutableListOf()
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
    }

    @Test
    fun `the default factory keeps a separate task even when an activity is resumed`() {
        registerLocationSettingsScreen()
        resumedActivity()

        createLocationProvider(application, LocationProviderConfig(), logger).openLocationSettings()

        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
        assertTrue(logger.warnings.isEmpty())
    }

    @Test
    fun `the default factory with named arguments still resolves to the separate task overload`() {
        registerLocationSettingsScreen()

        createLocationProvider(context = application, logger = logger).openLocationSettings()

        assertEquals(SEPARATE_TASK, assertNotNull(shadowOf(application).nextStartedActivity).flags)
    }

    @Test
    fun `the default factory reports a device without the screen and does not throw`() {
        createLocationProvider(application, LocationProviderConfig(), logger).openLocationSettings()

        assertNull(shadowOf(application).nextStartedActivity)
        assertEquals(1, logger.warnings.size)
    }

    // --- The ActivityAccess factory: the caller's task ---

    @Test
    fun `the activityAccess factory opens location settings from the resumed activity without task flags`() {
        registerLocationSettingsScreen()
        val activity: Activity = resumedActivity()

        createLocationProvider(application, activityAccess).openLocationSettings()

        val started: Intent = assertNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, started.action)
        assertEquals(0, started.flags)
    }

    @Test
    fun `the activityAccess factory falls back to a separate task when no activity is resumed`() {
        registerLocationSettingsScreen()

        createLocationProvider(application, activityAccess, LocationProviderConfig(), logger).openLocationSettings()

        val started: Intent = assertNotNull(shadowOf(application).nextStartedActivity)
        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, started.action)
        assertEquals(SEPARATE_TASK, started.flags)
        assertTrue(logger.warnings.isEmpty())
    }

    // --- A launcher of the app's own ---

    @Test
    fun `a custom launcher is called once per request with the location settings candidate and kind`() {
        val requests: MutableList<SystemScreenRequest> = mutableListOf()
        val provider: LocationProvider = createLocationProvider(
            application,
            LocationProviderConfig(),
            logger,
            SystemScreenLauncher { request -> requests.add(request) },
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
        createLocationProvider(application, LocationProviderConfig(), logger, SystemScreenLauncher { false })
            .openLocationSettings()

        assertEquals(1, logger.warnings.size)
    }

    @Test
    fun `a launcher that throws is reported as could not open and does not reach the caller`() {
        val failure = IllegalStateException("launcher bug")

        createLocationProvider(application, LocationProviderConfig(), logger, SystemScreenLauncher { throw failure })
            .openLocationSettings()

        assertSame(failure, logger.warnings.single())
    }

    @Test
    fun `the location settings kind names itself`() {
        assertEquals("LocationSettingsScreen", LocationSettingsScreen.toString())
    }

    // --- helpers ---

    private fun resumedActivity(): Activity =
        Robolectric.buildActivity(Activity::class.java).also { controllers.add(it) }.setup().get()

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

    /** Records the throwable of every warning (a placeholder when there is none). */
    private class RecordingLogger : Logger {
        val warnings: MutableList<Throwable> = mutableListOf()
        override val tag: String = "test"
        override fun isLoggable(level: LogLevel): Boolean = true
        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            message()
            if (level == LogLevel.WARN) warnings.add(throwable ?: NoThrowable)
        }
    }

    private object NoThrowable : Throwable()

    private companion object {
        const val SEPARATE_TASK: Int = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT
    }
}
