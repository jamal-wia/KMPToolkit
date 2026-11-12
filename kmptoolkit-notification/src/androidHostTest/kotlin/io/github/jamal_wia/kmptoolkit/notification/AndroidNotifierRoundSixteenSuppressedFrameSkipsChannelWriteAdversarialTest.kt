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
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

/**
 * Round 16 (docs 03-guide "What a suppressed frame still costs"): a suppressed frame "skips ... the persisted
 * channel write". Counted on the platform: one write for the first frame, none for any coalesced one.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26, 34], shadows = [AndroidNotifierRoundSixteenSuppressedFrameSkipsChannelWriteAdversarialTest.CountingShadow::class])
class AndroidNotifierRoundSixteenSuppressedFrameSkipsChannelWriteAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "r16-write", name = "R16 write")

    private fun frame(percent: Int): LocalNotification =
        LocalNotification(title = "t", body = "b", channel = channel, progress = NotificationProgress.Determinate(percent))

    @Test
    fun `coalesced frames never write the channel`() {
        context.getSystemService(NotificationManager::class.java).cancelAll()
        val notifier: Notifier = AndroidNotifier(
            context, permissions, NotificationRenderer(context, "test.ACTION"),
            ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO),
        )
        CountingShadow.writes.set(0)
        assertEquals(NotificationResult.Posted, runBlocking { notifier.post("a", frame(30)) })
        val afterFirst: Int = CountingShadow.writes.get()
        assertEquals(1, afterFirst, "the first frame writes the channel once")

        repeat(25) { assertEquals(NotificationResult.Coalesced, runBlocking { notifier.post("a", frame(31 + it % 8)) }) }

        assertEquals(afterFirst, CountingShadow.writes.get(), "a suppressed frame must not write the channel")
    }

    @Implements(NotificationManager::class)
    class CountingShadow : ShadowNotificationManager() {
        @Implementation
        public override fun createNotificationChannel(channel: NotificationChannel) {
            writes.incrementAndGet()
            super.createNotificationChannel(channel)
        }

        companion object {
            val writes: AtomicInteger = AtomicInteger(0)
        }
    }
}
