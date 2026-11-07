package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Each notification's tap target carries its own `contentExtras`, and a frame renders that target
 * before the platform accepts it. Whichever frame ends up on the tray, a frame that was rendered
 * and then dropped (overtaken by a newer frame or by a terminal frame) must not decide where the
 * tray's frame opens the app, whatever order the builds and the drops interleave in.
 */
@RunWith(AndroidJUnit4::class)
class AndroidNotifierTapTargetRaceAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "tap-race", name = "Tap race")

    /** Runs a hook before and after the real build, keyed by the frame's title. */
    private class HookedRenderer(context: Context) : NotificationRenderer(context, "test.ACTION") {
        val before: MutableMap<String, () -> Unit> = ConcurrentHashMap()
        val after: MutableMap<String, () -> Unit> = ConcurrentHashMap()

        override fun build(id: String, notification: LocalNotification, options: NotificationOptions, iconResId: Int): Notification {
            before[notification.title]?.invoke()
            val built: Notification = super.build(id, notification, options, iconResId)
            after[notification.title]?.invoke()
            return built
        }
    }

    private fun registerLauncher() {
        val component = ComponentName(context.packageName, "com.example.TestLauncher")
        shadowOf(context.packageManager).addActivityIfNotPresent(component)
        shadowOf(context.packageManager).addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    private fun notifier(renderer: NotificationRenderer): Notifier =
        AndroidNotifier(context, permissions, renderer, ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO))

    private fun frame(title: String, file: String, progress: NotificationProgress?): LocalNotification = LocalNotification(
        title = title,
        body = "body",
        channel = channel,
        progress = progress,
        contentExtras = mapOf("file" to file),
    )

    private fun tappedFile(): Pair<String?, String?> {
        val onTray: Notification = shadowOf(manager).allNotifications.single()
        val tapped: Intent = shadowOf(assertNotNull(onTray.contentIntent)).savedIntent
        return onTray.extras.getString(Notification.EXTRA_TITLE) to tapped.getStringExtra("file")
    }

    @Test
    fun `a progress frame rendered after the terminal frame does not decide where the done notification opens`() {
        registerLauncher()
        val renderer = HookedRenderer(context)
        val notifier: Notifier = notifier(renderer)
        val progressEntered = CountDownLatch(1)
        val progressRelease = CountDownLatch(1)
        renderer.before["progress"] = {
            progressEntered.countDown()
            check(progressRelease.await(10, TimeUnit.SECONDS)) { "progress frame never released" }
        }
        var progressResult: NotificationResult? = null
        val worker: Thread = thread(name = "kmptoolkit-adversarial-tap-progress") {
            progressResult = runBlocking {
                notifier.post("d", frame("progress", "progress.bin", NotificationProgress.Determinate(50)))
            }
        }
        try {
            assertTrue(progressEntered.await(10, TimeUnit.SECONDS), "the progress frame never reached its build")
            val done: NotificationResult = runBlocking { notifier.post("d", frame("done", "done.bin", null)) }
            assertEquals(NotificationResult.Posted, done)
        } finally {
            progressRelease.countDown()
            worker.join(TimeUnit.SECONDS.toMillis(10))
        }
        assertTrue(!worker.isAlive, "the progress worker is stuck")
        assertEquals(NotificationResult.Coalesced, progressResult)
        val (title: String?, file: String?) = tappedFile()
        assertEquals("done", title)
        assertEquals("done.bin", file, "the done notification opens the app with the extras of the progress frame that was dropped")
    }

    @Test
    fun `an older frame dropped while the newer one is still in flight does not rewrite the newer frame's tap target`() {
        registerLauncher()
        val renderer = HookedRenderer(context)
        val notifier: Notifier = notifier(renderer)
        val olderEntered = CountDownLatch(1)
        val olderRelease = CountDownLatch(1)
        val newerBuilt = CountDownLatch(1)
        val newerRelease = CountDownLatch(1)
        renderer.before["older"] = {
            olderEntered.countDown()
            check(olderRelease.await(10, TimeUnit.SECONDS)) { "older frame never released" }
        }
        renderer.after["newer"] = {
            newerBuilt.countDown()
            check(newerRelease.await(10, TimeUnit.SECONDS)) { "newer frame never released" }
        }
        var olderResult: NotificationResult? = null
        var newerResult: NotificationResult? = null
        val older: Thread = thread(name = "kmptoolkit-adversarial-tap-older") {
            olderResult = runBlocking { notifier.post("d", frame("older", "older.bin", NotificationProgress.Determinate(10))) }
        }
        var newer: Thread? = null
        try {
            assertTrue(olderEntered.await(10, TimeUnit.SECONDS), "the older frame never reached its build")
            newer = thread(name = "kmptoolkit-adversarial-tap-newer") {
                newerResult = runBlocking { notifier.post("d", frame("newer", "newer.bin", NotificationProgress.Determinate(50))) }
            }
            assertTrue(newerBuilt.await(10, TimeUnit.SECONDS), "the newer frame never finished its build")
            // The older frame now renders (rewriting the shared tap target) and finds itself overtaken.
            olderRelease.countDown()
            older.join(TimeUnit.SECONDS.toMillis(10))
            assertTrue(!older.isAlive, "the older worker is stuck")
        } finally {
            olderRelease.countDown()
            newerRelease.countDown()
            newer?.join(TimeUnit.SECONDS.toMillis(10))
        }
        assertEquals(NotificationResult.Coalesced, olderResult)
        assertEquals(NotificationResult.Posted, newerResult)
        val (title: String?, file: String?) = tappedFile()
        assertEquals("newer", title)
        assertEquals("newer.bin", file, "the newer frame on the tray opens the app with the extras of the older frame that was dropped")
    }

    @Test
    fun `a refused progress frame rendered after the terminal frame does not decide where the done notification opens`() {
        registerLauncher()
        val renderer = HookedRenderer(context)
        val notifier: Notifier = notifier(renderer)
        val progressEntered = CountDownLatch(1)
        val progressRelease = CountDownLatch(1)
        renderer.before["progress"] = {
            progressEntered.countDown()
            check(progressRelease.await(10, TimeUnit.SECONDS)) { "progress frame never released" }
        }
        // Rendered like the real one (the tap target is rewritten), then refused.
        renderer.after["progress"] = { throw IllegalStateException("the platform refused the frame") }
        var progressResult: NotificationResult? = null
        val worker: Thread = thread(name = "kmptoolkit-adversarial-tap-refused") {
            progressResult = runBlocking {
                notifier.post("d", frame("progress", "progress.bin", NotificationProgress.Determinate(50)))
            }
        }
        try {
            assertTrue(progressEntered.await(10, TimeUnit.SECONDS), "the progress frame never reached its build")
            assertEquals(NotificationResult.Posted, runBlocking { notifier.post("d", frame("done", "done.bin", null)) })
        } finally {
            progressRelease.countDown()
            worker.join(TimeUnit.SECONDS.toMillis(10))
        }
        assertTrue(!worker.isAlive, "the progress worker is stuck")
        assertTrue(progressResult is NotificationResult.Failed, "the refused frame answered $progressResult")
        val (title: String?, file: String?) = tappedFile()
        assertEquals("done", title)
        assertEquals("done.bin", file, "the done notification opens the app with the extras of the progress frame the platform refused")
    }

    @Test
    fun `an older frame refused while the newer one is still in flight does not rewrite the newer frame's tap target`() {
        registerLauncher()
        val renderer = HookedRenderer(context)
        val notifier: Notifier = notifier(renderer)
        val olderEntered = CountDownLatch(1)
        val olderRelease = CountDownLatch(1)
        val newerBuilt = CountDownLatch(1)
        val newerRelease = CountDownLatch(1)
        renderer.before["older"] = {
            olderEntered.countDown()
            check(olderRelease.await(10, TimeUnit.SECONDS)) { "older frame never released" }
        }
        renderer.after["older"] = { throw IllegalStateException("the platform refused the frame") }
        renderer.after["newer"] = {
            newerBuilt.countDown()
            check(newerRelease.await(10, TimeUnit.SECONDS)) { "newer frame never released" }
        }
        var olderResult: NotificationResult? = null
        var newerResult: NotificationResult? = null
        val older: Thread = thread(name = "kmptoolkit-adversarial-tap-older-refused") {
            olderResult = runBlocking { notifier.post("d", frame("older", "older.bin", NotificationProgress.Determinate(10))) }
        }
        var newer: Thread? = null
        try {
            assertTrue(olderEntered.await(10, TimeUnit.SECONDS), "the older frame never reached its build")
            newer = thread(name = "kmptoolkit-adversarial-tap-newer-2") {
                newerResult = runBlocking { notifier.post("d", frame("newer", "newer.bin", NotificationProgress.Determinate(50))) }
            }
            assertTrue(newerBuilt.await(10, TimeUnit.SECONDS), "the newer frame never finished its build")
            olderRelease.countDown()
            older.join(TimeUnit.SECONDS.toMillis(10))
            assertTrue(!older.isAlive, "the older worker is stuck")
        } finally {
            olderRelease.countDown()
            newerRelease.countDown()
            newer?.join(TimeUnit.SECONDS.toMillis(10))
        }
        assertTrue(olderResult is NotificationResult.Failed, "the refused frame answered $olderResult")
        assertEquals(NotificationResult.Posted, newerResult)
        val (title: String?, file: String?) = tappedFile()
        assertEquals("newer", title)
        assertEquals("newer.bin", file, "the newer frame on the tray opens the app with the extras of the older frame the platform refused")
    }
}
