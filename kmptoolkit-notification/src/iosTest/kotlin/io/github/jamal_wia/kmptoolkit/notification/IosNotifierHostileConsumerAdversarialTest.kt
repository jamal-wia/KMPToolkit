package io.github.jamal_wia.kmptoolkit.notification

import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionController
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSError
import platform.UserNotifications.UNNotificationRequest

/**
 * A consumer that misbehaves: a permission controller that throws (a runtime failure is an answer, an
 * `Error` is not ours to swallow but must leave nothing behind), a button list and an extras map that
 * throw when read. Derived from docs 04: `post` never throws for a device or consumer-side runtime
 * failure (it answers `Failed`), and a failed post leaves the notifier usable. Whatever escapes, no claim
 * outlives the call and no lock stays held (the next post and a cancel both complete).
 */
class IosNotifierHostileConsumerAdversarialTest {

    private class Center : NotificationCenterPort {
        val delivered: MutableSet<String> = mutableSetOf()
        override fun add(request: UNNotificationRequest, completion: (NSError?) -> Unit) {
            delivered += request.identifier
            completion(null)
        }

        override fun removeDelivered(ids: List<String>) {
            delivered.removeAll(ids.toSet())
        }

        override fun removePending(ids: List<String>) = Unit
        override fun removeAllDelivered() = delivered.clear()
    }

    private class Throwing(val failure: () -> Throwable) : PermissionController {
        var armed: Boolean = true
        override suspend fun check(permission: Permission): PermissionStatus {
            if (armed) throw failure()
            return PermissionStatus.Granted
        }

        override suspend fun request(permission: Permission): PermissionStatus = PermissionStatus.Granted
        override suspend fun request(permissions: Collection<Permission>): Map<Permission, PermissionStatus> =
            permissions.associateWith { PermissionStatus.Granted }

        override fun observe(permission: Permission): Flow<PermissionStatus> = flowOf(PermissionStatus.Granted)
        override fun openSettings(permission: Permission): Boolean = false
    }

    private val center = Center()
    private val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO)

    private fun note(
        actions: List<NotificationAction> = emptyList(),
        extras: Map<String, String>? = null,
    ): LocalNotification = LocalNotification(
        title = "T", body = "B", channel = NotificationChannelSpec("c", "C"),
        progress = NotificationProgress.Determinate(50), actions = actions, contentExtras = extras,
    )

    private fun <T> bounded(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    @Test
    fun `a controller that throws a runtime exception is an answer and leaves nothing behind`() {
        val controller = Throwing { IllegalStateException("not ready") }
        val notifier: Notifier = IosNotifier(controller, coalescer, center)

        val failed: NotificationResult = bounded { notifier.post("a", note()) }

        assertEquals("not ready", assertIs<NotificationResult.Failed>(failed).cause?.message)
        assertEquals(0, coalescer.claimCountForTest())
        controller.armed = false
        notifier.cancel("a")
        assertEquals(NotificationResult.Posted, bounded { notifier.post("a", note()) })
    }

    @Test
    fun `a controller that throws an Error escapes the post but leaves no claim and no held lock`() {
        val controller = Throwing { NotImplementedError("stub") }
        val notifier: Notifier = IosNotifier(controller, coalescer, center)

        assertFailsWith<NotImplementedError> { bounded { notifier.post("a", note()) } }

        assertEquals(0, coalescer.claimCountForTest())
        controller.armed = false
        notifier.cancel("a")
        notifier.cancelAll()
        assertEquals(NotificationResult.Posted, bounded { notifier.post("a", note()) })
        assertEquals(NotificationResult.Coalesced, bounded { notifier.post("a", note()) })
    }

    @Test
    fun `a button list that throws when read is a Failed answer and the claim is released`() {
        val hostile: List<NotificationAction> = object : AbstractList<NotificationAction>() {
            override val size: Int get() = 1
            override fun get(index: Int): NotificationAction = throw ConcurrentModificationException("edited under us")
        }
        val notifier: Notifier = IosNotifier(Throwing { error("unused") }.also { it.armed = false }, coalescer, center)

        val failed: NotificationResult = bounded { notifier.post("a", note(actions = hostile)) }

        assertIs<NotificationResult.Failed>(failed)
        assertEquals(0, coalescer.claimCountForTest())
        assertEquals(NotificationResult.Posted, bounded { notifier.post("a", note()) })
    }

    @Test
    fun `an extras map that throws when read is a Failed answer and the claim is released`() {
        val hostile: Map<String, String> = object : AbstractMap<String, String>() {
            override val entries: Set<Map.Entry<String, String>> get() = throw ConcurrentModificationException("edited under us")
        }
        val notifier: Notifier = IosNotifier(Throwing { error("unused") }.also { it.armed = false }, coalescer, center)

        val failed: NotificationResult = bounded { notifier.post("a", note(extras = hostile)) }

        assertIs<NotificationResult.Failed>(failed)
        assertEquals(0, coalescer.claimCountForTest())
        assertEquals(NotificationResult.Posted, bounded { notifier.post("a", note()) })
    }
}
