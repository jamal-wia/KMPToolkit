package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.CommonPermission
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Identity edge cases and flood churn of the Android notifier: ids that collide with a foreground
 * service's platform id, ids that look alike, a cancelAll racing posts of many ids, and gates that
 * reject a post before it may leave any coalescing state behind.
 */
@RunWith(AndroidJUnit4::class)
class AndroidNotifierChurnAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val shown: List<Notification> get() = shadowOf(manager).allNotifications
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "churn", name = "Churn")

    private fun frame(title: String, percent: Int? = null): LocalNotification = LocalNotification(
        title = title,
        body = "body",
        channel = channel,
        progress = percent?.let { NotificationProgress.Determinate(it) },
    )

    private fun titles(): List<String> =
        shown.map { it.extras.getString(Notification.EXTRA_TITLE).orEmpty() }.sorted()

    private fun notifier(
        coalescer: ProgressCoalescer = ProgressCoalescer(minInterval = Duration.ZERO),
        foregroundIds: Set<ForegroundNotificationId> = emptySet(),
    ): AndroidNotifier = AndroidNotifier(context, permissions, NotificationRenderer(context, "test.ACTION"), coalescer, foregroundIds)

    private fun post(notifier: Notifier, id: String, notification: LocalNotification): NotificationResult =
        runBlocking { notifier.post(id, notification) }

    @Test
    fun `an id whose legacy twin is a foreground service's platform id never touches the service's notification`() {
        val clashing = "other"
        val platformId: Int = legacyPlatformNotificationId(clashing)
        val notifier: Notifier = notifier(foregroundIds = setOf(ForegroundNotificationId("service", platformId)))
        assertEquals(NotificationResult.Posted, post(notifier, "service", frame("service")))

        assertEquals(NotificationResult.Posted, post(notifier, clashing, frame("other")))
        assertEquals(listOf("other", "service"), titles(), "posting the clashing id swept the service's notification")
        notifier.cancel(clashing)

        assertEquals(listOf("service"), titles(), "cancelling the clashing id removed the service's notification")
        notifier.cancel("service")
        assertEquals(emptyList(), titles())
    }

    @Test
    fun `ids that look alike are distinct notifications and each cancels alone`() {
        val ids: List<String> = listOf("Aa", "BB", "a/b", "a%2Fb", "a b", "A", "a", "日本語", "😀", " lead", "trail ", "x".repeat(300), "a\nb", "77", "1")
        val notifier: Notifier = notifier()
        ids.forEach { id: String -> post(notifier, id, frame(id)) }

        assertEquals(ids.sorted(), titles(), "two ids collapsed into one notification")
        val remaining: MutableList<String> = ids.toMutableList()
        ids.forEach { id: String ->
            notifier.cancel(id)
            remaining -= id
            assertEquals(remaining.sorted(), titles(), "cancelling '$id' removed the wrong notification")
        }
    }

    @Test
    fun `eight threads posting many ids while another cancels everything end empty with no state left`() {
        val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO)
        val notifier: AndroidNotifier = notifier(coalescer)
        val barrier = CyclicBarrier(9)
        val failures: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val posters: List<Thread> = List(8) { worker: Int ->
            thread(name = "kmptoolkit-adversarial-flood-$worker") {
                runCatching {
                    barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    repeat(40) { index: Int ->
                        val result: NotificationResult = post(notifier, "w$worker-${index % 5}", frame("w$worker-$index", (index * 7) % 101))
                        if (result is NotificationResult.Failed) failures += "worker=$worker index=$index: $result"
                    }
                }.onFailure { failures += "poster $worker: $it" }
            }
        }
        val canceller: Thread = thread(name = "kmptoolkit-adversarial-cancel-all") {
            runCatching {
                barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                repeat(30) {
                    notifier.cancelAll()
                    Thread.yield()
                }
            }.onFailure { failures += "canceller: $it" }
        }
        (posters + canceller).forEach { worker: Thread ->
            worker.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS * 3))
            assertTrue(!worker.isAlive, "thread ${worker.name} is stuck")
        }

        notifier.cancelAll()

        assertEquals(emptyList(), failures.toList())
        assertEquals(emptyList(), titles(), "the tray is not empty after the final cancelAll")
        assertEquals(0, coalescer.postedCountForTest())
        assertEquals(0, coalescer.latestCountForTest())
        assertEquals(0, notifier.sweptLegacyCountForTest())
    }

    @Test
    fun `posts a gate rejects leave no coalescing state and the first frame afterwards still posts`() {
        val coalescer = ProgressCoalescer(minInterval = Duration.ZERO)
        val notifier: Notifier = notifier(coalescer)
        permissions.setStatus(CommonPermission.Notifications, PermissionStatus.Denied())

        repeat(200) { index: Int ->
            assertEquals(NotificationResult.PermissionDenied, post(notifier, "id-$index", frame("t", 10 + index % 80)))
        }

        assertEquals(0, coalescer.postedCountForTest())
        assertEquals(0, coalescer.latestCountForTest())
        permissions.setStatus(CommonPermission.Notifications, PermissionStatus.Granted)
        assertEquals(NotificationResult.Posted, post(notifier, "id-0", frame("t", 10)))
    }

    @Test
    fun `progress percentages far outside the range are clamped on the tray and never crash a post`() {
        val notifier: Notifier = notifier()
        listOf(Int.MIN_VALUE, -1, 0, 100, 101, Int.MAX_VALUE).forEachIndexed { index: Int, percent: Int ->
            val id = "p$index"
            val result: NotificationResult = post(notifier, id, frame("t$index", percent))
            assertTrue(result is NotificationResult.Posted, "percent=$percent gave $result")
        }

        shown.forEach { notification: Notification ->
            val progress: Int = notification.extras.getInt(Notification.EXTRA_PROGRESS)
            assertTrue(progress in 0..100, "tray progress $progress is out of range")
        }
        assertEquals(6, shown.size)
    }

    private companion object {
        const val WAIT_SECONDS: Long = 5L
    }
}
