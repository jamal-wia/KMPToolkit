package io.github.jamal_wia.kmptoolkit.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
 * Round 19 (OS-state flips, bounded resources): the platform refuses to create more channels than a
 * package may own (Android: an `IllegalStateException` from `createNotificationChannel` past its
 * limit), and the user deletes channels behind the notifier's back. 05-platform-notes: "a channel that
 * is missing is still created" even for a frame about to be coalesced; the channel cache is
 * "bounded by channel ids"; nothing is thrown for a runtime condition. A refused creation must be a
 * [NotificationResult.Failed], must not enter the cache (a cached spec that was never written would hide
 * the missing channel from every later post), and must not harm the channels that exist.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], shadows = [AndroidNotifierChannelLimitAndDeletionAdversarialTest.LimitedShadowNotificationManager::class])
class AndroidNotifierChannelLimitAndDeletionAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)

    @After
    fun heal() {
        LimitedShadowNotificationManager.limit.set(Int.MAX_VALUE)
        LimitedShadowNotificationManager.creations.set(0)
    }

    private fun channel(n: Int): NotificationChannelSpec = NotificationChannelSpec(id = "c$n", name = "Channel $n")

    private fun frame(spec: NotificationChannelSpec, percent: Int? = null): LocalNotification = LocalNotification(
        title = "t",
        body = "b",
        channel = spec,
        progress = percent?.let { NotificationProgress.Determinate(it) },
    )

    private fun newNotifier(): AndroidNotifier = AndroidNotifier(
        context, permissions, NotificationRenderer(context, "test.ACTION"),
        ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO),
    )

    private fun post(notifier: Notifier, id: String, notification: LocalNotification): NotificationResult =
        runBlocking { notifier.post(id, notification) }

    @Test
    fun `a channel the platform refuses to create is a failure that is not cached and recovers when room is made`() {
        val notifier: AndroidNotifier = newNotifier()
        LimitedShadowNotificationManager.limit.set(5)
        for (n: Int in 0 until 5) {
            assertEquals(NotificationResult.Posted, post(notifier, "n$n", frame(channel(n))))
        }
        assertEquals(5, notifier.ensuredChannelCountForTest())

        repeat(3) {
            val refused: NotificationResult = post(notifier, "over", frame(channel(99)))
            assertTrue(refused is NotificationResult.Failed, "past the platform limit the post must be a Failed, was $refused")
        }
        assertEquals(5, notifier.ensuredChannelCountForTest(), "a channel that was never created must not be remembered as ensured")
        assertEquals(5, shadowOf(manager).notificationChannels.size)
        assertEquals(null, shadowOf(manager).getNotification("over", 1), "nothing may be on the tray for the refused post")

        // The channels that exist are untouched by the refusals.
        assertEquals(NotificationResult.Posted, post(notifier, "n0", frame(channel(0))))
        assertEquals(NotificationResult.Posted, post(notifier, "n4", frame(channel(4), percent = 20)))

        // The user frees a channel: the very next post of the refused channel creates it and posts.
        manager.deleteNotificationChannel("c1")
        assertEquals(NotificationResult.Posted, post(notifier, "over", frame(channel(99))))
        assertTrue(shadowOf(manager).getNotification("over", 1) != null, "the post that recovered must be on the tray")
        assertEquals(setOf("c0", "c2", "c3", "c4", "c99"), shadowOf(manager).notificationChannels.map { (it as NotificationChannel).id }.toSet())
    }

    @Test
    fun `a channel the user deleted is created again even for a frame that is about to be coalesced`() {
        val notifier: AndroidNotifier = newNotifier()
        val spec: NotificationChannelSpec = channel(1)
        assertEquals(NotificationResult.Posted, post(notifier, "p", frame(spec, percent = 40)))
        assertEquals(1, shadowOf(manager).notificationChannels.size)

        manager.deleteNotificationChannel(spec.id)
        assertEquals(0, shadowOf(manager).notificationChannels.size)

        // Same bucket, same content: a coalesced frame, which still has to bring the channel back.
        assertEquals(NotificationResult.Coalesced, post(notifier, "p", frame(spec, percent = 41)))
        assertEquals(
            listOf(spec.id),
            shadowOf(manager).notificationChannels.map { (it as NotificationChannel).id },
            "the platform drops a notification on a channel that does not exist without a word, so even a suppressed frame recreates it",
        )

        // Deleted, refused, deleted again and again: never an exception, the cache never outgrows the ids.
        repeat(50) { round: Int ->
            manager.deleteNotificationChannel(spec.id)
            val result: NotificationResult = post(notifier, "p", frame(spec, percent = 50 + round % 5))
            assertTrue(result == NotificationResult.Coalesced || result == NotificationResult.Posted, "round $round: $result")
            assertEquals(1, shadowOf(manager).notificationChannels.size, "round $round")
        }
        assertEquals(1, notifier.ensuredChannelCountForTest())
    }

    @Test
    fun `thousands of distinct channels keep the cache at one entry per id and the cost of a post flat`() {
        val notifier: AndroidNotifier = newNotifier()
        val total = 6_000
        fun timeBatch(from: Int, count: Int): Long {
            val start: Long = System.nanoTime()
            for (n: Int in from until from + count) {
                assertEquals(NotificationResult.Posted, post(notifier, "n", frame(channel(n))))
            }
            return System.nanoTime() - start
        }
        val warm: Long = timeBatch(0, 500)
        timeBatch(500, total - 1_000)
        val late: Long = timeBatch(total - 500, 500)
        assertEquals(total, notifier.ensuredChannelCountForTest())
        // A post whose cost grows with the number of channels already known would show here: the last 500 posts
        // (about 6000 entries known) against the first 500 (fewer than 500 known). Generous: Robolectric's own cost dominates.
        assertTrue(late < warm * 12 + 2_000_000_000L, "late batch took ${late / 1_000_000} ms against ${warm / 1_000_000} ms for the first")
    }

    /** Refuses to create a channel the way the platform does past its per-package limit. */
    @Implements(NotificationManager::class)
    class LimitedShadowNotificationManager : ShadowNotificationManager() {

        @Implementation
        public override fun createNotificationChannel(channel: NotificationChannel) {
            val known: Boolean = getNotificationChannel(channel.id) != null
            if (!known && notificationChannels.size >= limit.get()) throw IllegalStateException("Too many channels")
            creations.incrementAndGet()
            super.createNotificationChannel(channel)
        }

        companion object {
            val limit: AtomicInteger = AtomicInteger(Int.MAX_VALUE)
            val creations: AtomicInteger = AtomicInteger(0)
        }
    }
}
