package io.github.jamal_wia.kmptoolkit.notification

import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionController
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSError
import platform.UserNotifications.UNNotificationRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Round 19 (process death), iOS: the process dies at every single call into the notification center during
 * a session of posts, cancels and a cancelAll, once before the call takes effect and once after it (the
 * completion is lost), and a new notifier takes over a center whose pending and delivered notifications
 * outlive it. 05-platform-notes: nothing to restore after a process death; `cancel(id)` "removes the
 * delivered notification and any pending request with that identifier"; `cancelAll` removes delivered
 * notifications only. Whatever call the death landed on, the new process posts the same frame (it has no
 * baseline), and a `cancel` of every id of the session leaves neither a pending nor a delivered
 * notification behind.
 */
class IosNotifierKillAtEveryStepAdversarialTest {

    private class ProcessKilled(call: Int) : Error("process killed at center call $call")

    private class AllowAll : PermissionController {
        override suspend fun check(permission: Permission): PermissionStatus = PermissionStatus.Granted
        override suspend fun request(permission: Permission): PermissionStatus = PermissionStatus.Granted
        override suspend fun request(permissions: Collection<Permission>): Map<Permission, PermissionStatus> =
            permissions.associateWith { PermissionStatus.Granted }

        override fun observe(permission: Permission): Flow<PermissionStatus> = flowOf(PermissionStatus.Granted)
        override fun openSettings(permission: Permission): Boolean = false
    }

    /** A center whose notifications outlive the notifier: added ones are pending until presented, then delivered. */
    private class PersistentCenter : NotificationCenterPort {
        val pending: LinkedHashSet<String> = linkedSetOf()
        val delivered: LinkedHashSet<String> = linkedSetOf()
        var calls: Int = 0
        var killAt: Int = 0
        var killAfterEffect: Boolean = false

        private fun step(effect: () -> Unit) {
            calls += 1
            val dying: Boolean = calls == killAt
            if (dying && !killAfterEffect) throw ProcessKilled(calls)
            effect()
            if (dying) throw ProcessKilled(calls)
        }

        /** The OS presents what is pending, some time after the add. */
        fun present() {
            delivered += pending
            pending.clear()
        }

        override fun add(request: UNNotificationRequest, completion: (NSError?) -> Unit) {
            step {
                delivered.remove(request.identifier)
                pending += request.identifier
            }
            completion(null)
        }

        override fun removeDelivered(ids: List<String>) = step { delivered.removeAll(ids.toSet()) }
        override fun removePending(ids: List<String>) = step { pending.removeAll(ids.toSet()) }
        override fun removeAllDelivered() = step { delivered.clear() }
    }

    private fun note(percent: Int?): LocalNotification = LocalNotification(
        title = "t",
        body = "b",
        channel = NotificationChannelSpec(id = "chan", name = "Chan"),
        progress = percent?.let { NotificationProgress.Determinate(it) },
    )

    private fun notifier(center: NotificationCenterPort): IosNotifier = IosNotifier(
        AllowAll(), ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO), center,
    )

    private fun <T> bounded(block: suspend () -> T): T = runBlocking { withTimeout(TIMEOUT_MILLIS) { block() } }

    private fun session(notifier: Notifier, center: PersistentCenter) {
        bounded {
            notifier.post("a", note(30))
            notifier.post("b", note(30))
            center.present()
            notifier.post("a", note(60))
            notifier.cancel("b")
            notifier.post("c", note(null))
            center.present()
            notifier.cancelAll()
        }
    }

    @Test
    fun `a death at any single center call of a session leaves a process that posts again and cancels everything`() {
        val clean = PersistentCenter()
        session(notifier(clean), clean)
        val total: Int = clean.calls
        assertTrue(total >= 7, "the session is expected to call the center many times, made $total")

        for (afterEffect: Boolean in listOf(false, true)) {
            for (kill: Int in 1..total) {
                val label = "kill position $kill (${if (afterEffect) "after" else "before"} the effect)"
                val center = PersistentCenter()
                center.killAt = kill
                center.killAfterEffect = afterEffect
                val death: Throwable? = runCatching { session(notifier(center), center) }.exceptionOrNull()
                assertTrue(death is ProcessKilled, "$label: the session was expected to die, got $death")

                // The new process: same center, a new notifier, no kill any more.
                center.killAt = 0
                val survivor: IosNotifier = notifier(center)
                for ((id: String, percent: Int?) in listOf("a" to 60, "b" to 30, "c" to null)) {
                    val result: NotificationResult = bounded { survivor.post(id, note(percent)) }
                    assertEquals(NotificationResult.Posted, result, "$label: '$id' must post in the new process")
                }
                assertEquals(setOf("a", "b", "c"), (center.pending + center.delivered).toSet(), "$label: after the posts")
                for (id: String in listOf("a", "b", "c")) survivor.cancel(id)
                assertEquals(emptySet(), (center.pending + center.delivered).toSet(), "$label: a cancel of every id leaves neither pending nor delivered")
            }
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS: Long = 20_000L
    }
}
