package io.github.jamal_wia.kmptoolkit.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Round 15 (contract lawyer): Notifier KDoc, "[post] is suspending because checking whether you may
 * post is asynchronous on iOS. On Android it does not suspend in practice." A caller on the main thread
 * relies on it: a post whose permission answer is already there completes before `post` returns, for
 * every outcome (posted, coalesced, refused by a gate, failed), at API levels with and without the
 * runtime permission.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [32, 35])
class AndroidNotifierDoesNotSuspendAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val channel = NotificationChannelSpec(id = "sync", name = "Sync")

    private fun notifier(status: PermissionStatus): Notifier = AndroidNotifier(
        context,
        FakePermissionController(defaultStatus = status),
        NotificationRenderer(context, "test.ACTION"),
        ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO),
    )

    /** Runs [call] until its first suspension point; `null` means it suspended. */
    private fun <T> immediately(call: suspend () -> T): T? {
        val outcome: Any? = call.startCoroutineUninterceptedOrReturn(Continuation(EmptyCoroutineContext) { })
        @Suppress("UNCHECKED_CAST")
        return if (outcome === COROUTINE_SUSPENDED) null else outcome as T
    }

    @Test
    fun `post completes without suspending for every outcome when the permission answer is immediate`() {
        val notifier: Notifier = notifier(PermissionStatus.Granted)
        val denied: Notifier = notifier(PermissionStatus.Denied())
        val progress = { percent: Int -> LocalNotification("t", "b", channel, progress = NotificationProgress.Determinate(percent)) }

        assertEquals(NotificationResult.Posted, immediately { notifier.post("a", progress(10)) })
        assertEquals(NotificationResult.Coalesced, immediately { notifier.post("a", progress(11)) })
        assertEquals(NotificationResult.Posted, immediately { notifier.post("a", progress(100)) })
        assertNotEquals(null, immediately { notifier.post("a", LocalNotification("t", "b", channel), NotificationOptions(alertOnce = false)) })
        assertNotEquals(null, immediately { denied.post("a", progress(10)) }, "a refusal by a gate must not suspend either")
        assertNotEquals(
            null,
            immediately { notifier.post("x", LocalNotification("t", "b", channel, icon = NotificationIcon.AndroidDrawable(0x7fffffff))) },
            "a failure must not suspend either",
        )
    }
}
