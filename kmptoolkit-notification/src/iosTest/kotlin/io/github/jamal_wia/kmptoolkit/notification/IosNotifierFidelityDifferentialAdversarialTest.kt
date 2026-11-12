package io.github.jamal_wia.kmptoolkit.notification

import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import kotlin.test.Test
import kotlin.time.TestTimeSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSError
import platform.UserNotifications.UNNotificationRequest

/**
 * RecordingNotifier against the real iOS notifier over a fake notification center: one seeded script through
 * both, the double told what the real notifier answered, and both must agree on what is delivered after every
 * step ([NotifierFidelityDriver]).
 */
class IosNotifierFidelityDifferentialAdversarialTest {

    private class Center : NotificationCenterPort {
        val delivered: LinkedHashMap<String, UNNotificationRequest> = LinkedHashMap()
        override fun add(request: UNNotificationRequest, completion: (NSError?) -> Unit) {
            delivered[request.identifier] = request
            completion(null)
        }

        override fun removeDelivered(ids: List<String>) {
            ids.forEach { delivered.remove(it) }
        }

        override fun removePending(ids: List<String>) = Unit

        override fun removeAllDelivered() {
            delivered.clear()
        }
    }

    private val center = Center()
    private val permissions = MutablePermissions()

    private val host = object : NotifierFuzzHost {
        override val permissionGate: Boolean = true
        override val hasAppSwitch: Boolean = false

        override fun reset() {
            center.delivered.clear()
            permissions.status = PermissionStatus.Granted
        }

        override fun newNotifier(clock: TestTimeSource): Notifier =
            IosNotifier(permissions, NotifierProtocolFuzz.newCoalescer(clock), center)

        override fun setPermission(status: PermissionStatus) {
            permissions.status = status
        }

        override fun setAppSwitch(enabled: Boolean) = Unit

        override fun shown(ids: List<String>): Map<String, Pair<String, String>> =
            ids.mapNotNull { id: String ->
                center.delivered[id]?.let { request: UNNotificationRequest -> id to (request.content.title to request.content.body) }
            }.toMap()
    }

    @Test
    fun `the double and the real notifier agree on the tray over seeded scripts`() {
        runBlocking { withTimeout(120_000) { NotifierFidelityDriver.run(host, seeds = 100, steps = 60) } }
    }

    @Test
    fun `a blank id is rejected alike by the double and the real notifier`() {
        runBlocking {
            withTimeout(60_000) { NotifierFidelityDriver.blankIdRejectedAlike(host.newNotifier(TestTimeSource())) }
        }
    }
}
