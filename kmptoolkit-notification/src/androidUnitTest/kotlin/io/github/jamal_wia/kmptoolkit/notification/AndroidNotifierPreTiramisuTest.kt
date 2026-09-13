package io.github.jamal_wia.kmptoolkit.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.RecordingPermissionHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Below API 33 `POST_NOTIFICATIONS` does not exist, so nothing a [PermissionHandler] says about it
 * may keep a notification from being posted there.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [32])
class AndroidNotifierPreTiramisuTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val notification = LocalNotification(
        title = "Title",
        body = "Body",
        channel = NotificationChannelSpec(id = "general", name = "General"),
    )

    @Test
    fun `a handler reporting the permission denied does not block a post below API 33`() = runTest {
        val permissions = RecordingPermissionHandler(defaultStatus = PermissionStatus.Denied())

        val result: NotificationResult = createNotifier(context, permissions).post("n", notification)

        assertEquals(NotificationResult.Posted, result)
    }

    @Test
    fun `the handler is not even asked below API 33`() = runTest {
        val permissions = RecordingPermissionHandler(defaultStatus = PermissionStatus.Granted)

        createNotifier(context, permissions).post("n", notification)

        assertEquals(emptyList(), permissions.checks.filter { it == Permission.NOTIFICATIONS })
    }
}
