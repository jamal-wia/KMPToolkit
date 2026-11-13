package io.github.jamal_wia.kmptoolkit.notification

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import io.github.jamal_wia.kmptoolkit.scheduler.AlarmHandler
import io.github.jamal_wia.kmptoolkit.scheduler.AlarmNotification
import io.github.jamal_wia.kmptoolkit.scheduler.ScheduledAlarm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * E2 boss round 1 (cross-module, docs against docs). The scheduler's getting-started guide (step 3, "Write a
 * handler") tells an app to show a fired alarm with `NotificationManagerCompat.notify(alarm.id.hashCode(), ...)`:
 * untagged, under the id's `String.hashCode()`. That is exactly the key 2.x of the notifier used, and the 3.x+
 * notifier's migration sweep cancels the untagged `(id.hashCode() and 0x7FFFFFFF).coerceAtLeast(1)` on the first
 * `post(id)` of a process and on every `cancel(id)`. Both modules' platform notes say that on Android the
 * notifier and the scheduler keep a shared id apart ("code that shares an id works there"). These tests pin
 * what actually happens to the notification the documented handler shows when the notifier is used with the
 * alarm's own id: for an id with a non-negative hash it is removed by the sweep; for a negative hash it is not
 * (the mask makes the two keys differ). The pending alarm itself is never touched (a PendingIntent, not a
 * notification), which is what the platform notes literally promise.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SchedulerGuideHandlerMeetsLegacySweepBossAdversarialTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val notificationManager: NotificationManager get() = app.getSystemService(NotificationManager::class.java)
    private val tray: List<Notification> get() = shadowOf(notificationManager).allNotifications
    private val channel = NotificationChannelSpec(id = "reminders", name = "Reminders")

    /** The handler of docs/kmptoolkit-scheduler/02-getting-started.md step 3, as printed (icon swapped for a platform one). */
    private class StudyReminderHandler(private val context: Context) : AlarmHandler {
        override val type: String = "STUDY_REMINDER"
        override suspend fun onFire(alarm: ScheduledAlarm) {
            NotificationManagerCompat.from(context).notify(
                alarm.id.hashCode(),
                NotificationCompat.Builder(context, alarm.notification.channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(alarm.notification.title)
                    .setContentText(alarm.notification.body)
                    .build(),
            )
        }
    }

    private fun alarm(id: String): ScheduledAlarm = ScheduledAlarm(
        id = id,
        type = "STUDY_REMINDER",
        fireAtEpochMillis = 1_800_000_000_000L,
        notification = AlarmNotification(title = "Time to study", body = "Lesson awaits", channelId = channel.id),
    )

    private fun newNotifier(): Notifier =
        createNotifier(context = app, permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted))

    private fun reminderShowing(): Boolean =
        tray.any { it.extras.getString(Notification.EXTRA_TITLE) == "Time to study" }

    private fun fire(id: String) {
        runBlocking { StudyReminderHandler(app).onFire(alarm(id)) }
        assertTrue(reminderShowing(), "precondition: the guide's handler shows the fired reminder")
    }

    @Test
    fun `a notifier cancel of the alarm id removes the reminder the guide's handler showed when the hash is not negative`() {
        val id = "lesson-42"
        assertTrue(id.hashCode() >= 0, "precondition: a non-negative hash")
        fire(id)
        newNotifier().cancel(id)
        assertEquals(false, reminderShowing(), "the 2.x sweep of cancel(id) hits the untagged hashCode key of the guide's handler")
    }

    @Test
    fun `the first notifier post of the alarm id in a process removes the reminder the guide's handler showed`() {
        val id = "lesson-42"
        fire(id)
        val result: NotificationResult = runBlocking {
            newNotifier().post(id, LocalNotification(title = "Lesson 42 done", body = "Well done", channel = channel))
        }
        assertEquals(NotificationResult.Posted, result)
        assertEquals(false, reminderShowing(), "the first post of an id sweeps the untagged key the guide's handler used")
        assertEquals(1, tray.size, "only the notifier's own tagged notification is left")
    }

    @Test
    fun `a negative hash keeps the reminder because the sweep masks the sign bit`() {
        val id = "reminder-7"
        assertTrue(id.hashCode() < 0, "precondition: a negative hash")
        fire(id)
        val notifier: Notifier = newNotifier()
        runBlocking { notifier.post(id, LocalNotification(title = "Reminder 7 note", body = "b", channel = channel)) }
        notifier.cancel(id)
        assertTrue(reminderShowing(), "masked and unmasked keys differ for a negative hash, so the reminder stays")
    }

    @Test
    fun `distinct ids as the platform notes recommend never meet the reminder`() {
        fire("lesson-42")
        val notifier: Notifier = newNotifier()
        runBlocking { notifier.post("lesson-42-confirm", LocalNotification(title = "c", body = "b", channel = channel)) }
        notifier.cancel("lesson-42-confirm")
        assertTrue(reminderShowing())
    }
}
