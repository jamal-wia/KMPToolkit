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
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

/**
 * Round 18 (R18-S1): the channel cache is documented to re-apply a changed `name` or `description`, so a channel whose
 * description carries changing text is a documented use. The cache must stay bounded by the number of channel ids, a
 * changed spec must still be written, an unchanged one must not, and two ids must not disturb each other.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26, 34], shadows = [AndroidNotifierEnsuredChannelsBoundAdversarialTest.CountingShadow::class])
class AndroidNotifierEnsuredChannelsBoundAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun newNotifier(): AndroidNotifier {
        context.getSystemService(NotificationManager::class.java).cancelAll()
        return AndroidNotifier(
            context,
            FakePermissionController(defaultStatus = PermissionStatus.Granted),
            NotificationRenderer(context, "test.ACTION"),
            ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO),
        )
    }

    private fun post(notifier: Notifier, id: String, spec: NotificationChannelSpec): NotificationResult =
        runBlocking { withTimeout(10_000) { notifier.post(id, LocalNotification("t", "b", spec)) } }

    private fun description(id: String): String? =
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(id)?.description

    @Test
    fun `one channel whose description changes keeps one entry not one per description`() {
        val notifier: AndroidNotifier = newNotifier()
        repeat(3_000) { i: Int ->
            val spec = NotificationChannelSpec(id = "sync", name = "Sync", description = "Last sync: $i")
            assertEquals(NotificationResult.Posted, post(notifier, "job", spec))
        }
        assertEquals(1, notifier.ensuredChannelCountForTest())
        assertEquals("Last sync: 2999", description("sync"))
    }

    @Test
    fun `a changed spec is written again and an unchanged one is not`() {
        val notifier: AndroidNotifier = newNotifier()
        val v1 = NotificationChannelSpec(id = "r18-changed", name = "N", description = "one")
        val v2 = v1.copy(description = "two")
        CountingShadow.writes.set(0)
        post(notifier, "a", v1)
        assertEquals(1, CountingShadow.writes.get())
        post(notifier, "a", v1)
        assertEquals(1, CountingShadow.writes.get(), "an unchanged spec must not be written again")
        post(notifier, "a", v2)
        assertEquals(2, CountingShadow.writes.get(), "a changed spec must be written")
        assertEquals("two", description("r18-changed"))
        post(notifier, "a", v2)
        assertEquals(2, CountingShadow.writes.get())
    }

    @Test
    fun `two channel ids stay independent`() {
        val notifier: AndroidNotifier = newNotifier()
        val a = NotificationChannelSpec(id = "r18-a", name = "A", description = "a1")
        val b = NotificationChannelSpec(id = "r18-b", name = "B", description = "b1")
        CountingShadow.writes.set(0)
        post(notifier, "x", a)
        post(notifier, "y", b)
        assertEquals(2, CountingShadow.writes.get())
        assertEquals(2, notifier.ensuredChannelCountForTest())

        post(notifier, "x", a.copy(description = "a2"))
        assertEquals(3, CountingShadow.writes.get(), "changing A writes A only")
        post(notifier, "y", b)
        assertEquals(3, CountingShadow.writes.get(), "B is still cached after A changed")
        assertEquals("a2", description("r18-a"))
        assertEquals("b1", description("r18-b"))
        assertTrue(notifier.ensuredChannelCountForTest() == 2)
    }

    @Implements(NotificationManager::class)
    class CountingShadow : ShadowNotificationManager() {
        @Implementation
        public override fun createNotificationChannel(channel: NotificationChannel) {
            writes.incrementAndGet()
            super.createNotificationChannel(channel)
        }

        companion object {
            val writes: AtomicInteger = AtomicInteger()
        }
    }
}
