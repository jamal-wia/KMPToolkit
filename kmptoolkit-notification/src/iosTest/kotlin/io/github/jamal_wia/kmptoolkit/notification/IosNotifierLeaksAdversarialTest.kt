package io.github.jamal_wia.kmptoolkit.notification

import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSError
import platform.UserNotifications.UNNotificationRequest

/**
 * Round 18 (leaks), iOS: the notifier holds no process-wide registration (it and its permission controller are
 * collectable once dropped), and a post whose hand-over to the center was cancelled leaves nothing that still
 * points at its notification once the center lets go of its completion handler.
 */
@OptIn(ExperimentalNativeApi::class, NativeRuntimeApi::class)
class IosNotifierLeaksAdversarialTest {

    private class ParkingCenter : NotificationCenterPort {
        val completions: MutableList<(NSError?) -> Unit> = mutableListOf()
        val delivered: MutableMap<String, UNNotificationRequest> = mutableMapOf()
        var park: Boolean = false
        override fun add(request: UNNotificationRequest, completion: (NSError?) -> Unit) {
            delivered[request.identifier] = request
            if (park) completions += completion else completion(null)
        }

        override fun removeDelivered(ids: List<String>) {
            ids.forEach { delivered.remove(it) }
        }

        override fun removePending(ids: List<String>) = Unit
        override fun removeAllDelivered() {
            delivered.clear()
        }
    }

    private fun gone(reference: WeakReference<*>): Boolean {
        repeat(20) {
            GC.collect()
            if (reference.value == null) return true
        }
        return reference.value == null
    }

    private fun frame(i: Int, progress: NotificationProgress? = null): LocalNotification =
        LocalNotification(title = "t$i", body = "b$i", channel = NotificationChannelSpec("c${i % 5}", "C"), progress = progress)

    @Test
    fun `a notifier and its permission controller are collectable once dropped after thousands of cycles`() {
        fun use(): Pair<WeakReference<Notifier>, WeakReference<MutablePermissions>> {
            val permissions = MutablePermissions(PermissionStatus.Granted)
            val clock = TestTimeSource()
            val center = ParkingCenter()
            val notifier: Notifier = IosNotifier(permissions, NotifierProtocolFuzz.newCoalescer(clock), center)
            runBlocking {
                repeat(3_000) { i: Int ->
                    notifier.post("id${i % 40}", frame(i, if (i % 3 == 0) NotificationProgress.Determinate(i % 100) else null))
                    if (i % 4 == 0) notifier.cancel("id${i % 40}")
                    if (i % 701 == 0) notifier.cancelAll()
                }
            }
            return WeakReference(notifier) to WeakReference(permissions)
        }
        val (notifier, permissions) = use()
        assertTrue(gone(notifier), "the notifier is held by something process wide")
        assertTrue(gone(permissions), "the permission controller is held by something process wide")
    }

    @Test
    fun `a cancelled hand-over leaves nothing pointing at its notification once the center drops its handler`() {
        val permissions = MutablePermissions(PermissionStatus.Granted)
        val center = ParkingCenter().also { it.park = true }
        val notifier: Notifier = IosNotifier(permissions, NotifierProtocolFuzz.newCoalescer(TestTimeSource()), center)
        val references: MutableList<WeakReference<NotificationChannelSpec>> = mutableListOf()
        // The loop has its own runBlocking so that no coroutine of the test is still running when the references are counted.
        runBlocking {
            withTimeout(60_000) {
                repeat(800) { i: Int ->
                    val notification: LocalNotification = frame(i, NotificationProgress.Determinate(i % 100))
                    // The probe is the channel spec object, which every copy of the notification shares: it finds a retained copy too.
                    references += WeakReference(notification.channel)
                    val job = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { notifier.post("id${i % 30}", notification) }
                    job.cancel()
                }
            }
        }
        // The center lets go of everything it was handed: a late answer, or the app dropping the handlers.
        center.completions.clear()
        center.delivered.clear()
        repeat(20) { GC.collect() }
        val survivors: Int = references.count { it.value != null }
        // The coalescer keeps the newest ticket of each id until the id is cancelled or ages out: thirty ids, never 800 posts.
        assertTrue(survivors <= 30 + 5, "$survivors of ${references.size} cancelled posts still have their notification reachable")
        center.park = false
        assertEquals(NotificationResult.Posted, runBlocking { notifier.post("fresh", frame(9)) })
        notifier.cancelAll()
        repeat(20) { GC.collect() }
        assertTrue(references.count { it.value != null } <= 5, "cancelAll leaves the state of the cancelled posts behind")
    }
}
