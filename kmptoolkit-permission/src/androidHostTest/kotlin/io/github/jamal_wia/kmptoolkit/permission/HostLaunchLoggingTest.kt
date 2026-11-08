package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/**
 * `ActivityResultPermissionRequestHost`'s `logger` is "where a launch that could not be made is reported":
 * every `false` is explained once, and a launch that was made says nothing.
 */
@RunWith(AndroidJUnit4::class)
class HostLaunchLoggingTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val access: ActivityAccess = createActivityAccess(application)
    private val logger = RecordingLogger()
    private val activities: MutableList<ActivityController<out Activity>> = mutableListOf()

    @AfterTest
    fun tearDown() {
        access.release()
        activities.forEach { it.close() }
    }

    private fun warnings(): List<RecordingLogger.Entry> = logger.entries.filter { it.level == LogLevel.WARN }

    @Test
    fun `a launch with no resumed activity is reported once and a made launch says nothing`() {
        val host = ActivityResultPermissionRequestHost(access, logger)

        assertFalse(host.launch(listOf(Manifest.permission.CAMERA)) {})
        assertEquals(1, warnings().size, "${logger.entries}")

        Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()
        logger.entries.clear()
        assertTrue(host.launch(listOf(Manifest.permission.CAMERA)) {})
        assertEquals(emptyList(), logger.entries)
    }

    @Test
    fun `a launch refused because one is pending is reported`() {
        Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()
        val host = ActivityResultPermissionRequestHost(access, logger)
        assertTrue(host.launch(listOf(Manifest.permission.CAMERA)) {})
        logger.entries.clear()

        assertFalse(host.launch(listOf(Manifest.permission.RECORD_AUDIO)) {})

        assertEquals(1, warnings().size, "${logger.entries}")
    }
}
