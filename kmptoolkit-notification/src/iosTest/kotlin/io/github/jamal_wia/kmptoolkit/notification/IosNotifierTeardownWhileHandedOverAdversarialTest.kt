package io.github.jamal_wia.kmptoolkit.notification

import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionController
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSError
import platform.UserNotifications.UNNotificationRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration

/**
 * Round 15 (lifecycle chaos): the center holds a request that was handed over (`add` has been called,
 * its completion has not arrived) when the caller tears the tray down with `cancel(id)` or `cancelAll()`,
 * and only then does the completion arrive - with success or with an error.
 *
 * Contract: "`cancel` ... forgets its progress-coalescing state", "`cancelAll` ..." (the same), and
 * "a failed `add` does not become the coalescing baseline, so retrying the same progress posts". After a
 * teardown there is no baseline, so the same frame must post again whichever way the late completion goes.
 */
class IosNotifierTeardownWhileHandedOverAdversarialTest {

    private class HoldingCenter : NotificationCenterPort {
        var hold: Boolean = true
        val handedOver: CompletableDeferred<Unit> = CompletableDeferred()
        val held: MutableList<(NSError?) -> Unit> = mutableListOf()
        val events: MutableList<String> = mutableListOf()

        override fun add(request: UNNotificationRequest, completion: (NSError?) -> Unit) {
            events += "add:${request.identifier}"
            if (hold) {
                held += completion
                handedOver.complete(Unit)
            } else {
                completion(null)
            }
        }

        override fun removeDelivered(ids: List<String>) {
            events += "removeDelivered:${ids.joinToString("|")}"
        }

        override fun removePending(ids: List<String>) {
            events += "removePending:${ids.joinToString("|")}"
        }

        override fun removeAllDelivered() {
            events += "removeAllDelivered"
        }
    }

    private class AllowAll : PermissionController {
        override suspend fun check(permission: Permission): PermissionStatus = PermissionStatus.Granted
        override suspend fun request(permission: Permission): PermissionStatus = PermissionStatus.Granted
        override suspend fun request(permissions: Collection<Permission>): Map<Permission, PermissionStatus> =
            permissions.associateWith { PermissionStatus.Granted }
        override fun observe(permission: Permission): Flow<PermissionStatus> = flowOf(PermissionStatus.Granted)
        override fun openSettings(permission: Permission): Boolean = false
    }

    private val channel = NotificationChannelSpec(id = "c", name = "C")

    private fun frame(percent: Int): LocalNotification =
        LocalNotification("t", "b", channel, progress = NotificationProgress.Determinate(percent))

    private fun scenario(teardown: (Notifier) -> Unit, error: NSError?) {
        val center = HoldingCenter()
        val notifier: Notifier = IosNotifier(AllowAll(), ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO), center)
        runBlocking {
            withTimeout(10_000) {
                val post = async(start = CoroutineStart.UNDISPATCHED) { notifier.post("a", frame(50)) }
                center.handedOver.await()

                teardown(notifier)
                center.held.single().invoke(error)

                val late: NotificationResult = post.await()
                if (error == null) {
                    assertEquals(NotificationResult.Posted, late, "the center accepted the frame")
                } else {
                    assertIs<NotificationResult.Failed>(late)
                }
                center.hold = false
                assertEquals(
                    NotificationResult.Posted,
                    notifier.post("a", frame(50)),
                    "the teardown forgot the baseline: the same progress must post again",
                )
            }
        }
    }

    @Test
    fun `cancel of the id after the hand-over then a successful completion leaves no ghost baseline`() =
        scenario({ it.cancel("a") }, error = null)

    @Test
    fun `cancelAll after the hand-over then a successful completion leaves no ghost baseline`() =
        scenario({ it.cancelAll() }, error = null)

    @Test
    fun `cancel of the id after the hand-over then a failed completion leaves no ghost baseline`() =
        scenario({ it.cancel("a") }, error = NSError(domain = "r15", code = 7, userInfo = null))

    @Test
    fun `cancelAll after the hand-over then a failed completion leaves no ghost baseline`() =
        scenario({ it.cancelAll() }, error = NSError(domain = "r15", code = 7, userInfo = null))
}
