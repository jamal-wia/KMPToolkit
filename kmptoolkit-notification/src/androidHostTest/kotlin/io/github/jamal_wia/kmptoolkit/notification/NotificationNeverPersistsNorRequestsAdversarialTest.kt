package io.github.jamal_wia.kmptoolkit.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.CommonPermission
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * Round 15 (contract lawyer): the sentences that say what this module never does.
 *
 * - 05-platform-notes "Both platforms": "Nothing is persisted. The module has no database, no list of
 *   what it has posted, and nothing to restore after a reboot or a process death".
 * - 01-overview: "(to check `CommonPermission.Notifications`, never to request it)", "Not a permission
 *   requester. It checks `POST_NOTIFICATIONS` and reports what it found; it never shows a system prompt."
 */
@RunWith(AndroidJUnit4::class)
class NotificationNeverPersistsNorRequestsAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val channel = NotificationChannelSpec(id = "never", name = "Never", description = "d")

    private fun appFiles(): Map<String, Long> {
        val roots: List<File> = listOfNotNull(File(context.applicationInfo.dataDir), context.getExternalFilesDir(null))
        return roots.flatMap { dir: File ->
            dir.walkTopDown().filter { it.isFile }.map { file: File -> file.absolutePath to file.length() }.toList()
        }.toMap()
    }

    private fun workload(notifier: Notifier) = runBlocking {
        for (round in 0 until 5) {
            for (i in 0 until 30) {
                notifier.post(
                    "id-$i",
                    LocalNotification(
                        title = "t$round", body = "b", channel = channel,
                        progress = NotificationProgress.Determinate(round * 20),
                        actions = listOf(NotificationAction("stop", "Stop")),
                        contentExtras = mapOf("k" to "v"),
                    ),
                    NotificationOptions(alertOnce = false, dismissAction = NotificationAction("stop", "Stop"), mediaStyle = true),
                )
            }
            notifier.cancel("id-${round}")
        }
        notifier.cancelAll()
    }

    @Test
    fun `posting updating cancelling and building a foreground notification write no file and no preference`() {
        val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
        val before: Map<String, Long> = appFiles()
        val notifier: Notifier = createNotifier(context, permissions, NotificationConfig(minProgressInterval = kotlin.time.Duration.ZERO))

        workload(notifier)
        NotificationChannels.ensure(context, channel)
        buildForegroundNotification(context, ForegroundNotificationId("fg", 77), LocalNotification("t", "b", channel))
        NotificationChannels.delete(context, channel.id)

        assertEquals(before, appFiles(), "the notifier wrote to the app's data directory, but it persists nothing")
        assertTrue(context.getSharedPreferences("any", Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test
    fun `the notifier only ever checks the notification permission and never requests batches or opens settings`() {
        for (status in listOf<PermissionStatus>(PermissionStatus.Granted, PermissionStatus.Denied(), PermissionStatus.Limited)) {
            val permissions = FakePermissionController(defaultStatus = status)
            val notifier: Notifier = createNotifier(context, permissions, NotificationConfig(minProgressInterval = kotlin.time.Duration.ZERO))

            workload(notifier)

            assertEquals(emptyList(), permissions.requests, "status $status: the notifier requested a permission")
            assertEquals(emptyList(), permissions.batchRequests, "status $status: the notifier requested a batch of permissions")
            assertEquals(emptyList(), permissions.openedSettings, "status $status: the notifier opened a settings screen")
            assertTrue(permissions.checks.isNotEmpty(), "status $status: the gate must ask the controller (API 35)")
            assertTrue(
                permissions.checks.all { it == CommonPermission.Notifications },
                "status $status: the notifier checked ${permissions.checks.toSet()}, documented: only Notifications",
            )
        }
    }
}
