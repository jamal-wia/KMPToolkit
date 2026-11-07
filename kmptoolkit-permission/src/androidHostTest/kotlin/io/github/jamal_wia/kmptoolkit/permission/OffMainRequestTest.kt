package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivitySubscription
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Requests made from a thread that is not the main one: the work that has to happen on main is posted
 * there, and the main looper is paused here, so the test decides when the posted work runs.
 */
@RunWith(AndroidJUnit4::class)
class OffMainRequestTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val looper = shadowOf(Looper.getMainLooper())

    /** Resume listeners as the real tracker keeps them: the immediate call is posted when added off main. */
    private class TrackerLikeListeners : ResumeListeners() {
        override fun add(listener: () -> Unit): ActivitySubscription {
            val subscription: ActivitySubscription = super.add(listener)
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Handler(Looper.getMainLooper()).post { listener() }
            }
            return subscription
        }
    }

    private fun awaitPosted() {
        val deadline: Long = System.nanoTime() + 5_000_000_000L
        while (looper.isIdle && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(!looper.isIdle, "nothing was posted to the main looper")
    }

    @Test
    fun `a late replay of the resumed activity does not end the wait while the screen is open`() = runBlocking {
        shadowOf(application.packageManager).setCanRequestPackageInstalls(false)
        val listeners = TrackerLikeListeners()
        val controller: AndroidPermissionController = testController(
            context = application,
            storage = InMemoryKeyValueStorage(),
            sdkInt = android.os.Build.VERSION.SDK_INT,
            resumeListeners = listeners,
            launchScreen = { true },
        )

        val request: Deferred<PermissionStatus> =
            async(Dispatchers.Default) {
                controller.request(AndroidPermission.InstallPackages)
            }
        awaitPosted()
        Thread.sleep(100) // old code: the screen is open and the wait armed by now
        looper.idle() // the main thread gets to its queue: the replay (old code) or the whole open (new code)

        assertNull(withTimeoutOrNull(300) { request.await() }, "the wait ended before the user came back")
        listeners.fire() // the user is back
        assertTrue(withTimeoutOrNull(5_000) { request.await() } is PermissionStatus.Denied)
    }

    @Test
    fun `a request cancelled before its dialog is posted never shows the dialog`() = runBlocking {
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA)
        val host = RecordingHost()
        val controller: AndroidPermissionController = testController(
            context = application,
            storage = InMemoryKeyValueStorage(),
            host = host,
        )

        val request: Deferred<PermissionStatus> =
            async(Dispatchers.Default) { controller.request(CommonPermission.Camera) }
        awaitPosted()
        request.cancelAndJoin()
        looper.idle()

        assertEquals(0, host.launchCount, "a dialog was shown for a request nobody waits for")
    }
}
