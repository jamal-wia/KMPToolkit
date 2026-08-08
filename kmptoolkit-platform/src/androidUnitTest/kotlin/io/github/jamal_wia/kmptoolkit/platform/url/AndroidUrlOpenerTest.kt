package io.github.jamal_wia.kmptoolkit.platform.url

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class AndroidUrlOpenerTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val opener: UrlOpener = createUrlOpener(application)

    @Test
    fun `opening an https url starts a view intent for it`() {
        assertEquals(UrlOpenResult.OPENED, opener.open("https://example.com/privacy"))

        val started: Intent = assertNotNull(shadowOf(application).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://example.com/privacy", started.data.toString())
    }

    @Test
    fun `the intent carries the new-task flag an application context requires`() {
        opener.open("https://example.com")

        val started: Intent = assertNotNull(shadowOf(application).nextStartedActivity)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `a custom scheme is handed to the platform unchanged`() {
        assertEquals(UrlOpenResult.OPENED, opener.open("myapp://open/thing"))

        assertEquals(
            "myapp://open/thing",
            assertNotNull(shadowOf(application).nextStartedActivity).data.toString(),
        )
    }

    @Test
    fun `an empty url is rejected before the platform sees it`() {
        assertEquals(UrlOpenResult.INVALID_URL, opener.open(""))

        assertNull(shadowOf(application).nextStartedActivity, "nothing should have been launched")
    }

    @Test
    fun `a relative path is rejected`() {
        assertEquals(UrlOpenResult.INVALID_URL, opener.open("/help/privacy"))
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `a bare host is rejected rather than guessed at`() {
        assertEquals(UrlOpenResult.INVALID_URL, opener.open("example.com"))
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `a url nothing can handle reports no handler instead of throwing`() {
        // Makes Robolectric behave like a device with no matching activity.
        shadowOf(application).checkActivities(true)

        assertEquals(UrlOpenResult.NO_HANDLER, opener.open("nothinghandlesthis://x"))
    }
}
