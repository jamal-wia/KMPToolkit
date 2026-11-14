package io.github.jamal_wia.kmptoolkit.notification

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Round 20 (docs as a contract on every platform level): the earlier docs-sample tests run at one SDK;
 * a consumer's `minSdk` can be 24 and its device 36. These run the guide's samples through the public
 * factory at 24 26 29 31 33 35 (the levels the rest of the suite already loads, a sandbox per level stays in the test JVM): the channel sample (a no-op below 26, a channel from
 * 26), the adhan sample (dismiss action, media style, action icon), the foreground-service sample (the
 * built notification and the later post that updates it in place), the progress run, and the `when`
 * over every result. Nothing may throw, and the tray must show what the sample says.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 26, 29, 31, 33, 35])
class NotificationGuideSamplesSdkMatrixAdversarialTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val noInterval = NotificationConfig(minProgressInterval = Duration.ZERO)

    private fun registerLauncher() {
        val component = ComponentName(context.packageName, "com.example.MatrixLauncher")
        val packageManager = shadowOf(context.packageManager)
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    private val downloads = NotificationChannelSpec("downloads", "Downloads", "Progress", NotificationImportance.Low)
    private val remindersV2 = NotificationChannelSpec("reminders-v2", "Reminders", "Study", NotificationImportance.High, NotificationSound.Silent)

    @Test
    fun `the channel sample - delete the old id and ensure the rest at startup - works at every level`() {
        NotificationChannels.delete(context, "reminders")
        listOf(remindersV2, downloads).forEach { NotificationChannels.ensure(context, it) }
        // A second pass is the documented "safe to call at every startup".
        listOf(remindersV2, downloads).forEach { NotificationChannels.ensure(context, it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            assertEquals(setOf("reminders-v2", "downloads"), shadowOf(manager).notificationChannels.map { (it as android.app.NotificationChannel).id }.toSet())
            assertEquals(android.app.NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel("reminders-v2").importance)
        } else {
            assertEquals(0, shadowOf(manager).notificationChannels.size, "there are no channels below API 26")
        }
    }

    @Test
    fun `the progress run of the guide posts, replaces and finishes under one id at every level`() {
        registerLauncher()
        val notifier: Notifier = createNotifier(context, permissions, noInterval)
        for (percent in listOf(0, 10, 11, 25, 99, 100)) {
            val result: NotificationResult = runBlocking {
                notifier.post(
                    "download",
                    LocalNotification("Downloading", "$percent%", downloads, progress = NotificationProgress.Determinate(percent), ongoing = true),
                )
            }
            assertTrue(result == NotificationResult.Posted || result == NotificationResult.Coalesced, "$percent -> $result")
        }
        val done: NotificationResult = runBlocking {
            notifier.post(
                "download",
                LocalNotification("Done", "file.bin", downloads, progress = null, ongoing = false, contentExtras = mapOf("screen" to "downloads")),
            )
        }
        assertEquals(NotificationResult.Posted, done)
        val shown: List<Notification> = shadowOf(manager).allNotifications
        assertEquals(1, shown.size)
        assertEquals("Done", shown.single().extras.getString(Notification.EXTRA_TITLE))
        assertEquals(0, shown.single().flags and Notification.FLAG_ONGOING_EVENT)
        notifier.cancel("download")
        assertEquals(0, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun `the adhan sample - dismiss action media style and an action icon - posts at every level`() {
        registerLauncher()
        val notifier: Notifier = createNotifier(context, permissions)
        val stop = NotificationAction(id = "stop-adhan", label = "Stop")
        val result: NotificationResult = runBlocking {
            notifier.post(
                "adhan",
                LocalNotification("Adhan", "Dhuhr", remindersV2, actions = listOf(stop)),
                NotificationOptions(
                    dismissAction = stop,
                    mediaStyle = true,
                    actionIcons = mapOf(stop.id to NotificationIcon.AndroidDrawable(android.R.drawable.ic_media_pause)),
                ),
            )
        }
        assertEquals(NotificationResult.Posted, result)
        val shown: Notification = shadowOf(manager).allNotifications.single()
        assertEquals(1, shown.actions?.size, "the one button")
        assertNotNull(shown.deleteIntent, "the swipe sends the dismiss action")
    }

    @Test
    fun `the foreground sample - the built notification and the later post address the service's own platform id`() {
        registerLauncher()
        val playback = ForegroundNotificationId(id = "playback", platformId = 1001)
        val config = NotificationConfig()
        val nowPlaying = LocalNotification("Now playing", "Track 1", downloads)
        val built: Notification = buildForegroundNotification(context, playback, nowPlaying, config)
        assertTrue(built.flags and Notification.FLAG_ONGOING_EVENT != 0, "documented: always ongoing")
        // What startForeground does with it:
        manager.notify(playback.platformId, built)

        val notifier: Notifier = createNotifier(context, permissions, config, foregroundIds = setOf(playback))
        val result: NotificationResult = runBlocking { notifier.post("playback", nowPlaying.copy(body = "Track 2")) }

        assertEquals(NotificationResult.Posted, result)
        assertEquals(1, shadowOf(manager).allNotifications.size, "updated in place, not a second notification")
        assertEquals("Track 2", shadowOf(manager).getNotification(null, 1001).extras.getString(Notification.EXTRA_TEXT))
        notifier.cancel("playback")
        assertNull(shadowOf(manager).getNotification(null, 1001))
    }

    @Test
    fun `an id left out of the foreground set shows a second notification beside the service's - as the guide says`() {
        registerLauncher()
        val playback = ForegroundNotificationId(id = "playback", platformId = 1001)
        manager.notify(playback.platformId, buildForegroundNotification(context, playback, LocalNotification("Svc", "s", downloads)))
        val withoutSet: Notifier = createNotifier(context, permissions)

        assertEquals(NotificationResult.Posted, runBlocking { withoutSet.post("playback", LocalNotification("Other", "o", downloads)) })

        assertEquals(2, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun `every branch of the documented when is reachable on the platform and none throws`() {
        registerLauncher()
        val results: List<NotificationResult> = listOf(
            runBlocking { createNotifier(context, FakePermissionController(defaultStatus = PermissionStatus.Granted)).post("a", LocalNotification("t", "b", downloads)) },
            runBlocking { noOpNotifier().post("a", LocalNotification("t", "b", downloads)) },
        )
        val described: List<String> = results.map { result: NotificationResult ->
            when (result) {
                NotificationResult.Posted, NotificationResult.Coalesced -> "ok"
                NotificationResult.PermissionDenied -> "ask"
                NotificationResult.NotificationsDisabled -> "settings"
                is NotificationResult.ChannelBlocked -> "settings"
                is NotificationResult.Failed -> "failed"
            }
        }
        assertEquals(listOf("ok", "settings"), described)
        assertTrue(results[0].isPosted)
    }
}
