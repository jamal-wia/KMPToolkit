package io.github.jamal_wia.kmptoolkit.notification

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Round 19 (time, OS-state flips): the rate limit of the progress coalescer runs on a monotonic clock, not on wall time. The
 * device date is set backwards by ten hours, forwards by a day, and the time zone changed, between frames of one
 * run; the interval between two frames is still the real elapsed time. A frame inside the interval is Coalesced
 * whatever the date says, and a frame after it posts. (A coalescer reading the wall clock would post the frame
 * after the forward jump at once and hold the one after the backward jump for hours.)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NotificationWallClockIndependenceAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val originalZone: TimeZone = TimeZone.getDefault()

    @After
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    private fun frame(percent: Int): LocalNotification = LocalNotification(
        title = "t",
        body = "b",
        channel = NotificationChannelSpec(id = "clock", name = "Clock"),
        progress = NotificationProgress.Determinate(percent),
    )

    @Test
    fun `setting the device date backwards or forwards or changing the zone does not move the progress rate limit`() {
        val notifier: Notifier = createNotifier(
            context,
            FakePermissionController(defaultStatus = PermissionStatus.Granted),
            NotificationConfig(progressBucketPercent = 10, minProgressInterval = 400.milliseconds),
        )
        assertEquals(NotificationResult.Posted, runBlocking { notifier.post("run", frame(10)) })

        // Ten hours back: a different bucket, but inside the interval in real time.
        SystemClock.setCurrentTimeMillis(System.currentTimeMillis() - 10L * 3_600_000L)
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Apia"))
        assertEquals(NotificationResult.Coalesced, runBlocking { notifier.post("run", frame(30)) })

        Thread.sleep(500)
        assertEquals(NotificationResult.Posted, runBlocking { notifier.post("run", frame(30) ) }, "after the real interval the frame posts, whatever the date says")

        // A day forward: still inside the real interval of the frame just posted.
        SystemClock.setCurrentTimeMillis(System.currentTimeMillis() + 24L * 3_600_000L)
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
        assertEquals(NotificationResult.Coalesced, runBlocking { notifier.post("run", frame(50)) }, "a day of wall time must not count as elapsed")

        Thread.sleep(500)
        assertEquals(NotificationResult.Posted, runBlocking { notifier.post("run", frame(50)) })
    }
}
