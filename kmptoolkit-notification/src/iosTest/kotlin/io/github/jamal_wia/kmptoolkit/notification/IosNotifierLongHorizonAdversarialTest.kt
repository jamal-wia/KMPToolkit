package io.github.jamal_wia.kmptoolkit.notification

import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import kotlin.test.Test
import kotlin.time.TestTimeSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSError
import platform.UserNotifications.UNNotificationRequest

/**
 * Round 24: the 30-day virtual-time model of [NotifierLongHorizon] through the real iOS notifier over a fake
 * center behind the internal [NotificationCenterPort] seam.
 */
class IosNotifierLongHorizonAdversarialTest {

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
    fun `seeded 30 day sequences give the answers and the tray of the documented model`() {
        runBlocking {
            withTimeout(120_000) {
                val tally: NotifierLongHorizon.Tally = NotifierLongHorizon.run(host, seeds = 10, steps = 400)
                NotifierLongHorizon.assertExercised(tally, host)
            }
        }
    }
}
