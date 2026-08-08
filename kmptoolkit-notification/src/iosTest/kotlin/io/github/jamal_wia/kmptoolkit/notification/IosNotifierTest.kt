package io.github.jamal_wia.kmptoolkit.notification

import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionHandler
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * What can honestly be asserted about the iOS notifier from a unit test.
 *
 * A test binary is not an app bundle, and `UNUserNotificationCenter.currentNotificationCenter()`
 * says so out loud: it raises `NSInternalInconsistencyException` ("bundleProxyForCurrentProcess is
 * nil") the moment it is reached. So every call that touches the centre — posting for real,
 * cancelling, cancelling everything — is a device question, not a unit-test one.
 *
 * What this level *can* pin down is everything decided before the centre is touched, which is where
 * the typed results come from. The cancel path's own contract (an unknown id is a no-op, and the
 * coalescing state is forgotten) is covered on the Android side, against a real notification
 * manager, and by `ProgressCoalescerTest` for the shared half.
 */
class IosNotifierTest {

    private val notification = LocalNotification(
        title = "T",
        body = "B",
        channel = NotificationChannelSpec(id = "downloads", name = "Downloads"),
    )

    private fun notifier(status: PermissionStatus): Notifier =
        createNotifier(permissionHandler = FixedPermissionHandler(status))

    @Test
    fun `posting without authorization reports the permission rather than touching the center`() =
        runTest {
            val result: NotificationResult =
                notifier(PermissionStatus.Denied()).post("a", notification)

            assertEquals(NotificationResult.PermissionDenied, result)
        }

    @Test
    fun `a permanently denied authorization is reported the same way`() = runTest {
        val result: NotificationResult =
            notifier(PermissionStatus.PermanentlyDenied).post("a", notification)

        assertEquals(NotificationResult.PermissionDenied, result)
    }

    private class FixedPermissionHandler(private val status: PermissionStatus) : PermissionHandler {
        override suspend fun check(permission: Permission): PermissionStatus = status
        override suspend fun request(permission: Permission): PermissionStatus = status
        override fun openAppSettings(): Boolean = false
    }
}
