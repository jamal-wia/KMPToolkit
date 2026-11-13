package io.github.jamal_wia.kmptoolkit.notification

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * E2 boss round 1 (fix interaction: foregroundIds x coalescing). A foreground service shows its notification
 * with `startForeground(platformId, buildForegroundNotification(...))`, outside the notifier, and the guide says
 * the later `post` of that id "updates the service's notification in place". The notifier's coalescing baseline
 * is not told about a frame the service shows itself: when an abandoned run left a baseline (no terminal frame,
 * no cancel) and the service restarts within the abandoned-run horizon with the same text, a frame in the old
 * bucket answers `Coalesced` while the tray shows the service's own starting frame. The documented per-instance
 * residue ("a cancel made through another instance is, to this one, like a swipe") does not name
 * `startForeground`; these tests pin the behaviour so a docs fix can name it, and pin the documented way out (a
 * `cancel(id)` or a terminal frame when a run ends) as working.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ForegroundRestartCoalescingBossAdversarialTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = app.getSystemService(NotificationManager::class.java)
    private val channel = NotificationChannelSpec(id = "downloads", name = "Downloads")
    private val download = ForegroundNotificationId(id = "download", platformId = 4242)

    private fun frame(percent: Int): LocalNotification =
        LocalNotification(title = "Downloading", body = "lesson pack", channel = channel, progress = NotificationProgress.Determinate(percent))

    private fun barOnTray(): Int {
        val shown: Notification = shadowOf(manager).getNotification(null, download.platformId)
            ?: error("the service's notification is not on the tray")
        return shown.extras.getInt(Notification.EXTRA_PROGRESS, -1)
    }

    /** What `startForeground(platformId, notification)` does to the tray: an untagged notify under the platform id. */
    private fun serviceStarts(percent: Int) {
        manager.notify(download.platformId, buildForegroundNotification(app, download, frame(percent)))
    }

    private val time = TestTimeSource()

    /** The notifier createNotifier builds, with a virtual clock so the 500 ms rate limit needs no real time. */
    private fun newNotifier(): Notifier = AndroidNotifier(
        context = app,
        permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted),
        renderer = NotificationRenderer(app, NotificationConfig().resolveBroadcastAction(app.packageName)),
        coalescer = ProgressCoalescer(timeSource = time),
        foregroundIds = setOf(download),
    )

    @Test
    fun `a restarted service frame is not known to the baseline an abandoned run left`() {
        val notifier: Notifier = newNotifier()
        serviceStarts(0)
        assertEquals(NotificationResult.Posted, runBlocking { notifier.post(download.id, frame(45)) })
        assertEquals(45, barOnTray())
        // The run is abandoned (the download failed, no terminal frame), and the service starts again at once.
        serviceStarts(0)
        assertEquals(0, barOnTray())
        // A second later (past the 500 ms rate limit) the new run reaches the old bucket with the same text.
        time += 1.seconds
        val answer: NotificationResult = runBlocking { notifier.post(download.id, frame(47)) }
        assertEquals(NotificationResult.Coalesced, answer, "the baseline still names the 40 bucket of the abandoned run")
        assertEquals(0, barOnTray(), "the tray keeps the service's starting frame until the bar crosses a bucket")
    }

    @Test
    fun `a cancel when the run ends lets the restarted service frame be updated at once`() {
        val notifier: Notifier = newNotifier()
        serviceStarts(0)
        runBlocking { notifier.post(download.id, frame(45)) }
        notifier.cancel(download.id)
        serviceStarts(0)
        time += 1.seconds
        assertEquals(NotificationResult.Posted, runBlocking { notifier.post(download.id, frame(47)) })
        assertEquals(47, barOnTray())
    }
}
