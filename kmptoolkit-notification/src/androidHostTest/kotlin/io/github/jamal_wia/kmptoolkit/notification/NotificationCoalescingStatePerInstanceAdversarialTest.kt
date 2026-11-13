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
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Round 23 (documented sentence changed after round 20, guide "Cancel and concurrent posts"): "Coalescing
 * state is per instance too: a `cancel` made through another instance is, to this one, like a swipe; a
 * same-bucket frame afterwards can answer `Coalesced` with nothing showing, until the bar crosses a bucket,
 * the content changes, or the id has been quiet for the abandoned-run horizon."
 *
 * Every clause is pinned here with an injected clock (virtual time, no sleep): the residue itself, the
 * three ways out, and the horizon's two sizes (ten minutes, or 120 rate intervals when that is larger). The
 * round 20 suspect asserted the residue as a defect; the docs now call it the documented behaviour of two
 * instances, so this class pins exactly that and nothing more.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NotificationCoalescingStatePerInstanceAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "per-instance", name = "Per instance")
    private val clock = TestTimeSource()

    private fun notifier(minInterval: Duration): Notifier = AndroidNotifier(
        context = context,
        permissions = permissions,
        renderer = NotificationRenderer(context, "test.per.instance.ACTION"),
        coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = minInterval, timeSource = clock),
    )

    private fun frame(title: String, percent: Int): LocalNotification = LocalNotification(
        title = title,
        body = "b",
        channel = channel,
        progress = NotificationProgress.Determinate(percent),
    )

    private fun post(n: Notifier, id: String, notification: LocalNotification): NotificationResult =
        runBlocking { withTimeout(15_000L) { n.post(id, notification) } }

    private fun shownTitle(id: String): String? =
        shadowOf(manager).getNotification(id, 1)?.extras?.getString(Notification.EXTRA_TITLE)

    /** A shows 20 percent, B cancels the id, A is left with a baseline for a notification nobody shows. */
    private fun leaveGhostBaseline(a: Notifier, b: Notifier) {
        assertEquals(NotificationResult.Posted, post(a, "dl", frame("run", 20)))
        assertEquals("run", shownTitle("dl"))
        b.cancel("dl")
        assertNull(shownTitle("dl"), "control: the other instance's cancel removed the notification from the shared tray")
    }

    @Test
    fun `a cancel made through another instance leaves a same-bucket same-content frame answered Coalesced with nothing showing`() {
        val a: Notifier = notifier(500.milliseconds)
        val b: Notifier = notifier(500.milliseconds)
        leaveGhostBaseline(a, b)

        clock += 1.seconds
        assertEquals(NotificationResult.Coalesced, post(a, "dl", frame("run", 21)), "documented: like a swipe, the baseline stands")
        assertNull(shownTitle("dl"), "documented: Coalesced with nothing showing")
    }

    @Test
    fun `the ghost baseline ends when the bar crosses a bucket`() {
        val a: Notifier = notifier(500.milliseconds)
        leaveGhostBaseline(a, notifier(500.milliseconds))
        clock += 1.seconds
        assertEquals(NotificationResult.Coalesced, post(a, "dl", frame("run", 21)))

        clock += 1.seconds
        assertEquals(NotificationResult.Posted, post(a, "dl", frame("run", 30)))
        assertEquals("run", shownTitle("dl"))
    }

    @Test
    fun `the ghost baseline ends when the content changes even inside the bucket`() {
        val a: Notifier = notifier(500.milliseconds)
        leaveGhostBaseline(a, notifier(500.milliseconds))
        clock += 1.seconds
        assertEquals(NotificationResult.Coalesced, post(a, "dl", frame("run", 21)))

        clock += 1.seconds
        assertEquals(NotificationResult.Posted, post(a, "dl", frame("run, page 2", 21)))
        assertEquals("run, page 2", shownTitle("dl"))
    }

    @Test
    fun `the ghost baseline ends when the id has been quiet for ten minutes at the default rate`() {
        val a: Notifier = notifier(500.milliseconds)
        leaveGhostBaseline(a, notifier(500.milliseconds))

        // 120 x 500 ms is one minute, so the larger horizon is the ten minutes.
        clock += 9.minutes + 59.seconds
        assertEquals(
            NotificationResult.Coalesced,
            post(a, "dl", frame("run", 21)),
            "quiet for less than the horizon: still the ghost",
        )
        // That suppressed frame counted as activity ("no frame was posted or suppressed"): the quiet time restarts from
        // it, so another 9m59s later the id is still not forgotten although 19 minutes passed since the last post.
        clock += 9.minutes + 59.seconds
        assertEquals(NotificationResult.Coalesced, post(a, "dl", frame("run", 21)), "a suppressed frame is activity")
        clock += 10.minutes + 1.seconds
        assertEquals(NotificationResult.Posted, post(a, "dl", frame("run", 21)), "quiet for longer than ten minutes: forgotten")
        assertEquals("run", shownTitle("dl"))
    }

    @Test
    fun `the quiet horizon is 120 rate intervals when that is larger than ten minutes`() {
        val interval: Duration = 10.seconds // 120 x 10 s = 20 minutes
        val a: Notifier = notifier(interval)
        leaveGhostBaseline(a, notifier(interval))

        clock += 19.minutes + 59.seconds
        assertEquals(NotificationResult.Coalesced, post(a, "dl", frame("run", 21)), "inside 20 minutes: still the ghost")
        clock += 20.minutes + 1.seconds
        assertEquals(NotificationResult.Posted, post(a, "dl", frame("run", 21)), "past 20 minutes: forgotten")
    }

    @Test
    fun `a cancel through the same instance forgets the baseline at once, unlike another instance's cancel`() {
        val a: Notifier = notifier(500.milliseconds)
        assertEquals(NotificationResult.Posted, post(a, "dl", frame("run", 20)))
        a.cancel("dl")
        clock += 1.seconds
        assertEquals(NotificationResult.Posted, post(a, "dl", frame("run", 21)), "own cancel: the next frame is the first of a new run")
        assertEquals("run", shownTitle("dl"))
    }
}
