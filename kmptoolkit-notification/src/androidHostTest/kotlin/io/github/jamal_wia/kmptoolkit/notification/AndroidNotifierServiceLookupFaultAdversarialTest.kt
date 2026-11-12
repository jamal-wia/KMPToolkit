package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Round 15 (contract lawyer, resource starvation): the framework `NotificationManager` cannot be
 * looked up. Either the lookup answers null, or it throws (a system server that is restarting).
 *
 * Contract: Notifier KDoc / 04-api-reference "Every runtime failure is a `NotificationResult`, never an
 * exception"; "`cancel` ... Never throws for a platform refusal (a dead binder, a revoked right)";
 * "`cancelAll` ... Never throws either"; Failed "framework refusal"; and "if a post returns `Failed`,
 * retrying the same progress posts it again instead of answering `Coalesced`".
 */
@RunWith(AndroidJUnit4::class)
class AndroidNotifierServiceLookupFaultAdversarialTest {

    private val real: Context = ApplicationProvider.getApplicationContext()
    private val mode: AtomicReference<String> = AtomicReference("healthy")
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "lookup", name = "Lookup")

    private inner class FlakyContext(base: Context) : ContextWrapper(base) {
        private fun lookupFault(isNotificationManager: Boolean): Boolean? = when {
            !isNotificationManager -> null
            mode.get() == "throws" -> throw IllegalStateException("system server is restarting")
            mode.get() == "null" -> true
            else -> null
        }

        override fun getSystemService(name: String): Any? =
            if (lookupFault(name == Context.NOTIFICATION_SERVICE) == true) null else super.getSystemService(name)

        override fun getApplicationContext(): Context = this
    }

    private fun notifier(): Notifier = AndroidNotifier(
        FlakyContext(real), permissions, NotificationRenderer(real, "test.ACTION"), ProgressCoalescer(minInterval = Duration.ZERO),
    )

    private fun frame(percent: Int): LocalNotification = LocalNotification(
        title = "t", body = "b", channel = channel, progress = NotificationProgress.Determinate(percent),
    )

    private val tray: List<String>
        get() = shadowOf(real.getSystemService(NotificationManager::class.java)).allNotifications
            .map { it.extras.getString(Notification.EXTRA_TITLE).orEmpty() }

    @Test
    fun `a lookup that throws turns the post into Failed never an exception and the same progress posts once the service is back`() {
        val notifier: Notifier = notifier()
        mode.set("throws")

        val outcome: Result<NotificationResult> = runCatching { runBlocking { withTimeout(10_000) { notifier.post("a", frame(50)) } } }

        assertNull(outcome.exceptionOrNull(), "post threw ${outcome.exceptionOrNull()}, but every runtime failure is a NotificationResult")
        assertIs<NotificationResult.Failed>(outcome.getOrNull())
        assertEquals(emptyList(), tray)
        mode.set("healthy")
        assertEquals(
            NotificationResult.Posted,
            runBlocking { notifier.post("a", frame(50)) },
            "a failed post must not stand as the baseline: the retry of the same progress has to post",
        )
        assertEquals(1, tray.size)
    }

    @Test
    fun `a lookup that answers null never throws from post cancel or cancelAll`() {
        val notifier: Notifier = notifier()
        runBlocking { notifier.post("kept", frame(10)) }
        mode.set("null")

        val post: Throwable? = runCatching { runBlocking { withTimeout(10_000) { notifier.post("a", frame(50)) } } }.exceptionOrNull()
        val cancel: Throwable? = runCatching { notifier.cancel("a") }.exceptionOrNull()
        val cancelAll: Throwable? = runCatching { notifier.cancelAll() }.exceptionOrNull()

        assertNull(post, "post threw $post")
        assertNull(cancel, "cancel threw $cancel")
        assertNull(cancelAll, "cancelAll threw $cancelAll")
    }

    @Test
    fun `cancel and cancelAll through a lookup that throws never throw`() {
        // The notifier keeps NotificationManagerCompat from its construction, so the lookup is only
        // repeated for channels; the removal calls must be indifferent to it.
        val notifier: Notifier = notifier()
        runBlocking { notifier.post("a", frame(50)) }
        mode.set("throws")

        val cancel: Throwable? = runCatching { notifier.cancel("a") }.exceptionOrNull()
        val cancelAll: Throwable? = runCatching { notifier.cancelAll() }.exceptionOrNull()

        assertNull(cancel, "cancel threw $cancel")
        assertNull(cancelAll, "cancelAll threw $cancelAll")
    }
}
