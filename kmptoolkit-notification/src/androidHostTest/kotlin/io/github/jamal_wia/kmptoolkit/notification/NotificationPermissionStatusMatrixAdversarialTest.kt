package io.github.jamal_wia.kmptoolkit.notification

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.CommonPermission
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.isUsable
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Every [PermissionStatus] a controller can answer, through the notifier, on both sides of API 33
 * (where `POST_NOTIFICATIONS` appears). From 33 a status that the permission module calls usable
 * (`Granted`, `Limited`) posts and every other one is [NotificationResult.PermissionDenied], including
 * the ones that are not a refusal (`NotDetermined`: the app has not asked yet; `Restricted`,
 * `Unavailable`): the notifier never prompts and never guesses. Below 33 there is nothing to grant, so
 * whatever the controller says changes nothing, and the controller is not even asked.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [32, 33, 35])
class NotificationPermissionStatusMatrixAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val channel = NotificationChannelSpec(id = "perm", name = "Perm")

    private val statuses: List<PermissionStatus> = listOf(
        PermissionStatus.Granted,
        PermissionStatus.Limited,
        PermissionStatus.Denied(),
        PermissionStatus.Denied(explainFirst = true),
        PermissionStatus.PermanentlyDenied,
        PermissionStatus.NotDetermined,
        PermissionStatus.Restricted,
        PermissionStatus.Unavailable,
    )

    @Test
    fun `each status decides the post exactly as its usability says from API 33 and not at all below`() = runTest {
        for (status: PermissionStatus in statuses) {
            manager.cancelAll()
            val permissions = FakePermissionController(defaultStatus = status)
            val notifier: Notifier = createNotifier(context, permissions, NotificationConfig(minProgressInterval = Duration.ZERO))

            val result: NotificationResult = notifier.post("n", LocalNotification("t", "b", channel))

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                assertEquals(NotificationResult.Posted, result, "$status below 33")
                assertEquals(emptyList(), permissions.checks, "the controller must not be asked below 33")
            } else if (status.isUsable) {
                assertEquals(NotificationResult.Posted, result, "$status from 33")
            } else {
                assertEquals(NotificationResult.PermissionDenied, result, "$status from 33")
                assertTrue(shadowOf(manager).allNotifications.isEmpty(), "$status must post nothing")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                assertEquals(listOf(CommonPermission.Notifications), permissions.checks, "only the notification permission is checked")
                assertEquals(emptyList(), permissions.requests, "never a prompt")
            }
        }
    }

    @Test
    fun `a status that changes between two posts is honoured by the second`() = runTest {
        val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
        val notifier: Notifier = createNotifier(context, permissions, NotificationConfig(minProgressInterval = Duration.ZERO))
        assertEquals(NotificationResult.Posted, notifier.post("n", LocalNotification("t", "b", channel)))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@runTest

        permissions.setStatus(CommonPermission.Notifications, PermissionStatus.PermanentlyDenied)
        assertEquals(NotificationResult.PermissionDenied, notifier.post("n", LocalNotification("t2", "b", channel)))
        permissions.setStatus(CommonPermission.Notifications, PermissionStatus.Limited)
        assertEquals(NotificationResult.Posted, notifier.post("n", LocalNotification("t3", "b", channel)))
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }
}
