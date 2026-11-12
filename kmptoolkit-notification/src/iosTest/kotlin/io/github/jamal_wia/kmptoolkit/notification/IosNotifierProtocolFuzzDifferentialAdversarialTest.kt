package io.github.jamal_wia.kmptoolkit.notification

import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import kotlin.test.Test
import kotlin.time.TestTimeSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSError
import platform.UserNotifications.UNNotificationRequest

/**
 * The iOS half of the cross-platform differential: the seeded operation sequences and the reference
 * model of [NotifierProtocolFuzz] that the Android notifier is run through in
 * `AndroidNotifierProtocolFuzzDifferentialAdversarialTest`, here over a fake notification center
 * behind the internal [NotificationCenterPort] seam. iOS has no app-wide switch apart from the
 * permission, and answers [NotificationResult.PermissionDenied] for every unusable authorization.
 */
class IosNotifierProtocolFuzzDifferentialAdversarialTest {

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
    fun `seeded sequences give the answers and the tray of the shared model`() {
        runBlocking { withTimeout(120_000) { NotifierProtocolFuzz.run(host, seeds = 200, steps = 50) } }
    }
}
