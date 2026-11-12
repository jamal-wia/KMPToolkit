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
 * Round 15 (contract lawyer): the iOS column of the `LocalNotification` table and the options sentence.
 *
 * - 04-api-reference: "`NotificationOptions` ... **Android only**; iOS ignores every field."
 * - Table: `icon` ignored, `progress` "ignored for display; drives coalescing", `ongoing` ignored,
 *   `autoCancel` ignored, `actions` "ignored - use `iosCategoryId`", `contentExtras` "ignored - handle in
 *   your delegate".
 * - 03-guide, tap target: "routing there is Swift code that reads `userInfo`, which this module does not set."
 */
class IosNotifierIgnoredFieldsContractAdversarialTest {

    private class FakeCenter : NotificationCenterPort {
        val requests: MutableList<UNNotificationRequest> = mutableListOf()
        override fun add(request: UNNotificationRequest, completion: (NSError?) -> Unit) {
            requests += request
            completion(null)
        }

        override fun removeDelivered(ids: List<String>) = Unit
        override fun removePending(ids: List<String>) = Unit
        override fun removeAllDelivered() = Unit
    }

    private class AllowAll : PermissionController {
        override suspend fun check(permission: Permission): PermissionStatus = PermissionStatus.Granted
        override suspend fun request(permission: Permission): PermissionStatus = PermissionStatus.Granted
        override suspend fun request(permissions: Collection<Permission>): Map<Permission, PermissionStatus> =
            permissions.associateWith { PermissionStatus.Granted }
        override fun observe(permission: Permission): Flow<PermissionStatus> = flowOf(PermissionStatus.Granted)
        override fun openSettings(permission: Permission): Boolean = false
    }

    private val channel = NotificationChannelSpec(id = "chan", name = "Chan")
    private val center: FakeCenter = FakeCenter()
    private val notifier: Notifier = IosNotifier(AllowAll(), ProgressCoalescer(minInterval = Duration.ZERO), center)

    private fun <T> bounded(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun UNNotificationRequest.fingerprint(): List<Any?> = listOf(
        identifier,
        content.title,
        content.body,
        content.threadIdentifier,
        content.categoryIdentifier,
        content.sound == null,
        content.interruptionLevel,
        content.userInfo.size,
        trigger == null,
    )

    @Test
    fun `every NotificationOptions field is ignored - the request equals the one a plain post makes`() {
        val base = LocalNotification(title = "T", body = "B", channel = channel, iosCategoryId = "cat")
        val stop = NotificationAction("stop", "Stop")
        val loud = NotificationOptions(
            alertOnce = false,
            dismissAction = stop,
            mediaStyle = true,
            actionIcons = mapOf("stop" to NotificationIcon.AndroidDrawable(42)),
        )

        assertEquals(NotificationResult.Posted, bounded { notifier.post("plain", base) })
        assertEquals(NotificationResult.Posted, bounded { notifier.post("plain", base, NotificationOptions.DEFAULT) })
        assertEquals(NotificationResult.Posted, bounded { notifier.post("plain", base.copy(actions = listOf(stop)), loud) })

        val fingerprints: List<List<Any?>> = center.requests.map { it.fingerprint() }
        assertEquals(3, fingerprints.size)
        assertEquals(fingerprints[0], fingerprints[1])
        assertEquals(fingerprints[0], fingerprints[2], "options and Android button lists changed the iOS request")
    }

    @Test
    fun `icon ongoing autoCancel progress actions and contentExtras never reach the request and userInfo stays empty`() {
        val plain = LocalNotification(title = "T", body = "B", channel = channel)
        val loaded = plain.copy(
            icon = NotificationIcon.AndroidDrawable(7),
            ongoing = true,
            autoCancel = false,
            progress = NotificationProgress.Determinate(40),
            actions = listOf(NotificationAction("a", "A"), NotificationAction("b", "B")),
            contentExtras = mapOf("screen" to "downloads", "alarm_id" to "x"),
        )

        bounded { notifier.post("one", plain) }
        bounded { notifier.post("two", loaded) }

        val (a, b) = center.requests.map { it.fingerprint().drop(1) }
        assertEquals(a, b, "an Android-only field changed the iOS request")
        assertTrue(center.requests.all { it.content.userInfo.isEmpty() }, "the module documents that it does not set userInfo")
    }
}
