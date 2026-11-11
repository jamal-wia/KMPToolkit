package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What the notifier hands the platform for the plain fields of a [LocalNotification], through
 * `Notifier.post` itself. The foreground-parity tests compare `buildForegroundNotification` with the
 * notifier's frame, so a mapping that is wrong in the shared renderer is wrong in both and passes;
 * and no earlier test reads `ongoing`, `autoCancel`, the importance mapping or the progress bar back
 * off a posted notification (docs 04: `ongoing` = not dismissible, `autoCancel` = dismiss on tap,
 * `importance` = `IMPORTANCE_LOW` / `_DEFAULT` / `_HIGH`, applied when the channel is first created).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 26, 34])
class NotificationFlagsMappingAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)

    private fun notifier(): Notifier =
        createNotifier(context, permissions, NotificationConfig(minProgressInterval = Duration.ZERO))

    private fun channel(importance: NotificationImportance = NotificationImportance.Default, id: String = "mapping"): NotificationChannelSpec =
        NotificationChannelSpec(id = id, name = "Mapping $id", importance = importance)

    private fun shown(): Notification = shadowOf(manager).allNotifications.single()

    @Test
    fun `ongoing and autoCancel reach the platform as flags, whatever their combination`() = runTest {
        for (ongoing in listOf(false, true)) for (autoCancel in listOf(false, true)) {
            manager.cancelAll()
            val result: NotificationResult = notifier().post(
                "n",
                LocalNotification(title = "t", body = "b", channel = channel(), ongoing = ongoing, autoCancel = autoCancel),
            )
            assertEquals(NotificationResult.Posted, result)
            val flags: Int = shown().flags
            assertEquals(ongoing, flags and Notification.FLAG_ONGOING_EVENT != 0, "ongoing=$ongoing autoCancel=$autoCancel: ongoing flag")
            assertEquals(autoCancel, flags and Notification.FLAG_AUTO_CANCEL != 0, "ongoing=$ongoing autoCancel=$autoCancel: auto-cancel flag")
        }
    }

    @Test
    fun `the defaults dismiss on tap and are not ongoing`() = runTest {
        notifier().post("n", LocalNotification(title = "t", body = "b", channel = channel()))

        assertTrue(shown().flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(0, shown().flags and Notification.FLAG_ONGOING_EVENT)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `importance maps to the legacy priority below 26 and to the channel importance from 26`() = runTest {
        val expectedPriority: Map<NotificationImportance, Int> = mapOf(
            NotificationImportance.Low to Notification.PRIORITY_LOW,
            NotificationImportance.Default to Notification.PRIORITY_DEFAULT,
            NotificationImportance.High to Notification.PRIORITY_HIGH,
        )
        val expectedImportance: Map<NotificationImportance, Int> = mapOf(
            NotificationImportance.Low to NotificationManager.IMPORTANCE_LOW,
            NotificationImportance.Default to NotificationManager.IMPORTANCE_DEFAULT,
            NotificationImportance.High to NotificationManager.IMPORTANCE_HIGH,
        )
        for (importance: NotificationImportance in NotificationImportance.entries) {
            manager.cancelAll()
            val spec: NotificationChannelSpec = channel(importance, id = "imp-${importance.name}")
            assertEquals(NotificationResult.Posted, notifier().post("n", LocalNotification("t", "b", spec)))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                assertEquals(expectedPriority.getValue(importance), shown().priority, "priority of $importance")
            } else {
                assertEquals(expectedImportance.getValue(importance), manager.getNotificationChannel(spec.id).importance, "channel importance of $importance")
            }
        }
    }

    @Test
    fun `a progress bar is a hundred-step bar at the clamped percentage, and an indeterminate one has no position`() = runTest {
        val notifier: Notifier = notifier()
        val cases: List<Pair<NotificationProgress?, Triple<Int, Int, Boolean>?>> = listOf(
            NotificationProgress.Determinate(0) to Triple(100, 0, false),
            NotificationProgress.Determinate(37) to Triple(100, 37, false),
            NotificationProgress.Determinate(100) to Triple(100, 100, false),
            NotificationProgress.Determinate(-5) to Triple(100, 0, false),
            NotificationProgress.Determinate(250) to Triple(100, 100, false),
            NotificationProgress.Indeterminate to Triple(100, 0, true),
            null to null,
        )
        for ((progress: NotificationProgress?, expected: Triple<Int, Int, Boolean>?) in cases) {
            manager.cancelAll()
            val fresh: Notifier = notifier()
            assertEquals(NotificationResult.Posted, fresh.post("n", LocalNotification("t", "b", channel(), progress = progress)), "$progress")
            val extras = shown().extras
            if (expected == null) {
                assertEquals(0, extras.getInt(Notification.EXTRA_PROGRESS_MAX), "no bar for $progress")
            } else {
                assertEquals(expected.first, extras.getInt(Notification.EXTRA_PROGRESS_MAX), "max for $progress")
                assertEquals(expected.second, extras.getInt(Notification.EXTRA_PROGRESS), "position for $progress")
                assertEquals(expected.third, extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE), "indeterminate for $progress")
            }
        }
        assertEquals(NotificationResult.Posted, notifier.post("x", LocalNotification("t", "b", channel())))
    }

    @Test
    fun `a bar that is replaced by a plain frame loses its bar`() = runTest {
        val notifier: Notifier = notifier()
        notifier.post("n", LocalNotification("t", "b", channel(), progress = NotificationProgress.Determinate(40)))

        assertEquals(NotificationResult.Posted, notifier.post("n", LocalNotification("t", "b", channel())))

        assertEquals(0, shown().extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertFalse(shown().extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    @Test
    fun `an importance changed in the spec of an existing channel does not move the channel and does not block posting`() = runTest {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@runTest
        val notifier: Notifier = notifier()
        notifier.post("a", LocalNotification("t", "b", channel(NotificationImportance.Low, id = "stay")))
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel("stay").importance)

        val result: NotificationResult = notifier.post("b", LocalNotification("t", "b", channel(NotificationImportance.High, id = "stay")))

        assertEquals(NotificationResult.Posted, result)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel("stay").importance, "importance belongs to the user once the channel exists")
    }
}
