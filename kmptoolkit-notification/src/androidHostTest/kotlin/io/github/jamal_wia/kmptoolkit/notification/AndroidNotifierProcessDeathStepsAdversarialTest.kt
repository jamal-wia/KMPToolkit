package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

/**
 * Round 19 (failure injection and process death): the process dies at every platform step of a post
 * and of a cancel (modelled as an `Error` thrown by the platform call, with the effect of the call
 * applied or not, which is what a kill before or after the binder transaction looks like), and a
 * new notifier (empty coalescer, empty caches) takes over a tray, channels and `PendingIntent`s that
 * the dead process left behind. 05-platform-notes: "nothing to restore after ... a process death
 * beyond in-memory progress-coalescing state, which is meant to be lost"; the KDoc of the tap target:
 * it "holds after a process restart too". Whatever step the death landed on, the next process must
 * post (never judge a frame redundant against a baseline it does not have), leave exactly one
 * notification per id with the newest frame's tap target, and cancel what the dead process left.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 29, 35], shadows = [AndroidNotifierProcessDeathStepsAdversarialTest.DyingShadowNotificationManager::class])
class AndroidNotifierProcessDeathStepsAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "death", name = "Death")

    class ProcessKilled(point: String) : Error("process killed at $point")

    enum class Kill { NONE, BEFORE_CHANNEL, BEFORE_NOTIFY, AFTER_NOTIFY, BEFORE_CANCEL, AFTER_CANCEL, BEFORE_CANCEL_ALL, AFTER_CANCEL_ALL }

    @After
    fun heal() {
        DyingShadowNotificationManager.kill.set(Kill.NONE)
    }

    private fun registerLauncher() {
        val component = ComponentName(context.packageName, "com.example.DeathLauncher")
        shadowOf(context.packageManager).addActivityIfNotPresent(component)
        shadowOf(context.packageManager).addIntentFilter(component)
    }

    private fun org.robolectric.shadows.ShadowPackageManager.addIntentFilter(component: ComponentName) {
        addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    /** A new process: a fresh notifier, so a fresh coalescer and empty caches, over the same platform state. */
    private fun relaunch(): Notifier = AndroidNotifier(
        context, permissions, NotificationRenderer(context, "test.ACTION"),
        ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO),
    )

    private fun frame(percent: Int?, file: String): LocalNotification = LocalNotification(
        title = "t-$file",
        body = "b",
        channel = channel,
        progress = percent?.let { NotificationProgress.Determinate(it) },
        contentExtras = mapOf("file" to file),
    )

    private fun tray(): List<Notification> = shadowOf(manager).allNotifications

    private fun tapFile(id: String): String? {
        val shown: Notification = assertNotNull(shadowOf(manager).getNotification(id, 1), "nothing on the tray under '$id'")
        return shown.contentIntent?.let { shadowOf(it).savedIntent.getStringExtra("file") }
    }

    private fun post(notifier: Notifier, id: String, frame: LocalNotification): NotificationResult =
        runBlocking { notifier.post(id, frame) }

    /** A channel exists from API 26 only; below it that step of a post does not exist to die in. */
    private fun firstPostKills(): List<Kill> =
        listOfNotNull(Kill.BEFORE_CHANNEL.takeIf { android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O }, Kill.BEFORE_NOTIFY, Kill.AFTER_NOTIFY)

    private fun diesDuring(kill: Kill, block: () -> Unit) {
        DyingShadowNotificationManager.kill.set(kill)
        try {
            assertFailsWith<ProcessKilled> { block() }
        } finally {
            DyingShadowNotificationManager.kill.set(Kill.NONE)
        }
    }

    @Test
    fun `a death at any step of the first post leaves a process that posts the same frame and owns one notification`() {
        registerLauncher()
        for (kill: Kill in firstPostKills()) {
            shadowOf(manager).notificationChannels.toList().forEach { manager.deleteNotificationChannel(it.id) }
            manager.cancelAll()
            val dead: Notifier = relaunch()
            diesDuring(kill) { post(dead, "run", frame(30, "a")) }

            val survivor: Notifier = relaunch()
            assertEquals(NotificationResult.Posted, post(survivor, "run", frame(30, "a")), "$kill: the new process judged a frame redundant against a baseline it never had")
            assertEquals(1, tray().size, "$kill: one notification per id")
            assertEquals("a", tapFile("run"), "$kill: the tap target of the newest frame")
            assertEquals(30, assertNotNull(shadowOf(manager).getNotification("run", 1)).extras.getInt(Notification.EXTRA_PROGRESS), "$kill")
            assertEquals(NotificationResult.Coalesced, post(survivor, "run", frame(30, "a")), "$kill: and then coalescing works again")
        }
    }

    @Test
    fun `an Error the host survives leaves the same notifier able to post the same frame again`() {
        registerLauncher()
        for (kill: Kill in firstPostKills()) {
            shadowOf(manager).notificationChannels.toList().forEach { manager.deleteNotificationChannel(it.id) }
            manager.cancelAll()
            val notifier: Notifier = relaunch()
            diesDuring(kill) { post(notifier, "run", frame(40, "a")) }

            assertEquals(NotificationResult.Posted, post(notifier, "run", frame(40, "a")), "$kill: a frame the Error stopped must not stand as the baseline")
            assertEquals("a", tapFile("run"), "$kill")
        }
    }

    @Test
    fun `a death between the platform accepting a replacement and its tap target write is repaired by the next process`() {
        registerLauncher()
        val first: Notifier = relaunch()
        assertEquals(NotificationResult.Posted, post(first, "run", frame(10, "a")))
        assertEquals("a", tapFile("run"))

        diesDuring(Kill.AFTER_NOTIFY) { post(first, "run", frame(60, "b")) }
        // The documented micro-window: the tray already shows the new frame, the tap target is still the old one.
        assertEquals(60, assertNotNull(shadowOf(manager).getNotification("run", 1)).extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals("a", tapFile("run"))

        val second: Notifier = relaunch()
        assertEquals(NotificationResult.Posted, post(second, "run", frame(60, "b")))
        assertEquals(1, tray().size)
        assertEquals("b", tapFile("run"), "the tap target must follow the frame the tray shows once a process has accepted it")
    }

    @Test
    fun `a death inside cancel or cancelAll leaves what the next process can still cancel and post over`() {
        registerLauncher()
        for (kill: Kill in listOf(Kill.BEFORE_CANCEL, Kill.AFTER_CANCEL)) {
            manager.cancelAll()
            val dead: Notifier = relaunch()
            assertEquals(NotificationResult.Posted, post(dead, "run", frame(50, "a")))
            diesDuring(kill) { dead.cancel("run") }

            val survivor: Notifier = relaunch()
            survivor.cancel("run")
            assertEquals(0, tray().size, "$kill: the survivor's cancel must clear the id")
            assertEquals(NotificationResult.Posted, post(survivor, "run", frame(50, "a")), "$kill: no stale baseline after a cancel")
            assertEquals(1, tray().size)
        }
        for (kill: Kill in listOf(Kill.BEFORE_CANCEL_ALL, Kill.AFTER_CANCEL_ALL)) {
            manager.cancelAll()
            val dead: Notifier = relaunch()
            assertEquals(NotificationResult.Posted, post(dead, "x", frame(50, "a")))
            assertEquals(NotificationResult.Posted, post(dead, "y", frame(50, "b")))
            diesDuring(kill) { dead.cancelAll() }

            val survivor: Notifier = relaunch()
            survivor.cancelAll()
            assertEquals(0, tray().size, "$kill")
            assertEquals(NotificationResult.Posted, post(survivor, "x", frame(50, "a")))
        }
    }

    @Test
    fun `a terminal frame killed on its way leaves the progress frame which the next process replaces`() {
        registerLauncher()
        val dead: Notifier = relaunch()
        assertEquals(NotificationResult.Posted, post(dead, "run", frame(50, "a")))
        diesDuring(Kill.BEFORE_NOTIFY) { post(dead, "run", frame(null, "done")) }
        assertEquals(50, assertNotNull(shadowOf(manager).getNotification("run", 1)).extras.getInt(Notification.EXTRA_PROGRESS))

        val survivor: Notifier = relaunch()
        assertEquals(NotificationResult.Posted, post(survivor, "run", frame(null, "done")))
        assertEquals(1, tray().size)
        assertEquals("done", tapFile("run"))
        assertNull(shadowOf(manager).getNotification("run", 1)?.extras?.takeIf { it.containsKey(Notification.EXTRA_PROGRESS_INDETERMINATE) && it.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) })
    }

    /** Throws [ProcessKilled] at the configured step, once, with the effect of the call applied or not. */
    @Implements(NotificationManager::class)
    class DyingShadowNotificationManager : ShadowNotificationManager() {

        @Implementation
        public override fun createNotificationChannel(channel: NotificationChannel) {
            fire(Kill.BEFORE_CHANNEL)
            super.createNotificationChannel(channel)
        }

        @Implementation
        public override fun notify(tag: String?, id: Int, notification: Notification?) {
            fire(Kill.BEFORE_NOTIFY)
            super.notify(tag, id, notification)
            fire(Kill.AFTER_NOTIFY)
        }

        @Implementation
        public override fun cancel(tag: String?, id: Int) {
            if (tag == null) {
                super.cancel(tag, id)
                return
            }
            fire(Kill.BEFORE_CANCEL)
            super.cancel(tag, id)
            fire(Kill.AFTER_CANCEL)
        }

        @Implementation
        public override fun cancelAll() {
            fire(Kill.BEFORE_CANCEL_ALL)
            super.cancelAll()
            fire(Kill.AFTER_CANCEL_ALL)
        }

        private fun fire(point: Kill) {
            if (kill.compareAndSet(point, Kill.NONE)) throw ProcessKilled(point.name)
        }

        companion object {
            val kill: AtomicReference<Kill> = AtomicReference(Kill.NONE)
        }
    }
}
