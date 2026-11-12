package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

/**
 * Round 16 (regression hunt on the round 15 horizon fix), end to end through the real Android notifier: the
 * tray shows the 30 percent frame, the run keeps sending frames, one later frame is refused by the platform
 * (a `Failed`), and the next frame of the 30 percent tenth is still redundant because the frame on the tray
 * has not changed (03-guide: the bucket is compared against "the last one actually posted").
 */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [AndroidNotifierRoundSixteenRefusedFrameBaselineAdversarialTest.OneShotRefusingShadow::class])
class AndroidNotifierRoundSixteenRefusedFrameBaselineAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "r16", name = "R16")
    private val time = TestTimeSource()

    private fun frame(percent: Int): LocalNotification =
        LocalNotification(title = "t", body = "b", channel = channel, progress = NotificationProgress.Determinate(percent))

    private fun runScenario(liveFor: kotlin.time.Duration): NotificationResult {
        manager.cancelAll()
        OneShotRefusingShadow.refuseNext.set(false)
        val notifier: Notifier = AndroidNotifier(
            context,
            permissions,
            NotificationRenderer(context, "test.ACTION"),
            ProgressCoalescer(bucketPercent = 10, minInterval = 500.milliseconds, timeSource = time),
        )
        assertEquals(NotificationResult.Posted, runBlocking { notifier.post("a", frame(30)) })
        var elapsed: kotlin.time.Duration = kotlin.time.Duration.ZERO
        while (elapsed < liveFor) {
            time += 1.seconds
            elapsed += 1.seconds
            assertEquals(NotificationResult.Coalesced, runBlocking { notifier.post("a", frame(31)) })
        }
        OneShotRefusingShadow.refuseNext.set(true)
        val refused: NotificationResult = runBlocking { notifier.post("a", frame(45)) }
        assertTrue(refused is NotificationResult.Failed, "the platform refused this frame: $refused")
        return runBlocking { notifier.post("a", frame(31)) }
    }

    @Test
    fun `after a refused frame a short live run still coalesces a frame of the tenth that is on the tray`() {
        assertEquals(NotificationResult.Coalesced, runScenario(5.seconds))
    }

    @Test
    fun `after a refused frame a run that was live for fifteen minutes still coalesces a frame of the tenth on the tray`() {
        assertEquals(NotificationResult.Coalesced, runScenario(15.minutes))
    }

    @Implements(NotificationManager::class)
    class OneShotRefusingShadow : ShadowNotificationManager() {

        @Implementation
        public override fun notify(tag: String?, id: Int, notification: Notification?) {
            if (refuseNext.getAndSet(false)) throw SecurityException("r16 refused")
            super.notify(tag, id, notification)
        }

        companion object {
            val refuseNext: AtomicBoolean = AtomicBoolean(false)
        }
    }
}
