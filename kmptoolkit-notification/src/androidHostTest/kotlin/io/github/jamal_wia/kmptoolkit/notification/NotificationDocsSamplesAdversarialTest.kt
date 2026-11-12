package io.github.jamal_wia.kmptoolkit.notification

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Round 12 (documentation as a test): the samples of docs/kmptoolkit-notification (02-getting-started,
 * 03-guide) compiled against the public API and run on a real NotificationManager (Robolectric). The
 * parts of a sample that belong to the app (strings, a download service) are plain values here; nothing
 * documented is changed.
 */
@RunWith(AndroidJUnit4::class)
class NotificationDocsSamplesAdversarialTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val shown: List<Notification> get() = shadowOf(manager).allNotifications

    private fun title(n: Notification): String = n.extras.getString(Notification.EXTRA_TITLE).orEmpty()

    /** This module has no launcher activity of its own; a real app resolves one (as AndroidNotifierTest does). */
    private fun registerLauncherActivity() {
        val component = android.content.ComponentName(context.packageName, "com.example.DocsLauncher")
        val packageManager = shadowOf(context.packageManager)
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(
            component,
            android.content.IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    @Test
    fun `getting started steps 5 and 6 - the channel, the post, the replace, the cancel and the exhaustive when`() {
        registerLauncherActivity()
        val notifier: Notifier = createNotifier(context = context, permissions = permissions)
        val remindersChannel = NotificationChannelSpec(
            id = "reminders",
            name = "Reminders",
            description = "Study reminders",
            importance = NotificationImportance.Default,
        )
        val result: NotificationResult = runBlocking {
            notifier.post(
                id = "reminder-42",
                notification = LocalNotification(
                    title = "Time to study",
                    body = "Lesson 7",
                    channel = remindersChannel,
                    contentExtras = mapOf("lessonId" to "42"),
                ),
            )
        }
        val handled: String = when (result) {
            NotificationResult.Posted, NotificationResult.Coalesced -> "ok"
            NotificationResult.PermissionDenied -> "prompt"
            NotificationResult.NotificationsDisabled -> "settings"
            is NotificationResult.ChannelBlocked -> "settings"
            is NotificationResult.Failed -> "failed ${result.cause}"
        }
        assertEquals("ok", handled)
        val channel = assertNotNull(manager.getNotificationChannel("reminders"))
        assertEquals("Reminders", channel.name.toString())
        assertEquals("Study reminders", channel.description)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertEquals(listOf("Time to study"), shown.map(::title))
        // "tapping opens the app with these extras": the tap target exists.
        assertNotNull(shown.single().contentIntent, "contentExtras non-null means a tap target")

        // `id` is yours: re-posting it replaces in place, cancel takes it down.
        runBlocking { notifier.post("reminder-42", LocalNotification("Again", "b", remindersChannel)) }
        assertEquals(listOf("Again"), shown.map(::title))
        assertNull(shown.single().contentIntent, "contentExtras = null (the default) means the notification does nothing when tapped")
        notifier.cancel("reminder-42")
        assertEquals(emptyList(), shown)
    }

    @Test
    fun `step 4 - posting before the permission is granted answers PermissionDenied, as documented, and posts nothing`() {
        val denied = FakePermissionController(defaultStatus = PermissionStatus.Denied())
        val notifier: Notifier = createNotifier(context = context, permissions = denied)
        val result: NotificationResult = runBlocking {
            notifier.post("x", LocalNotification("t", "b", NotificationChannelSpec("c", "C")))
        }
        // The test runs at the default SDK (35, above 33), where the runtime grant is required.
        assertEquals(NotificationResult.PermissionDenied, result)
        assertEquals(emptyList(), shown)
    }

    @Test
    fun `the guide's download run - a whole run, the finishing frame replaces it, and the next run starts fresh`() {
        val time = TestTimeSource()
        val notifier: Notifier = AndroidNotifier(
            context, permissions, NotificationRenderer(context, "test.ACTION"),
            ProgressCoalescer(bucketPercent = 10, minInterval = 500.milliseconds, timeSource = time),
        )
        val downloadsChannel = NotificationChannelSpec("downloads", "Downloads", importance = NotificationImportance.Low)

        suspend fun onBytes(percent: Int): NotificationResult = notifier.post(
            id = "download",
            notification = LocalNotification(
                title = "Downloading",
                body = "$percent %",
                channel = downloadsChannel,
                progress = NotificationProgress.Determinate(percent),
                ongoing = true,
            ),
        )

        suspend fun onFinished(): NotificationResult = notifier.post(
            id = "download",
            notification = LocalNotification(
                title = "Download complete",
                body = "file.bin",
                channel = downloadsChannel,
                progress = null,
                ongoing = false,
                contentExtras = mapOf("screen" to "downloads"),
            ),
        )

        runBlocking {
            // The body repeats the percentage, so "something new" is said at every step: only the rate limit
            // holds a frame back. 600 ms between steps lets every one of the 101 frames through.
            var posted = 0
            for (percent in 0..100) {
                time += 600.milliseconds
                if (onBytes(percent) == NotificationResult.Posted) posted++
            }
            assertEquals(101, posted, "documented: a changed body is not held back by the bucket; only the rate limit applies")
            assertEquals(1, shown.size)
            assertEquals(100, shown.single().extras.getInt(Notification.EXTRA_PROGRESS))

            // A 100 percent frame reset the id: the next run starts fresh, so its first frame posts at once, and a
            // repeat inside the rate limit is held back. A terminal frame is never suppressed.
            assertEquals(NotificationResult.Posted, onBytes(30), "100 percent resets the id's state")
            assertEquals(NotificationResult.Coalesced, onBytes(30))
            assertEquals(NotificationResult.Posted, onFinished(), "a terminal frame is never suppressed")
            assertEquals(listOf("Download complete"), shown.map(::title))
            val finished: Notification = shown.single()
            assertEquals(false, finished.extras.containsKey(Notification.EXTRA_PROGRESS_MAX) &&
                finished.extras.getInt(Notification.EXTRA_PROGRESS_MAX) > 0, "no bar any more")
            assertFalse(finished.flags and Notification.FLAG_ONGOING_EVENT != 0, "ongoing = false after finishing")

            // "Both then reset the id's state, so the next run starts fresh": the first frame of a run posts at once.
            assertEquals(NotificationResult.Posted, onBytes(5))
            assertEquals(NotificationResult.Coalesced, onBytes(7), "same bucket, same text inside the interval")
        }
    }

    @Test
    fun `the guide's channel sample - delete the old id, ensure the rest at startup, no post needed`() {
        val remindersV2 = NotificationChannelSpec("reminders-v2", "Reminders", importance = NotificationImportance.High)
        val downloads = NotificationChannelSpec("downloads", "Downloads", importance = NotificationImportance.Low)
        NotificationChannels.ensure(context, NotificationChannelSpec("reminders", "Reminders (old)"))
        assertNotNull(manager.getNotificationChannel("reminders"))

        NotificationChannels.delete(context, "reminders")
        listOf(remindersV2, downloads).forEach { NotificationChannels.ensure(context, it) }

        assertNull(manager.getNotificationChannel("reminders"), "the replaced channel must not linger")
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel("reminders-v2")?.importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel("downloads")?.importance)
        // Deleting what does not exist is harmless, and ensure is idempotent.
        NotificationChannels.delete(context, "reminders")
        NotificationChannels.ensure(context, downloads)
        assertEquals(emptyList(), shown, "ensure and delete post nothing")
    }

    @Test
    fun `the guide's action receiver - the button's broadcast carries what the sample reads`() {
        val notifier: Notifier = createNotifier(context, permissions)
        val channel = NotificationChannelSpec("downloads", "Downloads")
        runBlocking {
            notifier.post(
                "download-9",
                LocalNotification("t", "b", channel, actions = listOf(NotificationAction(id = "cancel", label = "Cancel"))),
            )
        }
        val action: Notification.Action = shown.single().actions.single()
        val intent: Intent = shadowOf(action.actionIntent).savedIntent

        // The sample receiver's body, verbatim apart from the service call.
        var cancelled: String? = null
        val actionId: String? = NotificationActionIntent.actionId(intent)
        val notificationId: String? = NotificationActionIntent.notificationId(intent)
        when (actionId) {
            "cancel" -> cancelled = notificationId
        }
        assertEquals("download-9", cancelled)
        assertEquals(NotificationActionIntent.defaultAction(context), intent.action)
        assertEquals(context.packageName + ".KMPTOOLKIT_NOTIFICATION_ACTION", NotificationActionIntent.defaultAction(context))
        assertTrue(intent.`package` == context.packageName || intent.component != null || intent.action != null)
    }

    @Test
    fun `NotificationConfig actionBroadcastAction overrides the derived action on the button's broadcast`() {
        val notifier: Notifier = createNotifier(
            context, permissions, NotificationConfig(actionBroadcastAction = "my.app.ACTION", minProgressInterval = Duration.ZERO),
        )
        runBlocking {
            notifier.post("a", LocalNotification("t", "b", NotificationChannelSpec("c", "C"), actions = listOf(NotificationAction("go", "Go"))))
        }
        assertEquals("my.app.ACTION", shadowOf(shown.single().actions.single().actionIntent).savedIntent.action)
    }
}
