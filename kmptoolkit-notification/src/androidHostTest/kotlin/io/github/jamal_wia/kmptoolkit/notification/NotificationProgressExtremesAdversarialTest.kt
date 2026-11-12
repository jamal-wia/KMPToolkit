package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Round 15 (contract lawyer): "`percent` is clamped to `0..100` for both rendering and coalescing"
 * (04-api-reference, `NotificationProgress`) and "Two updates always get through ... a `Determinate(100)`"
 * (03-guide), taken together at the extremes of `Int` through the real Android notifier with a one-hour
 * rate limit, so only the bucket and the terminal rule can decide.
 */
@RunWith(AndroidJUnit4::class)
class NotificationProgressExtremesAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val channel = NotificationChannelSpec(id = "extreme", name = "Extreme")

    private fun notifier(): Notifier = AndroidNotifier(
        context,
        FakePermissionController(defaultStatus = PermissionStatus.Granted),
        NotificationRenderer(context, "test.ACTION"),
        ProgressCoalescer(bucketPercent = 10, minInterval = 1.hours),
    )

    private fun frame(percent: Int): LocalNotification =
        LocalNotification("t", "b", channel, progress = NotificationProgress.Determinate(percent))

    private fun shownProgress(): Pair<Int, Int> {
        val extras = shadowOf(manager).allNotifications.single().extras
        return extras.getInt(Notification.EXTRA_PROGRESS) to extras.getInt(Notification.EXTRA_PROGRESS_MAX)
    }

    @Test
    fun `an out of range percent is drawn clamped to the bar`() {
        val expectations: List<Pair<Int, Int>> = listOf(
            -1 to 0, Int.MIN_VALUE to 0, 101 to 100, 1_000 to 100, Int.MAX_VALUE to 100, 0 to 0, 100 to 100, 37 to 37,
        )
        for ((given: Int, drawn: Int) in expectations) {
            val notifier: Notifier = notifier()
            notifier.cancel("a")
            assertEquals(NotificationResult.Posted, runBlocking { notifier.post("a", frame(given)) }, "first frame $given")
            assertEquals(drawn to 100, shownProgress(), "Determinate($given) must be drawn as $drawn of 100")
        }
    }

    @Test
    fun `a percent above 100 is the terminal frame - never held back even straight after another one`() {
        val notifier: Notifier = notifier()

        assertEquals(NotificationResult.Posted, runBlocking { notifier.post("a", frame(100)) })
        assertEquals(NotificationResult.Posted, runBlocking { notifier.post("a", frame(100)) }, "100 is never suppressed")
        assertEquals(NotificationResult.Posted, runBlocking { notifier.post("a", frame(Int.MAX_VALUE)) }, "clamped to 100, so never suppressed")
        assertEquals(NotificationResult.Posted, runBlocking { notifier.post("a", frame(250)) })
        // ... and each of them reset the state, so the next run starts fresh with its first frame.
        assertEquals(NotificationResult.Posted, runBlocking { notifier.post("a", frame(3)) })
        assertEquals(NotificationResult.Coalesced, runBlocking { notifier.post("a", frame(4)) }, "same bucket, inside the rate limit")
    }

    @Test
    fun `a percent below zero is the bottom of the first bucket for coalescing`() {
        val notifier: Notifier = notifier()

        assertEquals(NotificationResult.Posted, runBlocking { notifier.post("a", frame(Int.MIN_VALUE)) })
        assertEquals(NotificationResult.Coalesced, runBlocking { notifier.post("a", frame(0)) }, "-inf clamps to 0: same bucket as 0")
        assertEquals(NotificationResult.Coalesced, runBlocking { notifier.post("a", frame(-50)) })
    }
}
