package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.notification.testing.RecordingNotifier
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Round 18 (fake fidelity): every refusal a real Android notifier can answer with (permission, the app-wide switch, a muted
 * channel, an icon that does not resolve) scripted into RecordingNotifier as a consumer would script it, over seeded
 * sequences of posts and cancels: a refused post must leave the tray exactly as it was, in the real notifier and in the
 * double (docs: only a `Posted` result puts a notification on screen).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AndroidNotifierFidelityGatesAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val ids: List<String> = listOf("a", "b", "c")

    private fun shown(): Map<String, Pair<String, String>> = ids.mapNotNull { id: String ->
        shadowOf(manager).getNotification(id, 1)?.let { n: Notification ->
            id to (n.extras.getString(Notification.EXTRA_TITLE).orEmpty() to n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty())
        }
    }.toMap()

    @Test
    fun `refusals of every kind leave the tray as it was in the real notifier and in the double`() {
        runBlocking {
            withTimeout(120_000) {
                repeat(30) { seed: Int ->
                    val random = Random(seed * 13 + 1)
                    manager.cancelAll()
                    manager.notificationChannels.toList().forEach { manager.deleteNotificationChannel(it.id) }
                    shadowOf(manager).setNotificationsEnabled(true)
                    // A channel the user already muted before the app ever posted on it.
                    manager.createNotificationChannel(NotificationChannel("muted", "Muted", NotificationManager.IMPORTANCE_NONE))
                    val permissions = MutablePermissions(PermissionStatus.Granted)
                    val notifier: Notifier = createNotifier(context, permissions, NotificationConfig(minProgressInterval = Duration.ZERO))
                    val fake = RecordingNotifier()
                    val log = StringBuilder()
                    repeat(40) { step: Int ->
                        val where = "seed $seed step $step"
                        if (random.nextInt(6) == 0) {
                            val id: String = ids.random(random)
                            notifier.cancel(id)
                            fake.cancel(id)
                            log.append("cancel($id); ")
                        } else {
                            val mode: Int = random.nextInt(5)
                            permissions.status = if (mode == 1) PermissionStatus.Denied() else PermissionStatus.Granted
                            shadowOf(manager).setNotificationsEnabled(mode != 2)
                            val channel = NotificationChannelSpec(if (mode == 3) "muted" else "ok", "Name")
                            val icon: NotificationIcon = if (mode == 4) NotificationIcon.AndroidDrawable(0x7f7f7f7f) else NotificationIcon.Default
                            val id: String = ids.random(random)
                            val frame = LocalNotification("t$step", "b$mode", channel, icon = icon)
                            val answered: NotificationResult = notifier.post(id, frame)
                            val expected: NotificationResult? = when (mode) {
                                1 -> NotificationResult.PermissionDenied
                                2 -> NotificationResult.NotificationsDisabled
                                3 -> NotificationResult.ChannelBlocked("muted")
                                4 -> null
                                else -> NotificationResult.Posted
                            }
                            log.append("post($id,mode=$mode)=${answered::class.simpleName}; ")
                            if (expected == null) assertIs<NotificationResult.Failed>(answered, "$where: an unresolvable icon fails. Steps: $log")
                            else assertEquals(expected, answered, "$where: real answer. Steps: $log")
                            fake.result = answered
                            fake.post(id, frame)
                        }
                        val fakeScreen: Map<String, Pair<String, String>> = fake.showing.mapValues { (_, n: LocalNotification) -> n.title to n.body }
                        assertEquals(shown(), fakeScreen, "$where: the tray and the double disagree. Steps: $log")
                    }
                }
            }
        }
    }
}
